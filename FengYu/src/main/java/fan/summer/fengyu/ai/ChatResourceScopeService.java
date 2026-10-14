package fan.summer.fengyu.ai;

import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import fan.summer.fengyu.runtime.ChatResourceLimits;
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
 * the RC task doc (revision 2): <b>selection only writes a draft description; resources are
 * captured when a send is prepared; per-turn {@code FileRef}s are derived at the execution
 * boundary and released when the turn ends.</b>
 *
 * <p>Ownership model:
 * <ul>
 *   <li>A {@link Scope} is created by the backend and bound to the requesting user. The optional
 *       {@code conversationId} links once the draft is persisted.</li>
 *   <li>Picker selections and uploads NEVER touch this service — they live as frontend draft
 *       attachments until the user sends (E01/E02). A send {@link SendTransaction} (keyed by the
 *       client's {@code sendId}) captures each attachment exactly once (E05); a conflicting
 *       replay is rejected (E07); an aborted or expired transaction reclaims its exclusive
 *       copies (E04/E12).</li>
 *   <li>Every committed {@link Resource} owns ONE stable reference (§14-3, desktop calibration
 *       2026-10): an upload or a sensitive-path pick owns a host COPY under the copy store,
 *       while an ordinary native pick owns a LIVE reference to the user's original file plus a
 *       recorded fingerprint (size + mtime for files, bounded walk totals for trees). The old
 *       data-level snapshot became a turn-boundary check: {@link #acquireLease} re-validates
 *       every live fingerprint and fails the turn loudly when the user changed the file since
 *       attaching it. A chat turn's lease derives read grants over the reference for every
 *       eligible plugin; {@link #releaseLease} revokes them but keeps the reference, so the next
 *       turn can say "continue" and re-derive access (E09/E10). Removing or refreshing a
 *       resource retires the superseded copy — physically deleted only once no lease still pins
 *       it (E11); a live reference is only ever forgotten, never deleted.</li>
 *   <li>The output location is host-save authorization only: it never mints a worker grant
 *       (invariant 5.3-8).</li>
 *   <li>Scope manifests persist under the copy root (desktop calibration D2): a restart reloads
 *       each scope, re-validates its references, and keeps anything it cannot re-arm in the
 *       snapshot as {@code unavailable} — visible and removable, never leasable (E13 revised).
 *       In-flight send transactions are process state and die with the process; the client
 *       retries with a fresh sendId. Grant tables stay in memory: no plugin holds any grant
 *       across a restart.</li>
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
    static final int MAX_TREE_FILES = 2_000;
    /** Caps come from {@link ChatResourceLimits} (fengyu.chat-resources.*) — see that class. */
    private final ChatResourceLimits limits;
    /** Per-scope manifest file name, inside the scope's own directory under the copy root. */
    static final String SCOPE_MANIFEST = "scope.json";

    private final ChatFileGrantService chatFiles;
    private final PluginFileGrantService pluginFiles;
    private final Path copyRoot;
    private final Map<String, Scope> scopes = new ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper manifestJson =
            com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature
                            .FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();
    /** Test seam: the sweeps and TTLs read time from here. */
    java.util.function.Supplier<Instant> clock = Instant::now;
    /**
     * Test seam: which native paths must fall back to a bounded snapshot copy. Production
     * default = the credential-directory matcher (the paths a live read grant must never
     * expose on a ro-bind platform); tests substitute a targeted predicate.
     */
    java.util.function.Predicate<Path> snapshotEligible = PluginFileGrantService::isSensitiveHomePath;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatResourceScopeService(ChatFileGrantService chatFiles, PluginFileGrantService pluginFiles,
            ChatResourceLimits limits) {
        this(chatFiles, pluginFiles, RuntimePaths.root().resolve("chat-resources"), limits);
    }

    public ChatResourceScopeService(ChatFileGrantService chatFiles, PluginFileGrantService pluginFiles,
            Path copyRoot) {
        this(chatFiles, pluginFiles, copyRoot, ChatResourceLimits.defaults());
    }

    public ChatResourceScopeService(ChatFileGrantService chatFiles, PluginFileGrantService pluginFiles,
            Path copyRoot, ChatResourceLimits limits) {
        this.chatFiles = chatFiles;
        this.pluginFiles = pluginFiles;
        this.limits = limits;
        limits.validate();
        this.copyRoot = copyRoot.toAbsolutePath().normalize();
        // D2: a desktop restart re-arms persisted scopes instead of wiping the store; anything
        // without a readable manifest is a crashed run's orphan and is reclaimed (below).
        loadPersistedScopes();
    }

    // ── scope lifecycle ─────────────────────────────────────────────────────────────────

    /** Creates a scope owned by {@code userId}; returns its server-minted id. */
    public synchronized String createScope(Long userId) {
        sweepExpired(clock.get());
        String scopeId = "cs_" + UUID.randomUUID();
        Scope scope = new Scope(scopeId, userId == null ? 0L : userId, clock.get());
        scopes.put(scopeId, scope);
        persist(scope); // D2: the manifest dir exists from birth, live-only scopes included
        return scopeId;
    }

    /** Idempotent close: revokes every lease grant, reclaims every copy, forgets the scope. */
    public synchronized void closeScope(String scopeId, Long userId) {
        Scope scope = scopeId == null ? null : scopes.get(scopeId);
        if (scope == null) return; // already closed (or never existed) — idempotent no-op
        if (scope.userId != (userId == null ? 0L : userId)) {
            throw new IllegalArgumentException("Resource scope belongs to another session");
        }
        // D2: the manifest dies FIRST — if the process crashes (or a locked entry swallows a
        // delete) after this point, the leftover directory is just another boot-time orphan;
        // a surviving manifest would RESURRECT a scope the user explicitly closed.
        deleteTree(copyRoot.resolve(scope.scopeId).resolve(SCOPE_MANIFEST));
        scopes.remove(scope.scopeId);
        for (Lease lease : scope.leases.values()) {
            for (ActiveFileRef ref : lease.refs()) revokeQuietly(ref);
        }
        for (Resource resource : scope.resources.values()) {
            if (resource.ownedCopy()) deleteTree(resource.copyPath());
        }
        for (RetiredCopy retired : scope.retiredCopies.values()) {
            deleteTree(retired.copyPath());
        }
        for (SendTransaction tx : scope.sends.values()) {
            for (Resource pending : tx.pendingResources.values()) {
                if (pending.ownedCopy()) deleteTree(pending.copyPath());
            }
        }
        // What remains of the directory (empty manifest slot, unclaimed copies) is fair game
        // for the boot-time orphan sweep.
        deleteTree(copyRoot.resolve(scope.scopeId));
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
        persist(scope);
    }

    public synchronized void setOutputTarget(String scopeId, Long userId, String rawPath) {
        Scope scope = owned(scopeId, userId);
        if (rawPath == null || rawPath.isBlank()) {
            scope.outputTarget = null;
            persist(scope);
            return;
        }
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Output location is not an existing directory");
        }
        AUDIT.info("chat output target: scope={} path={}", scopeId, path);
        // Host-save authorization only — deliberately NO plugin grant is minted here.
        scope.outputTarget = path.toString();
        persist(scope);
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
        // Sweep first: a send prepared long before this request must not enter COMMITTING
        // past its TTL only to be rolled back by a concurrent sweeper mid-commit — the
        // sweep either fails it now (clean 400 below) or confirms it is still in time.
        sweepExpiredSends(scope, clock.get());
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
            persist(scope);
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
        // Only a COMMITTING send may finish: one swept to FAILED between begin and finish
        // (a 30-minute stall inside one request) must not be re-marked COMMITTED over an
        // empty rollback journal.
        if (!SendTransaction.STATE_COMMITTING.equals(tx.state())) {
            throw new IllegalArgumentException("Send " + sendId + " is " + tx.state()
                    + " — cannot finish");
        }
        for (Resource replaced : tx.rollback.values()) {
            if (replaced != null) retireCopy(scope, replaced);
        }
        tx.result = Map.copyOf(response);
        tx.state = SendTransaction.STATE_COMMITTED;
        persist(scope);
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
            if (introduced != null) deleteCopyUnlessPinned(scope, introduced);
            Resource restored = rollback.getValue();
            if (restored != null) scope.resources.put(restored.resourceId(), restored);
        }
        tx.rollback.clear();
        for (Resource pending : tx.pendingResources.values()) deleteCopyUnlessPinned(scope, pending);
        tx.pendingResources.clear();
        tx.state = SendTransaction.STATE_FAILED;
        tx.error = reason;
        persist(scope);
    }

    /** Deletes a resource's copy now, or defers while a live lease still pins it (E11). A live
     *  reference owns nothing on disk and is simply forgotten — deleting the user's original is
     *  never this registry's call. */
    private void deleteCopyUnlessPinned(Scope scope, Resource resource) {
        if (!resource.ownedCopy()) return;
        Path copy = resource.copyPath();
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
                pending.displayPath(), pending.size(), pending.copyPath(), pending.ownedCopy(),
                pending.fingerprint());
        if (tx != null) tx.rollback.put(committed.resourceId(), replaced);
        scope.resources.put(committed.resourceId(), committed);
        return committed;
    }

    private void prepareNativeInto(Scope scope, SendTransaction tx, NativeAttachment attachment) {
        if (tx.pendingResources.containsKey(attachment.attachmentId())) return;
        if (!List.of("file", "directory").contains(attachment.kind())) {
            throw new IllegalArgumentException("Invalid attachment kind");
        }
        // Resolve through symlinks BEFORE any decision: the sensitive-path matcher and the
        // recorded reference must both see the REAL target — an alias planted into a picker'd
        // directory must not smuggle a credential path past the snapshot policy (review finding).
        Path source = resolveExisting(attachment.path());
        if (source == null) {
            throw new IllegalArgumentException("The selected file is no longer available: "
                    + attachment.path());
        }
        if ("directory".equals(attachment.kind()) != Files.isDirectory(source)) {
            throw new IllegalArgumentException("Selected path kind does not match: " + source);
        }
        Resource resource = nativeResource(scope, tx.sendId, attachment.attachmentId(), source,
                attachment.kind());
        tx.attach(attachment.attachmentId(), resource);
    }

    /** The real path behind a client-supplied selection, or null when it no longer exists. */
    private static Path resolveExisting(String rawPath) {
        try {
            return Path.of(rawPath).toRealPath();
        } catch (IOException e) {
            return null;
        }
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
            Resource resource = nativeResource(scope, sendId == null ? "tx_text" : sendId,
                    "text_" + UUID.randomUUID(), path, directory ? "directory" : "file");
            try {
                SendTransaction tx = sendId == null ? null : ephemeralTx(scope, sendId);
                adopted.add(commitInto(scope, tx, resource));
            } catch (RuntimeException e) {
                if (resource.ownedCopy()) deleteTree(resource.copyPath());
                for (Resource minted : adopted) removeResource(scopeId, userId, minted.resourceId());
                throw e;
            }
        }
        if (!adopted.isEmpty()) persist(scope);
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
     * Re-syncs a native read-only resource with its original file. A LIVE reference (the
     * desktop default) re-records its fingerprint — the file itself was always the truth, so a
     * refresh re-arms the turn-boundary check at the CURRENT content and bumps the revision. A
     * sensitive-path snapshot re-copies and swaps atomically; the previous revision's copy is
     * retired (kept while a lease still pins it) so a live turn keeps reading the version it
     * started with (A10; failure keeps the old copy).
     */
    public synchronized Resource refresh(String scopeId, Long userId, String resourceId) {
        Scope scope = owned(scopeId, userId);
        Resource resource = scope.resources.get(resourceId);
        if (resource == null) throw unknownResource();
        if (!"input".equals(resource.purpose()) || !"native".equals(resource.source())
                || !"file".equals(resource.kind())) {
            throw new IllegalArgumentException("Only native read-only files can be refreshed");
        }
        // Same symlink discipline as prepare: re-arming follows the real file, not an alias.
        Path source = resolveExisting(resource.sourceKey());
        if (source == null || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("The original file is no longer available");
        }
        Resource swapped;
        if (resource.ownedCopy()) {
            Resource updated = copyNative(scope, "tx_refresh", resource.resourceId(), source, "file");
            swapped = new Resource(resource.resourceId(), scope.scopeId, updated.name(),
                    resource.kind(), resource.purpose(), resource.access(), resource.revision() + 1,
                    STATUS_READY, resource.source(), resource.sourceKey(), resource.displayPath(),
                    updated.size(), updated.copyPath(), true, null);
        } else {
            try {
                long size = Files.size(source);
                if (size > limits.getMaxSingleFileBytes()) {
                    throw new IllegalArgumentException("File exceeds "
                            + ChatResourceLimits.human(limits.getMaxSingleFileBytes()));
                }
                enforceCopyQuota(scope, size - resource.size());
                swapped = new Resource(resource.resourceId(), scope.scopeId, resource.name(),
                        resource.kind(), resource.purpose(), resource.access(),
                        resource.revision() + 1, STATUS_READY, resource.source(),
                        resource.sourceKey(), resource.displayPath(), size, source, false,
                        liveFingerprintOf(source, "file"));
            } catch (IOException e) {
                throw new IllegalArgumentException("The original file cannot be read: "
                        + e.getMessage(), e);
            }
        }
        scope.resources.put(resource.resourceId(), swapped);
        retireCopy(scope, resource);
        persist(scope);
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
        persist(scope);
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
        // D1 turn-boundary gate: a live reference must still exist and still match the
        // fingerprint recorded when it was attached — before ANY grant is derived.
        for (Resource resource : resolved.values()) {
            if (!resource.ownedCopy()) validateLiveReference(resource);
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
        // Pinning covers EVERY leased reference, owned copies and live paths alike: the send-TTL
        // sweep's COMMITTING protection asks exactly this list "is a turn still executing over
        // this send's resources?" — filtering live paths out let the sweep roll a live turn back
        // mid-stream (review finding). The list's other consumers only ever compare it against
        // owned-copy paths under the copy root, which a live user path can never equal.
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

    /**
     * Dispatches one native attachment to its owning form (D1): sensitive paths keep the
     * bounded snapshot copy; everything else becomes a zero-copy live reference with a
     * turn-boundary fingerprint.
     */
    private Resource nativeResource(Scope scope, String sendId, String attachmentId, Path source,
            String kind) {
        return snapshotEligible.test(source)
                ? copyNative(scope, sendId, attachmentId, source, kind)
                : referenceNative(scope, source, kind);
    }

    /**
     * Adopts a native path as a LIVE reference — no copy, no disk duplication. The recorded
     * fingerprint (size + mtime for files; bounded walk totals for trees) is what turns the old
     * data-level snapshot into a turn-boundary check: content the user changed since attaching
     * fails the next lease loudly instead of silently diverging from what was described.
     */
    private Resource referenceNative(Scope scope, Path source, String kind) {
        long size;
        String fingerprint;
        try {
            if ("directory".equals(kind)) {
                size = scanTree(source).totalBytes();
            } else {
                size = Files.size(source);
                if (size > limits.getMaxSingleFileBytes()) {
                    throw new IllegalArgumentException("File exceeds "
                            + ChatResourceLimits.human(limits.getMaxSingleFileBytes()));
                }
            }
            fingerprint = liveFingerprintOf(source, kind);
        } catch (IOException e) {
            throw new IllegalArgumentException("The selected " + kind
                    + " cannot be referenced: " + e.getMessage(), e);
        }
        enforceCopyQuota(scope, size);
        return new Resource("res_" + UUID.randomUUID(), scope.scopeId,
                source.getFileName().toString(), kind, "input", "read", 1, STATUS_READY,
                "native", source.toString(), source.toString(), size, source, false, fingerprint);
    }

    /**
     * The fingerprint formula shared by record-time ({@link #referenceNative}) and every
     * turn-boundary re-validation ({@link #acquireLease}) — a mismatch in either direction is
     * the "the attachment changed" signal. Directory trees fingerprint as
     * file-count:total-bytes:mtime-sum — content-preserving renames deliberately pass, while
     * any edit, add, or removal trips at least one term (a same-size swap that also preserves
     * every mtime is outside a non-content fingerprint's power, by design); the walk stays
     * bounded.
     */
    private static String liveFingerprintOf(Path path, String kind) throws IOException {
        if (!"directory".equals(kind)) {
            return Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis();
        }
        long total = 0;
        int files = 0;
        long mtimeSum = 0;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path entry : paths.limit(MAX_TREE_FILES + 2L).toList()) {
                if (Files.isRegularFile(entry)) {
                    files++;
                    total += Files.size(entry);
                    mtimeSum = Math.addExact(mtimeSum,
                            Files.getLastModifiedTime(entry).toMillis() % 1_000_000_007L);
                }
            }
        }
        return files + ":" + total + ":" + mtimeSum;
    }

    /** Turn-boundary consistency gate for live references (see {@link #acquireLease}). */
    private static void validateLiveReference(Resource resource) {
        Path path = resource.copyPath();
        if (!Files.exists(path) || ("directory".equals(resource.kind()) != Files.isDirectory(path))) {
            throw new IllegalArgumentException("The attached " + resource.kind()
                    + " is no longer available — re-attach it");
        }
        String current;
        try {
            current = liveFingerprintOf(path, resource.kind());
        } catch (IOException e) {
            throw new IllegalArgumentException("The attached " + resource.kind()
                    + " cannot be read — re-attach it");
        }
        if (!current.equals(resource.fingerprint())) {
            throw new IllegalArgumentException("The attached " + resource.kind()
                    + " changed since it was added — re-attach it");
        }
    }

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
                    "native", source.toString(), source.toString(), size, copy, true, null);
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
                if (file.getSize() > limits.getMaxSingleFileBytes()) {
                    throw new IllegalArgumentException("File exceeds "
                            + ChatResourceLimits.human(limits.getMaxSingleFileBytes()));
                }
                total += file.getSize();
            }
            if (total > limits.getMaxTreeBytes()) {
                throw new IllegalArgumentException("Directory exceeds "
                        + ChatResourceLimits.human(limits.getMaxTreeBytes()));
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
                    "read", 1, STATUS_READY, "upload", "upload:" + UUID.randomUUID(), null, size,
                    top, true, null);
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
    private Path copyFile(Path source, Path dir) throws IOException {
        if (Files.size(source) > limits.getMaxSingleFileBytes()) {
            throw new IllegalArgumentException("File exceeds "
                    + ChatResourceLimits.human(limits.getMaxSingleFileBytes()));
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(sanitize(source.getFileName().toString()));
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        if (Files.size(target) != Files.size(source)) {
            throw new IOException("The file changed while it was being copied — try again");
        }
        return target;
    }

    /** Copies a bounded directory tree, refusing symlinks and out-of-tree entries. The
     *  walk is capped by {@code limit} BEFORE materializing — a runaway directory (millions
     *  of entries) is rejected after reading at most one entry past the cap, never after
     *  buffering the whole tree in memory. */
    private Path copyTree(Path source, Path dir) throws IOException {
        List<Path> entries = scanTree(source).entries();
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

    /** One bounded scan of a directory tree: the walk-capped entries plus cap-enforced totals. */
    private TreeScan scanTree(Path source) throws IOException {
        List<Path> entries;
        try (Stream<Path> paths = Files.walk(source)) {
            // limit() short-circuits the lazy walk; +2 = the root itself plus the one
            // entry past the cap that proves the bound was exceeded.
            entries = paths.limit(MAX_TREE_FILES + 2L).toList();
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
                long size = Files.size(entry);
                if (size > limits.getMaxSingleFileBytes()) {
                    throw new IllegalArgumentException("Directory contains a file larger than "
                            + ChatResourceLimits.human(limits.getMaxSingleFileBytes()));
                }
                total += size;
                if (total > limits.getMaxTreeBytes()) {
                    throw new IllegalArgumentException("Directory exceeds "
                            + ChatResourceLimits.human(limits.getMaxTreeBytes()));
                }
            }
        }
        return new TreeScan(entries, total);
    }

    private record TreeScan(List<Path> entries, long totalBytes) {}

    private void enforceCopyQuota(Scope scope, long addition) {
        long current = scope.resources.values().stream().mapToLong(Resource::size).sum();
        if (current + addition > limits.getMaxScopeBytes()) {
            throw new IllegalArgumentException("This conversation's resources exceed the copy quota — remove some first");
        }
    }

    /** Disables a superseded copy for new turns; deletes it once no lease still pins it (E11). */
    private void retireCopy(Scope scope, Resource resource) {
        deleteCopyUnlessPinned(scope, resource);
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

    // ── persistence (desktop calibration D2) ─────────────────────────────────────────────

    /** The on-disk shape of one scope's manifest ({@code <copyRoot>/<scopeId>/scope.json}). */
    record StoredScope(String scopeId, long userId, String createdAt, String lastActiveAt,
            Long conversationId, String outputTarget, List<StoredResource> resources) {}

    record StoredResource(String resourceId, String name, String kind, String purpose,
            String access, int revision, String status, String source, String sourceKey,
            String displayPath, long size, String copyPath, boolean ownedCopy, String fingerprint) {}

    /**
     * Best-effort manifest write: the in-memory registry stays authoritative, and a failed
     * write only costs the NEXT restart its re-authorization (the copies on disk are intact).
     * Writes land atomically (temp file + move) so a crash mid-write never corrupts a manifest.
     */
    private void persist(Scope scope) {
        Path manifest = copyRoot.resolve(scope.scopeId).resolve(SCOPE_MANIFEST);
        try {
            Files.createDirectories(manifest.getParent());
            List<StoredResource> resources = scope.resources.values().stream()
                    .map(r -> new StoredResource(r.resourceId(), r.name(), r.kind(), r.purpose(),
                            r.access(), r.revision(), r.status(), r.source(), r.sourceKey(),
                            r.displayPath(), r.size(), r.copyPath().toString(), r.ownedCopy(),
                            r.fingerprint()))
                    .toList();
            StoredScope stored = new StoredScope(scope.scopeId, scope.userId,
                    scope.createdAt.toString(), scope.lastActiveAt.toString(), scope.conversationId,
                    scope.outputTarget, resources);
            Path tmp = manifest.resolveSibling(SCOPE_MANIFEST + ".tmp-" + UUID.randomUUID());
            Files.writeString(tmp, manifestJson.writeValueAsString(stored));
            try {
                Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException plainMove) {
                Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Could not persist chat-resource manifest for {}: {}", scope.scopeId, e.toString());
        }
    }

    /**
     * Reloads persisted scopes at boot (D2): an owned copy that survived the restart resumes
     * as-is; a live reference whose fingerprint still matches re-arms; anything else is kept in
     * the snapshot as {@code unavailable} — visible and removable, never leasable. Directories
     * without a readable manifest are a crashed run's orphans and are reclaimed. In-flight send
     * transactions are process state and die with the process — the client retries with a
     * fresh sendId, and no plugin grant ever survives the restart (the grant table is memory).
     */
    private void loadPersistedScopes() {
        if (!Files.isDirectory(copyRoot)) return;
        try (var dirs = Files.list(copyRoot)) {
            for (Path dir : dirs.toList()) {
                if (!Files.isDirectory(dir)) continue;
                Path manifest = dir.resolve(SCOPE_MANIFEST);
                if (!Files.isRegularFile(manifest)) {
                    deleteTree(dir);
                    continue;
                }
                try {
                    StoredScope stored = readManifestWithRetry(manifest);
                    Scope scope = restoreScope(stored, dir);
                    if (scope == null) {
                        deleteTree(dir);
                        continue;
                    }
                    reclaimUnvouchedEntries(scope, dir);
                    scopes.put(scope.scopeId, scope);
                } catch (IOException | RuntimeException e) {
                    log.warn("Reclaiming unreadable chat-resource manifest {}: {}", manifest, e.toString());
                    deleteTree(dir);
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan persisted chat-resource scopes under {}: {}", copyRoot, e.toString());
        }
        // Scopes that were already idle-past-TTL when the process died should not linger until
        // the next createScope triggers the sweep.
        sweepExpired(clock.get());
    }

    /** One bounded retry: a transient read failure (disk hiccup) must not cost the scope's
     *  copies — only a persistently unreadable manifest is reclaimed by the caller. */
    private StoredScope readManifestWithRetry(Path manifest) throws IOException {
        IOException failure;
        try {
            return manifestJson.readValue(Files.readString(manifest), StoredScope.class);
        } catch (IOException first) {
            failure = first;
        }
        try {
            return manifestJson.readValue(Files.readString(manifest), StoredScope.class);
        } catch (IOException second) {
            failure.addSuppressed(second);
            throw failure;
        }
    }

    /** Rebuilds one scope from its manifest; null when the manifest does not match its directory. */
    private Scope restoreScope(StoredScope stored, Path dir) {
        if (stored.scopeId() == null || !dir.getFileName().toString().equals(stored.scopeId())) {
            return null;
        }
        try {
            Scope scope = new Scope(stored.scopeId(), stored.userId(), Instant.parse(stored.createdAt()));
            scope.lastActiveAt = Instant.parse(stored.lastActiveAt());
            scope.conversationId = stored.conversationId();
            scope.outputTarget = stored.outputTarget();
            for (StoredResource r : stored.resources() == null ? List.<StoredResource>of() : stored.resources()) {
                scope.resources.put(r.resourceId(), restoreResource(stored.scopeId(), r, dir));
            }
            return scope;
        } catch (RuntimeException e) {
            log.warn("Dropping malformed chat-resource manifest {}: {}", dir, e.toString());
            return null;
        }
    }

    /** Re-validates one persisted resource: ready only when its reference still holds. */
    private Resource restoreResource(String scopeId, StoredResource r, Path scopeDir) {
        Path copyPath = Path.of(r.copyPath());
        String status = STATUS_READY;
        if (r.ownedCopy()) {
            // A manifest is on-disk input: its copyPath is only trusted for DELETION when it is
            // contained by the scope's own directory. A crafted or corrupted entry naming an
            // outside path is neutered to a tombstone inside the scope dir (deleteTree of a
            // non-existent path is a no-op) and shows up as unavailable (review finding).
            if (!copyPath.toAbsolutePath().normalize()
                    .startsWith(scopeDir.toAbsolutePath().normalize())) {
                log.warn("Persisted resource {} names a copy outside its scope directory — neutered",
                        r.resourceId());
                copyPath = scopeDir.resolve("violating-" + r.resourceId());
                status = STATUS_UNAVAILABLE;
            } else if (!Files.exists(copyPath)) {
                status = STATUS_UNAVAILABLE;
            }
        } else {
            try {
                if (!Files.exists(copyPath)
                        || !Objects.equals(liveFingerprintOf(copyPath, r.kind()), r.fingerprint())) {
                    status = STATUS_UNAVAILABLE;
                }
            } catch (IOException e) {
                status = STATUS_UNAVAILABLE;
            }
        }
        return new Resource(r.resourceId(), scopeId, r.name(), r.kind(), r.purpose(), r.access(),
                r.revision(), status, r.source(), r.sourceKey(), r.displayPath(), r.size(),
                copyPath, r.ownedCopy(), r.fingerprint());
    }

    /**
     * Deletes entries inside a restored scope's directory that no committed resource vouches
     * for: an in-flight send's pending copies (sends are process state and never persisted)
     * and any retired copy the process died before reclaiming. The manifest file itself and
     * the committed copies' ancestor directories are the only survivors.
     */
    private void reclaimUnvouchedEntries(Scope scope, Path dir) {
        try (var entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.equals(SCOPE_MANIFEST) || name.startsWith(SCOPE_MANIFEST + ".tmp-")) {
                    continue;
                }
                boolean vouched = scope.resources.values().stream()
                        .filter(Resource::ownedCopy)
                        .anyMatch(r -> r.copyPath().toAbsolutePath().normalize()
                                .startsWith(entry.toAbsolutePath().normalize()));
                if (!vouched) deleteTree(entry);
            }
        } catch (IOException e) {
            log.warn("Could not reclaim unvouched entries under {}: {}", dir, e.toString());
        }
    }

    // ── internals ───────────────────────────────────────────────────────────────────────

    static final String STATUS_READY = "ready";
    static final String STATUS_UNAVAILABLE = "unavailable";

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
     * One aggregated chat resource: display metadata plus the ONE stable reference it owns —
     * a host copy ({@code ownedCopy=true}, deleted by this registry) or a live path guarded by
     * {@code fingerprint} (never deleted here). Plugin {@code FileRef}s are derived per turn
     * from the reference — they are never stored here.
     */
    public record Resource(String resourceId, String scopeId, String name, String kind,
            String purpose, String access, int revision, String status, String source,
            String sourceKey, String displayPath, long size, Path copyPath, boolean ownedCopy,
            String fingerprint) {}

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
