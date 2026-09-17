package fan.summer.fengyu.ai;

import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import fan.summer.fengyu.runtime.RuntimePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Conversation-scoped registry for chat resources, implementing the send-transaction model of
 * the RC task doc (revision 2): <b>selection only writes a draft description; host copies are
 * created when a send is prepared; per-turn {@code FileRef}s are derived at the execution
 * boundary and released when the turn ends.</b>
 *
 * <p>Ownership model:
 * <ul>
 *   <li>A {@link Scope} is created by the backend and bound to the requesting user. The optional
 *       {@code conversationId} links once the draft is persisted.</li>
 *   <li>Picker selections and uploads NEVER touch this service — they live as frontend draft
 *       attachments until the user sends (E01/E02). A send {@link SendTransaction} (keyed by the
 *       client's {@code sendId}) copies each attachment into the host-owned copy store exactly
 *       once (E05); a conflicting replay is rejected (E07); an aborted or expired transaction
 *       reclaims its copies (E04/E12).</li>
 *   <li>Every committed {@link Resource} owns ONE host copy (§14-3). A chat turn's
 *       {@link #acquireLease} derives live read grants over that copy for every eligible plugin;
 *       {@link #releaseLease} revokes them but keeps the copy, so the next turn can say
 *       "continue" and re-derive access (E09/E10). Removing or refreshing a resource retires the
 *       superseded copy — physically deleted only once no lease still pins it (E11).</li>
 *   <li>The output location is host-save authorization only: it never mints a worker grant
 *       (invariant 5.3-8).</li>
 *   <li>Scopes and grants are in-memory: a restart wipes the registry and (via the startup
 *       sweep) the copy store — old conversations load with empty resource lists and their
 *       persisted attachment metadata shows as unavailable (E13; cross-restart re-authorization
 *       is deliberately out of RC scope).</li>
 * </ul>
 */
@Service
public class ChatResourceScopeService {

    private static final Logger log = LoggerFactory.getLogger(ChatResourceScopeService.class);
    private static final Logger AUDIT =
            LoggerFactory.getLogger("fan.summer.fengyu.audit.plugin-file-grant");

    /** Idle scopes are closed and fully reclaimed once older than this. */
    static final Duration SCOPE_IDLE_TTL = Duration.ofHours(24);
    /** Send transactions that never committed are rolled back after this (E12). */
    static final Duration SEND_TTL = Duration.ofMinutes(30);
    /** Hard cap per scope — a runaway attach loop must not exhaust the registry. */
    static final int MAX_RESOURCES_PER_SCOPE = 64;
    /** Total host-copy bytes one scope may pin (chat inputs are bounded working sets). */
    static final long MAX_SCOPE_COPY_BYTES = 2L * 1024 * 1024 * 1024;
    /** One copied file may not exceed this (mirrors the plugin grant service bound). */
    static final long MAX_SINGLE_FILE_BYTES = 100L * 1024 * 1024;
    /** One copied directory tree may not exceed this. */
    static final long MAX_TREE_BYTES = 500L * 1024 * 1024;
    private static final int MAX_TREE_FILES = 2_000;

    private final ChatFileGrantService chatFiles;
    private final PluginFileGrantService pluginFiles;
    private final Path copyRoot;
    private final Map<String, Scope> scopes = new ConcurrentHashMap<>();
    /** Test seam: the sweeps and TTLs read time from here. */
    java.util.function.Supplier<Instant> clock = Instant::now;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatResourceScopeService(ChatFileGrantService chatFiles, PluginFileGrantService pluginFiles) {
        this(chatFiles, pluginFiles, RuntimePaths.root().resolve("chat-resources"));
    }

    public ChatResourceScopeService(ChatFileGrantService chatFiles, PluginFileGrantService pluginFiles,
            Path copyRoot) {
        this.chatFiles = chatFiles;
        this.pluginFiles = pluginFiles;
        this.copyRoot = copyRoot.toAbsolutePath().normalize();
        // The registry is in-memory, so nothing under the copy root can still be owned — a fresh
        // boot reclaims whatever a previous run (or a crashed send) left behind.
        deleteTree(this.copyRoot);
    }

    // ── scope lifecycle ─────────────────────────────────────────────────────────────────

    /** Creates a scope owned by {@code userId}; returns its server-minted id. */
    public synchronized String createScope(Long userId) {
        sweepExpired(clock.get());
        String scopeId = "cs_" + UUID.randomUUID();
        scopes.put(scopeId, new Scope(scopeId, userId == null ? 0L : userId, clock.get()));
        return scopeId;
    }

    /** Idempotent close: revokes every lease grant, reclaims every copy, forgets the scope. */
    public synchronized void closeScope(String scopeId, Long userId) {
        Scope scope = scopeId == null ? null : scopes.get(scopeId);
        if (scope == null) return; // already closed (or never existed) — idempotent no-op
        if (scope.userId != (userId == null ? 0L : userId)) {
            throw new IllegalArgumentException("Resource scope belongs to another session");
        }
        scopes.remove(scope.scopeId);
        for (Lease lease : scope.leases.values()) {
            for (ActiveFileRef ref : lease.refs()) revokeQuietly(ref);
        }
        for (Resource resource : scope.resources.values()) {
            deleteTree(resource.copyPath());
        }
        for (RetiredCopy retired : scope.retiredCopies.values()) {
            deleteTree(retired.copyPath());
        }
        for (SendTransaction tx : scope.sends.values()) {
            for (Resource pending : tx.pendingResources.values()) deleteTree(pending.copyPath());
        }
        scope.resources.clear();
        scope.retiredCopies.clear();
        scope.sends.clear();
        scope.leases.clear();
        scope.closed = true;
    }

    /** Associates the persisted conversation id once the draft is saved. Idempotent. */
    public synchronized void bindConversation(String scopeId, Long userId, Long conversationId) {
        Scope scope = owned(scopeId, userId);
        if (conversationId != null) scope.conversationId = conversationId;
    }

    public synchronized void setOutputTarget(String scopeId, Long userId, String rawPath) {
        Scope scope = owned(scopeId, userId);
        if (rawPath == null || rawPath.isBlank()) {
            scope.outputTarget = null;
            return;
        }
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Output location is not an existing directory");
        }
        AUDIT.info("chat output target: scope={} path={}", scopeId, path);
        // Host-save authorization only — deliberately NO plugin grant is minted here.
        scope.outputTarget = path.toString();
    }

    // ── send transactions (§15) ─────────────────────────────────────────────────────────

    /** One native attachment descriptor of a send: the draft attachment id + its source path. */
    public record NativeAttachment(String attachmentId, String path, String kind) {}

    /**
     * Prepares (idempotently) one send transaction: every native attachment is copied into the
     * host store NOW — the copy content is the send-time truth (§14-6) — and stays owned by the
     * transaction until commit. Selection never reaches this method (E01); an attachment that
     * fails reclaims the whole transaction's copies and reports which id failed (E04); the same
     * {@code sendId} with the same payload replays the same result (E05), a different payload is
     * a conflict (E07).
     */
    public synchronized SendStatus prepareSend(String scopeId, Long userId, String sendId,
            List<NativeAttachment> natives) {
        Scope scope = owned(scopeId, userId);
        if (sendId == null || sendId.isBlank()) throw new IllegalArgumentException("Missing send id");
        scope.lastActiveAt = clock.get();
        SendTransaction existing = scope.sends.get(sendId);
        if (existing != null) {
            if (!existing.isReplayableAsPrepared() && existing.state() != SendTransaction.STATE_FAILED) {
                throw new IllegalArgumentException("Send " + sendId + " already " + existing.state());
            }
            if (!Objects.equals(existing.fingerprint(), fingerprintOf(natives))) {
                throw new IllegalArgumentException("Send " + sendId + " was prepared with different attachments");
            }
            return existing.status();
        }
        sweepExpiredSends(scope, clock.get());
        SendTransaction tx = new SendTransaction(sendId, scope.scopeId,
                fingerprintOf(natives), clock.get());
        scope.sends.put(sendId, tx);
        try {
            for (NativeAttachment attachment : natives == null ? List.<NativeAttachment>of() : natives) {
                prepareNativeInto(scope, tx, attachment);
            }
            tx.state = SendTransaction.STATE_PREPARED;
        } catch (RuntimeException e) {
            failSend(scope, tx, e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }
        return tx.status();
    }

    /**
     * Adds (idempotently, by attachment id) one browser upload to a prepared send. Rejected once
     * the send is committing — uploads belong to the send payload, never to selection time.
     */
    public synchronized SendStatus addUploadToSend(String scopeId, Long userId, String sendId,
            String attachmentId, MultipartFile file) {
        Scope scope = owned(scopeId, userId);
        SendTransaction tx = sendOf(scope, sendId);
        if (!tx.state().equals(SendTransaction.STATE_PREPARED)
                && !tx.state().equals(SendTransaction.STATE_PREPARING)) {
            throw new IllegalArgumentException(
                    "Send " + sendId + " is " + tx.state() + " — uploads belong to the payload");
        }
        if (tx.pendingResources.containsKey(attachmentId)) return tx.status(); // idempotent
        Resource resource = copyUpload(scope, tx.sendId, attachmentId,
                file.getOriginalFilename() == null ? "file"
                        : Path.of(file.getOriginalFilename()).getFileName().toString(),
                "file", List.of(file), List.of(file.getOriginalFilename() == null ? "file"
                        : Path.of(file.getOriginalFilename()).getFileName().toString()));
        tx.attach(attachmentId, resource);
        return tx.status();
    }

    /** Same as {@link #addUploadToSend} for a browser directory upload. */
    public synchronized SendStatus addUploadDirectoryToSend(String scopeId, Long userId, String sendId,
            String attachmentId, List<MultipartFile> files, List<String> paths) {
        Scope scope = owned(scopeId, userId);
        SendTransaction tx = sendOf(scope, sendId);
        if (!tx.state().equals(SendTransaction.STATE_PREPARED)
                && !tx.state().equals(SendTransaction.STATE_PREPARING)) {
            throw new IllegalArgumentException(
                    "Send " + sendId + " is " + tx.state() + " — uploads belong to the payload");
        }
        if (tx.pendingResources.containsKey(attachmentId)) return tx.status(); // idempotent
        String top = paths.isEmpty() || Path.of(paths.get(0)).getNameCount() == 0
                ? "directory" : Path.of(paths.get(0)).getName(0).toString();
        Resource resource = copyUpload(scope, tx.sendId, attachmentId, top, "directory", files, paths);
        tx.attach(attachmentId, resource);
        return tx.status();
    }

    /** The transaction's current state — the recovery entry point after a lost response (E06). */
    public synchronized SendStatus sendStatus(String scopeId, Long userId, String sendId) {
        Scope scope = owned(scopeId, userId);
        return sendOf(scope, sendId).status();
    }

    /** Aborts an uncommitted send: its exclusive copies are reclaimed (E02's server half). */
    public synchronized void abortSend(String scopeId, Long userId, String sendId) {
        Scope scope = owned(scopeId, userId);
        SendTransaction tx = sendOf(scope, sendId);
        if (SendTransaction.STATE_COMMITTED.equals(tx.state())) {
            throw new IllegalArgumentException("Send " + sendId + " is already committed");
        }
        if (SendTransaction.STATE_COMMITTING.equals(tx.state())) {
            throw new IllegalArgumentException("Send " + sendId + " is committing");
        }
        failSend(scope, tx, "aborted");
    }

    /**
     * First half of the commit bracket (called by the scoped chat turn): the transaction's
     * prepared copies become committed scope resources — same native path replaces the previous
     * revision (A03) — and the send stops accepting payloads. Idempotent replays of a COMMITTED
     * send are answered by {@link #replayableResult}; a second racer sees a conflict.
     */
    public synchronized void beginCommit(String scopeId, Long userId, String sendId) {
        Scope scope = owned(scopeId, userId);
        SendTransaction tx = sendOf(scope, sendId);
        switch (tx.state()) {
            case SendTransaction.STATE_COMMITTED, SendTransaction.STATE_COMMITTING ->
                    throw new IllegalArgumentException("Send " + sendId + " is already " + tx.state());
            case SendTransaction.STATE_FAILED ->
                    throw new IllegalArgumentException("Send " + sendId + " failed: " + tx.error());
            default -> { }
        }
        tx.state = SendTransaction.STATE_COMMITTING;
        try {
            for (Resource pending : tx.pendingResources.values()) {
                commitInto(scope, tx, pending);
            }
        } catch (RuntimeException e) {
            // A PARTIAL move must never stick (quota or resource-count ceiling hit mid-loop):
            // undo exactly what this begin moved via the journal, mark the send failed, and
            // rethrow — the client retries with a fresh sendId. Without this the transaction
            // would sit half-committed in COMMITTING until the TTL sweep.
            failSend(scope, tx, "The send could not be committed: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
            throw e;
        }
    }

    /**
     * Second half of the commit bracket: records the accepted chat response so a lost-response
     * retry with the same {@code sendId} replays it instead of sending twice (E06), and retires
     * the copies this send superseded (safe now — the commit can no longer roll back).
     */
    public synchronized void finishCommit(String scopeId, Long userId, String sendId,
            Map<String, Object> response) {
        Scope scope = owned(scopeId, userId);
        SendTransaction tx = sendOf(scope, sendId);
        for (Resource replaced : tx.rollback.values()) {
            if (replaced != null) retireCopy(scope, replaced);
        }
        tx.result = Map.copyOf(response);
        tx.state = SendTransaction.STATE_COMMITTED;
    }

    /** The recorded response of a committed send, or null — the chat idempotence gate (E05/E06). */
    public synchronized Map<String, Object> replayableResult(String scopeId, Long userId, String sendId) {
        Scope scope = owned(scopeId, userId);
        SendTransaction tx = scope.sends.get(sendId);
        if (tx == null || !SendTransaction.STATE_COMMITTED.equals(tx.state())) return null;
        return tx.result;
    }

    /**
     * The resource ids a committing send introduced into the scope — the chat turn must lease
     * exactly these plus the ids the client explicitly referenced ("continue" turns).
     */
    public synchronized List<String> sendResourceIds(String scopeId, Long userId, String sendId) {
        Scope scope = owned(scopeId, userId);
        SendTransaction tx = sendOf(scope, sendId);
        return List.copyOf(tx.rollback.keySet());
    }

    /**
     * Rolls a committing send back after the chat turn failed before producing a stream: every
     * resource this transaction introduced is removed again (restoring replaced revisions), so
     * the user's draft is the only thing left (E03/E04 semantics at commit time).
     */
    public synchronized void failSend(String scopeId, Long userId, String sendId, String reason) {
        Scope scope = owned(scopeId, userId);
        SendTransaction tx = scope.sends.get(sendId);
        if (tx == null) return;
        failSend(scope, tx, reason);
    }

    private void failSend(Scope scope, SendTransaction tx, String reason) {
        for (var rollback : tx.rollback.entrySet()) {
            Resource introduced = scope.resources.remove(rollback.getKey());
            if (introduced != null) deleteCopyUnlessPinned(scope, introduced.copyPath());
            Resource restored = rollback.getValue();
            if (restored != null) scope.resources.put(restored.resourceId(), restored);
        }
        tx.rollback.clear();
        for (Resource pending : tx.pendingResources.values()) deleteCopyUnlessPinned(scope, pending.copyPath());
        tx.pendingResources.clear();
        tx.state = SendTransaction.STATE_FAILED;
        tx.error = reason;
    }

    /** Deletes a copy now, or defers while a live lease still pins it (E11: recycle exactly once). */
    private void deleteCopyUnlessPinned(Scope scope, Path copy) {
        boolean pinned = scope.leases.values().stream()
                .anyMatch(lease -> lease.pinnedCopies().contains(copy));
        if (pinned) {
            scope.retiredCopies.putIfAbsent(copy.toString(), new RetiredCopy(copy));
            return;
        }
        deleteTree(copy);
        scope.retiredCopies.remove(copy.toString());
    }

    /**
     * Makes one prepared copy a committed scope resource, replacing the previous revision of the
     * same native input path (A03). The superseded copy is retired only at {@link #finishCommit}
     * — a rolled-back commit must restore the old revision with its copy intact.
     */
    private Resource commitInto(Scope scope, SendTransaction tx, Resource pending) {
        if (scope.resources.size() >= MAX_RESOURCES_PER_SCOPE) {
            throw new IllegalArgumentException("This conversation already carries the maximum number of resources");
        }
        enforceCopyQuota(scope, pending.size());
        Resource replaced = null;
        for (Resource existing : scope.resources.values()) {
            if ("input".equals(pending.purpose()) && "input".equals(existing.purpose())
                    && pending.sourceKey().equals(existing.sourceKey())) {
                replaced = existing;
                break;
            }
        }
        Resource committed = replaced == null ? pending : new Resource(replaced.resourceId(),
                scope.scopeId, pending.name(), pending.kind(), pending.purpose(), pending.access(),
                replaced.revision() + 1, STATUS_READY, pending.source(), pending.sourceKey(),
                pending.displayPath(), pending.size(), pending.copyPath());
        if (tx != null) tx.rollback.put(committed.resourceId(), replaced);
        scope.resources.put(committed.resourceId(), committed);
        return committed;
    }

    private void prepareNativeInto(Scope scope, SendTransaction tx, NativeAttachment attachment) {
        if (tx.pendingResources.containsKey(attachment.attachmentId())) return;
        if (!List.of("file", "directory").contains(attachment.kind())) {
            throw new IllegalArgumentException("Invalid attachment kind");
        }
        Path source = Path.of(attachment.path()).toAbsolutePath().normalize();
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("The selected file is no longer available: " + source);
        }
        if ("directory".equals(attachment.kind()) != Files.isDirectory(source)) {
            throw new IllegalArgumentException("Selected path kind does not match: " + source);
        }
        Resource resource = copyNative(scope, tx.sendId, attachment.attachmentId(), source,
                attachment.kind());
        tx.attach(attachment.attachmentId(), resource);
    }

    private static String fingerprintOf(List<NativeAttachment> natives) {
        List<String> parts = new ArrayList<>();
        for (NativeAttachment a : natives == null ? List.<NativeAttachment>of() : natives) {
            parts.add(a.attachmentId() + "|" + a.path() + "|" + a.kind());
        }
        parts.sort(String::compareTo);
        return String.join(";", parts);
    }

    // ── text-path adoption (§16.3) ──────────────────────────────────────────────────────

    /**
     * Adopts absolute paths the user explicitly typed into the latest message as scope-owned
     * read-only resources: a host copy is taken NOW (send-time truth) and, when a sendId is
     * given, tracked in that transaction so a failed turn rolls the adoption back (invariant
     * 5.3-7: a user-supplied path enters the same registry as a picker selection — at send time,
     * never at selection time). Returns the resources minted.
     */
    public synchronized List<Resource> adoptTextInput(String scopeId, Long userId, String text,
            String sendId) {
        Scope scope = owned(scopeId, userId);
        List<Resource> adopted = new ArrayList<>();
        if (text == null || text.isBlank()) return adopted;
        for (Path path : ChatFileGrantService.extractExistingPaths(text)) {
            boolean directory = Files.isDirectory(path);
            Resource resource = copyNative(scope, sendId == null ? "tx_text" : sendId,
                    "text_" + UUID.randomUUID(), path, directory ? "directory" : "file");
            try {
                SendTransaction tx = sendId == null ? null : ephemeralTx(scope, sendId);
                adopted.add(commitInto(scope, tx, resource));
            } catch (RuntimeException e) {
                deleteTree(resource.copyPath());
                for (Resource minted : adopted) removeResource(scopeId, userId, minted.resourceId());
                throw e;
            }
        }
        return adopted;
    }

    /** A never-committed transaction view used only to reuse commitInto's replace logic. */
    private SendTransaction ephemeralTx(Scope scope, String sendId) {
        SendTransaction tx = scope.sends.get(sendId);
        if (tx != null) return tx;
        SendTransaction ephemeral = new SendTransaction(sendId, scope.scopeId, "", clock.get());
        scope.sends.put(sendId, ephemeral);
        return ephemeral;
    }

    // ── resource operations ─────────────────────────────────────────────────────────────

    /**
     * Re-copies a native read-only resource from its original path: the fresh copy swaps in
     * atomically; the previous revision's copy is retired (kept while a lease still pins it) so
     * a live turn keeps reading the version it started with (A10; failure keeps the old copy).
     */
    public synchronized Resource refresh(String scopeId, Long userId, String resourceId) {
        Scope scope = owned(scopeId, userId);
        Resource resource = scope.resources.get(resourceId);
        if (resource == null) throw unknownResource();
        if (!"input".equals(resource.purpose()) || !"native".equals(resource.source())
                || !"file".equals(resource.kind())) {
            throw new IllegalArgumentException("Only native read-only files can be refreshed");
        }
        Path source = Path.of(resource.sourceKey());
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("The original file is no longer available");
        }
        Resource updated = copyNative(scope, "tx_refresh", resource.resourceId(), source, "file");
        Resource swapped = new Resource(resource.resourceId(), scope.scopeId, updated.name(),
                resource.kind(), resource.purpose(), resource.access(), resource.revision() + 1,
                STATUS_READY, resource.source(), resource.sourceKey(), resource.displayPath(),
                updated.size(), updated.copyPath());
        scope.resources.put(resource.resourceId(), swapped);
        retireCopy(scope, resource);
        return swapped;
    }

    /**
     * Idempotent removal: the resource stops being resolvable for new turns immediately; the
     * copy is physically deleted once no lease still pins it (5.3-4, E11).
     */
    public synchronized void removeResource(String scopeId, Long userId, String resourceId) {
        Scope scope = owned(scopeId, userId);
        Resource resource = scope.resources.remove(resourceId);
        if (resource == null) return; // idempotent
        retireCopy(scope, resource);
    }

    // ── chat-turn resolution (the authorization gate) ────────────────────────────────────

    /**
     * Resolves the resources a chat turn may use and records an execution lease over their
     * CURRENT copies: live read grants are derived for every eligible plugin here — and only
     * here (E01's counterpart at the execution boundary) — and revoked by {@link #releaseLease}.
     * Unknown ids, resources from another scope, or removed resources are rejected: the frontend
     * hiding a chip is never the authorization.
     */
    public synchronized Lease acquireLease(String scopeId, Long userId, List<String> resourceIds) {
        Scope scope = owned(scopeId, userId);
        List<String> ids = resourceIds == null ? List.of() : resourceIds;
        Map<String, Resource> resolved = new LinkedHashMap<>();
        for (String id : ids) {
            Resource resource = scope.resources.get(id);
            if (resource == null || !STATUS_READY.equals(resource.status())) {
                throw unknownResource();
            }
            resolved.put(id, resource);
        }
        List<ActiveFileRef> refs = new ArrayList<>();
        try {
            for (Resource resource : resolved.values()) {
                refs.addAll(chatFiles.grantHostCopyLive(resource.copyPath(), resource.kind()));
            }
        } catch (RuntimeException e) {
            for (ActiveFileRef ref : refs) revokeQuietly(ref);
            throw e;
        }
        List<Path> pinnedCopies = resolved.values().stream().map(Resource::copyPath).toList();
        Lease lease = new Lease("lease_" + UUID.randomUUID(), scope.scopeId,
                List.copyOf(refs), pinnedCopies, clock.get());
        scope.leases.put(lease.leaseId(), lease);
        scope.lastActiveAt = clock.get();
        return lease;
    }

    /** Releases an execution lease (idempotent): grants go, copies stay unless retired (E09). */
    public synchronized void releaseLease(String scopeId, String leaseId) {
        Scope scope = scopes.get(scopeId);
        if (scope == null) return;
        Lease lease = scope.leases.remove(leaseId);
        if (lease == null) return; // idempotent
        for (ActiveFileRef ref : lease.refs()) revokeQuietly(ref);
        reclaimUnpinnedRetiredCopies(scope);
    }

    public synchronized int activeLeaseCount(String scopeId) {
        Scope scope = scopes.get(scopeId);
        return scope == null ? 0 : scope.leases.size();
    }

    /** The scope's current view: aggregated resources plus the host-save output target. */
    public synchronized ScopeSnapshot snapshot(String scopeId, Long userId) {
        Scope scope = owned(scopeId, userId);
        scope.lastActiveAt = clock.get();
        return new ScopeSnapshot(List.copyOf(scope.resources.values()), scope.outputTarget,
                scope.conversationId);
    }

    public synchronized String outputTarget(String scopeId) {
        Scope scope = scopes.get(scopeId);
        return scope == null ? null : scope.outputTarget;
    }

    /** The conversation a scope is bound to, if any — artifact records use this for recovery. */
    public synchronized Long conversationIdOf(String scopeId) {
        Scope scope = scopes.get(scopeId);
        return scope == null ? null : scope.conversationId;
    }

    // ── host copy store ──────────────────────────────────────────────────────────────────

    private Resource copyNative(Scope scope, String sendId, String attachmentId, Path source,
            String kind) {
        String copyId = sendId + "_" + attachmentId + "_" + UUID.randomUUID();
        Path dir = copyRoot.resolve(scope.scopeId).resolve(copyId);
        try {
            Path copy = "directory".equals(kind) ? copyTree(source, dir) : copyFile(source, dir);
            long size = sizeOf(copy);
            enforceCopyQuota(scope, size);
            return new Resource("res_" + UUID.randomUUID(), scope.scopeId,
                    source.getFileName().toString(), kind, "input", "read", 1, STATUS_READY,
                    "native", source.toString(), source.toString(), size, copy);
        } catch (IOException e) {
            deleteTree(dir);
            throw new IllegalArgumentException("Cannot copy the selected "
                    + kind + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            deleteTree(dir);
            throw e;
        }
    }

    private Resource copyUpload(Scope scope, String sendId, String attachmentId, String name,
            String kind, List<MultipartFile> uploads, List<String> paths) {
        String copyId = sendId + "_" + attachmentId + "_" + UUID.randomUUID();
        Path dir = copyRoot.resolve(scope.scopeId).resolve(copyId);
        try {
            if (uploads.size() > MAX_TREE_FILES) {
                throw new IllegalArgumentException("Directory contains too many files");
            }
            long total = 0;
            for (MultipartFile file : uploads) {
                if (file.getSize() > MAX_SINGLE_FILE_BYTES) {
                    throw new IllegalArgumentException("File exceeds 100 MB");
                }
                total += file.getSize();
            }
            if (total > MAX_TREE_BYTES) {
                throw new IllegalArgumentException("Directory exceeds 500 MB");
            }
            Path top;
            if ("directory".equals(kind)) {
                top = Files.createDirectories(dir.resolve(sanitize(name)));
                for (int i = 0; i < uploads.size(); i++) {
                    Path target = top.resolve(paths.get(i)).normalize();
                    if (!target.startsWith(top)) {
                        throw new IllegalArgumentException("Upload escapes its directory");
                    }
                    Files.createDirectories(target.getParent());
                    try (var in = uploads.get(i).getInputStream()) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } else {
                top = Files.createDirectories(dir).resolve(sanitize(name));
                try (var in = uploads.get(0).getInputStream()) {
                    Files.copy(in, top, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            long size = sizeOf(top);
            enforceCopyQuota(scope, size);
            return new Resource("res_" + UUID.randomUUID(), scope.scopeId, name, kind, "input",
                    "read", 1, STATUS_READY, "upload", "upload:" + UUID.randomUUID(), null, size, top);
        } catch (IOException e) {
            deleteTree(dir);
            throw new IllegalArgumentException("Cannot store the uploaded "
                    + kind + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            deleteTree(dir);
            throw e;
        }
    }

    /** Copies one regular file, confirming the size afterwards (§14-7's cheap torn-copy check). */
    private static Path copyFile(Path source, Path dir) throws IOException {
        if (Files.size(source) > MAX_SINGLE_FILE_BYTES) {
            throw new IllegalArgumentException("File exceeds 100 MB");
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(sanitize(source.getFileName().toString()));
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        if (Files.size(target) != Files.size(source)) {
            throw new IOException("The file changed while it was being copied — try again");
        }
        return target;
    }

    /** Copies a bounded directory tree, refusing symlinks and out-of-tree entries. */
    private static Path copyTree(Path source, Path dir) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(source)) {
            entries = paths.toList();
        }
        if (entries.size() > MAX_TREE_FILES + 1) {
            throw new IllegalArgumentException("Directory contains too many files");
        }
        long total = 0;
        for (Path entry : entries) {
            if (Files.isSymbolicLink(entry)) {
                throw new IllegalArgumentException("The folder contains a symbolic link");
            }
            if (Files.isRegularFile(entry)) {
                total += Files.size(entry);
                if (total > MAX_TREE_BYTES) {
                    throw new IllegalArgumentException("Directory exceeds 500 MB");
                }
            }
        }
        Path top = Files.createDirectories(dir.resolve(sanitize(source.getFileName().toString())));
        for (Path entry : entries) {
            Path relative = source.relativize(entry);
            Path target = top.resolve(relative).normalize();
            if (!target.startsWith(top)) throw new IllegalArgumentException("Entry escapes the copy root");
            if (Files.isDirectory(entry)) Files.createDirectories(target);
            else Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return top;
    }

    private void enforceCopyQuota(Scope scope, long addition) {
        long current = scope.resources.values().stream().mapToLong(Resource::size).sum();
        if (current + addition > MAX_SCOPE_COPY_BYTES) {
            throw new IllegalArgumentException("This conversation's resources exceed the copy quota — remove some first");
        }
    }

    /** Disables a superseded copy for new turns; deletes it once no lease still pins it (E11). */
    private void retireCopy(Scope scope, Resource resource) {
        deleteCopyUnlessPinned(scope, resource.copyPath());
    }

    private void reclaimUnpinnedRetiredCopies(Scope scope) {
        for (RetiredCopy retired : List.copyOf(scope.retiredCopies.values())) {
            boolean stillPinned = scope.leases.values().stream()
                    .anyMatch(lease -> lease.pinnedCopies().contains(retired.copyPath()));
            if (!stillPinned) {
                deleteTree(retired.copyPath());
                scope.retiredCopies.remove(retired.copyPath().toString());
            }
        }
    }

    private static long sizeOf(Path path) {
        try (Stream<Path> paths = Files.walk(path)) {
            return paths.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0L; }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String sanitize(String raw) {
        String name = raw == null ? "" : raw.trim();
        Path single = name.isEmpty() ? null : Path.of(name).getFileName();
        if (single == null || single.toString().isBlank() || single.toString().equals(".")) {
            return "file";
        }
        return single.toString();
    }

    private static void deleteTree(Path directory) {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | java.io.UncheckedIOException raced) {
            // deleted concurrently or locked — nothing references it after a restart sweep anyway
        }
    }

    // ── internals ───────────────────────────────────────────────────────────────────────

    static final String STATUS_READY = "ready";

    private void revokeQuietly(ActiveFileRef ref) {
        try {
            pluginFiles.revoke(ref.pluginId(), ref.ref().id());
        } catch (RuntimeException e) {
            log.debug("Could not revoke chat resource grant {}: {}", ref.ref().id(), e.toString());
        }
    }

    private Scope owned(String scopeId, Long userId) {
        Scope scope = scopeId == null ? null : scopes.get(scopeId);
        if (scope == null) {
            throw new IllegalArgumentException("Unknown or closed resource scope");
        }
        if (scope.userId != (userId == null ? 0L : userId)) {
            AUDIT.warn("scope ownership mismatch: scope={} owner={} caller={}",
                    scopeId, scope.userId, userId);
            throw new IllegalArgumentException("Resource scope belongs to another session");
        }
        return scope;
    }

    private static SendTransaction sendOf(Scope scope, String sendId) {
        SendTransaction tx = sendId == null ? null : scope.sends.get(sendId);
        if (tx == null) throw new IllegalArgumentException("Unknown send transaction");
        return tx;
    }

    private static IllegalArgumentException unknownResource() {
        return new IllegalArgumentException("Unknown, removed, or expired chat resource");
    }

    /** Closes scopes idle beyond the TTL and rolls back expired sends so nothing leaks. */
    private void sweepExpired(Instant now) {
        for (Scope scope : scopes.values()) {
            sweepExpiredSends(scope, now);
            Instant idleSince = scope.lastActiveAt == null ? scope.createdAt : scope.lastActiveAt;
            if (Duration.between(idleSince, now).compareTo(SCOPE_IDLE_TTL) > 0) {
                try {
                    closeScope(scope.scopeId, scope.userId);
                } catch (RuntimeException e) {
                    log.debug("Idle-scope sweep failed for {}: {}", scope.scopeId, e.toString());
                }
            }
        }
    }

    private void sweepExpiredSends(Scope scope, Instant now) {
        for (SendTransaction tx : scope.sends.values()) {
            boolean expired = Duration.between(tx.createdAt, now).compareTo(SEND_TTL) > 0;
            if (!expired) continue;
            switch (tx.state()) {
                // A committed send keeps its idempotence record; only its pending copies (none)
                // would need care — nothing to do.
                case SendTransaction.STATE_COMMITTED -> { }
                // Committing past the TTL with leases still alive is a live turn: never roll it
                // back while a lease pins its copies (E06 recovery keeps the accepted turn).
                case SendTransaction.STATE_COMMITTING -> {
                    boolean pinned = tx.rollback.keySet().stream()
                            .anyMatch(id -> scope.resources.containsKey(id)
                                    && scope.leases.values().stream().anyMatch(lease ->
                                            lease.pinnedCopies().contains(
                                                    scope.resources.get(id).copyPath())));
                    if (!pinned) failSend(scope, tx, "expired");
                }
                default -> failSend(scope, tx, "expired");
            }
        }
    }

    // ── model ───────────────────────────────────────────────────────────────────────────

    /**
     * One aggregated chat resource: display metadata plus the ONE host copy it owns. Plugin
     * {@code FileRef}s are derived per turn from the copy — they are never stored here.
     */
    public record Resource(String resourceId, String scopeId, String name, String kind,
            String purpose, String access, int revision, String status, String source,
            String sourceKey, String displayPath, long size, Path copyPath) {}

    public record ScopeSnapshot(List<Resource> resources, String outputTarget, Long conversationId) {}

    /** A chat execution's pin over the resource copies its turn was started with. */
    public record Lease(String leaseId, String scopeId, List<ActiveFileRef> refs,
            List<Path> pinnedCopies, Instant createdAt) {}

    /** One send transaction: prepared copies, their commit rollback journal, and the result. */
    public static final class SendTransaction {
        static final String STATE_PREPARING = "preparing";
        static final String STATE_PREPARED = "prepared";
        static final String STATE_COMMITTING = "committing";
        static final String STATE_COMMITTED = "committed";
        static final String STATE_FAILED = "failed";

        private final String sendId;
        private final String scopeId;
        private final String fingerprint;
        private final Instant createdAt;
        private final Map<String, Resource> pendingResources = new LinkedHashMap<>();
        /** resourceId → the revision it replaced (null for fresh resources) — the rollback journal. */
        private final Map<String, Resource> rollback = new LinkedHashMap<>();
        private String state = STATE_PREPARING;
        private String error;
        private Map<String, Object> result;

        SendTransaction(String sendId, String scopeId, String fingerprint, Instant createdAt) {
            this.sendId = sendId;
            this.scopeId = scopeId;
            this.fingerprint = fingerprint;
            this.createdAt = createdAt;
        }

        void attach(String attachmentId, Resource resource) {
            pendingResources.put(attachmentId, resource);
        }

        boolean isReplayableAsPrepared() {
            return state.equals(STATE_PREPARED) || state.equals(STATE_FAILED);
        }

        String state() { return state; }
        String error() { return error; }
        String fingerprint() { return fingerprint; }

        SendStatus status() {
            List<SendStatus.Entry> entries = new ArrayList<>();
            pendingResources.forEach((id, resource) ->
                    entries.add(new SendStatus.Entry(id, resource.name(), resource.kind(), "prepared", null)));
            return new SendStatus(sendId, state, entries, error,
                    STATE_COMMITTED.equals(state) ? result : null);
        }
    }

    /** The client-facing state of one send transaction (status query / prepare response). */
    public record SendStatus(String sendId, String state, List<Entry> attachments, String error,
            Map<String, Object> committedResult) {
        public record Entry(String attachmentId, String name, String kind, String status, String error) {}
    }

    private record RetiredCopy(Path copyPath) {}

    static final class Scope {
        final String scopeId;
        final long userId;
        final Instant createdAt;
        volatile Instant lastActiveAt;
        volatile Long conversationId;
        volatile String outputTarget;
        volatile boolean closed;
        final Map<String, Resource> resources = new LinkedHashMap<>();
        final Map<String, RetiredCopy> retiredCopies = new LinkedHashMap<>();
        final Map<String, SendTransaction> sends = new LinkedHashMap<>();
        final Map<String, Lease> leases = new LinkedHashMap<>();

        Scope(String scopeId, long userId, Instant createdAt) {
            this.scopeId = scopeId;
            this.userId = userId;
            this.createdAt = createdAt;
            this.lastActiveAt = createdAt;
        }
    }
}
