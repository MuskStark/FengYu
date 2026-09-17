package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.ChatArtifactStore;
import fan.summer.fengyu.ai.ChatResourceScopeService;
import fan.summer.fengyu.security.SecurityContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conversation-scoped chat resources: one aggregated record per user selection (never one chip
 * per plugin), a host-save output location, the send-transaction surface, and the
 * generated-artifact save closure. The scoped surface is chat-only — Flow runs and their panels
 * keep the run-owned legacy endpoints.
 *
 * <p>Per the RC task doc (revision 2), the picker/upload endpoints are GONE from selection time:
 * a selection is a frontend draft attachment until the user sends, and only the send transaction
 * ({@code POST /{scopeId}/sends}) creates host copies (E01/E02). Every scope operation validates
 * that the scope belongs to the calling user; resource resolution for chat turns happens
 * server-side in {@link AiController} (the frontend never holds authorization material beyond
 * opaque ids).
 */
@RestController
@RequestMapping("/api/ai/chat-resources")
public class ChatResourceController {

    private final ChatResourceScopeService scopes;
    private final ChatArtifactStore artifacts;
    private final SecurityContext securityContext;

    public ChatResourceController(ChatResourceScopeService scopes, ChatArtifactStore artifacts,
            SecurityContext securityContext) {
        this.scopes = scopes;
        this.artifacts = artifacts;
        this.securityContext = securityContext;
    }

    private long userId() {
        Long id = securityContext == null ? null : securityContext.currentUserId();
        return id == null ? 0L : id;
    }

    // ── scopes ──────────────────────────────────────────────────────────────────────────

    /** Registers a scope owned by the caller; it may back a draft that is not persisted yet. */
    @PostMapping("/scopes")
    public Map<String, Object> createScope() {
        return Map.of("scopeId", scopes.createScope(userId()));
    }

    /** The scope's aggregated resources and host-save output location. */
    @GetMapping("/{scopeId}")
    public Map<String, Object> snapshot(@PathVariable String scopeId) {
        ChatResourceScopeService.ScopeSnapshot snapshot = scopes.snapshot(scopeId, userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resources", snapshot.resources().stream().map(ChatResourceController::resourceDto).toList());
        out.put("outputTarget", snapshot.outputTarget());
        out.put("conversationId", snapshot.conversationId());
        return out;
    }

    /** Late-binds the persisted conversation id (first save happens after the first turn). */
    @PostMapping("/{scopeId}/conversation")
    public Map<String, Object> bindConversation(@PathVariable String scopeId,
            @RequestBody ConversationBinding body) {
        scopes.bindConversation(scopeId, userId(), body.conversationId());
        artifacts.bindConversation(scopeId, body.conversationId());
        return Map.of("ok", true);
    }

    /** Closes the scope: revokes every grant and purges its unsaved artifacts (idempotent). */
    @DeleteMapping("/{scopeId}")
    public void close(@PathVariable String scopeId) {
        // Ownership first: closeScope validates the caller before anything is destroyed, so a
        // foreign session can never purge another scope's pending artifacts.
        scopes.closeScope(scopeId, userId());
        artifacts.purgeScope(scopeId);
    }

    /** Sets (or clears, with a blank path) the host-save output location — never a worker grant. */
    @PostMapping("/{scopeId}/output")
    public Map<String, Object> setOutput(@PathVariable String scopeId,
            @RequestBody OutputTargetRequest body) {
        scopes.setOutputTarget(scopeId, userId(), body.path());
        return Map.of("outputTarget", scopes.outputTarget(scopeId));
    }

    // ── send transactions (§15) ─────────────────────────────────────────────────────────

    /**
     * Prepares a send: every desktop-native draft attachment is copied into the host store NOW
     * (send-time content truth) and stays transaction-owned until the chat turn commits it.
     * Selection itself never reaches this endpoint — E01's zero-grant window is the picker.
     */
    @PostMapping("/{scopeId}/sends")
    public Map<String, Object> prepareSend(@PathVariable String scopeId,
            @RequestBody PrepareSendRequest body) {
        List<ChatResourceScopeService.NativeAttachment> natives = body.attachments() == null
                ? List.of() : body.attachments().stream()
                        .map(a -> new ChatResourceScopeService.NativeAttachment(
                                a.attachmentId(), a.path(), a.kind()))
                        .toList();
        return sendDto(scopes.prepareSend(scopeId, userId(), body.sendId(), natives));
    }

    /** Adds one browser upload to a prepared send (the web analog of a native attachment). */
    @PostMapping("/{scopeId}/sends/{sendId}/uploads")
    public Map<String, Object> uploadToSend(@PathVariable String scopeId, @PathVariable String sendId,
            @RequestPart("file") MultipartFile file, @RequestParam("attachmentId") String attachmentId) {
        return sendDto(scopes.addUploadToSend(scopeId, userId(), sendId, attachmentId, file));
    }

    /** Adds one browser directory upload to a prepared send. */
    @PostMapping("/{scopeId}/sends/{sendId}/upload-directories")
    public Map<String, Object> uploadDirectoryToSend(@PathVariable String scopeId,
            @PathVariable String sendId, @RequestPart("files") List<MultipartFile> uploads,
            @RequestParam("paths") List<String> paths,
            @RequestParam("attachmentId") String attachmentId) {
        return sendDto(scopes.addUploadDirectoryToSend(scopeId, userId(), sendId,
                attachmentId, uploads, paths));
    }

    /** Send status — the recovery entry point after a timeout or lost response (E06). */
    @GetMapping("/{scopeId}/sends/{sendId}")
    public Map<String, Object> sendStatus(@PathVariable String scopeId, @PathVariable String sendId) {
        return sendDto(scopes.sendStatus(scopeId, userId(), sendId));
    }

    /** Aborts an uncommitted send: its exclusive copies are reclaimed (never a committed one). */
    @DeleteMapping("/{scopeId}/sends/{sendId}")
    public void abortSend(@PathVariable String scopeId, @PathVariable String sendId) {
        scopes.abortSend(scopeId, userId(), sendId);
    }

    // ── committed resources ─────────────────────────────────────────────────────────────

    /** Re-snapshots a native read-only file; the previous revision serves live turns. */
    @PostMapping("/{scopeId}/resources/{resourceId}/refresh")
    public Map<String, Object> refresh(@PathVariable String scopeId, @PathVariable String resourceId) {
        return resourceDto(scopes.refresh(scopeId, userId(), resourceId));
    }

    /** Idempotent removal: new turns stop resolving it, pinned turns drain safely. */
    @DeleteMapping("/{scopeId}/resources/{resourceId}")
    public void remove(@PathVariable String scopeId, @PathVariable String resourceId) {
        scopes.removeResource(scopeId, userId(), resourceId);
    }

    // ── artifacts ───────────────────────────────────────────────────────────────────────

    /** All artifacts registered for the scope (any state). */
    @GetMapping("/{scopeId}/artifacts")
    public List<Map<String, Object>> artifacts(@PathVariable String scopeId) {
        scopes.snapshot(scopeId, userId()); // ownership check
        return artifacts.listByScope(scopeId).stream().map(ChatResourceController::artifactDto).toList();
    }

    /** Saves (or retries) one artifact into the chosen directory; never reports unconfirmed success. */
    @PostMapping("/{scopeId}/artifacts/{artifactId}/save")
    public Map<String, Object> save(@PathVariable String scopeId, @PathVariable String artifactId,
            @RequestBody SaveRequest body) {
        ChatArtifactStore.Artifact owned = artifacts.get(artifactId);
        if (!scopeId.equals(owned.scopeId())) {
            throw new IllegalArgumentException("Artifact belongs to another conversation");
        }
        return artifactDto(artifacts.save(artifactId, body.targetPath()));
    }

    /**
     * The confirmed saved location of one artifact — the ONLY path source for the desktop
     * open/reveal bridge, so the renderer can never hand the shell an arbitrary string (7.4).
     */
    @GetMapping("/artifacts/{artifactId}/path")
    public Map<String, Object> savedPath(@PathVariable String artifactId) {
        return Map.of("path", artifacts.savedPath(artifactId).toString());
    }

    /** Web save path: the browser's "save" is a download of the pending copy. */
    @GetMapping("/artifacts/{artifactId}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String artifactId) {
        Path pending = artifacts.pendingPath(artifactId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\""
                        + pending.getFileName().toString().replace("\"", "") + "\"")
                .body(new FileSystemResource(pending));
    }

    /** Restart recovery (C09): pending artifacts of a persisted conversation, without write auth. */
    @GetMapping("/artifacts")
    public List<Map<String, Object>> pendingByConversation(@RequestParam Long conversationId) {
        return artifacts.listPendingByConversation(conversationId).stream()
                .map(ChatResourceController::artifactDto).toList();
    }

    // ── DTOs (presentation only — no plugin ids, no grant material) ─────────────────────

    /** Presentation view of one aggregated resource: names/ids only, never grant internals. */
    static Map<String, Object> resourceDto(ChatResourceScopeService.Resource resource) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resourceId", resource.resourceId());
        out.put("name", resource.name());
        out.put("kind", resource.kind());
        out.put("purpose", resource.purpose());
        out.put("access", resource.access());
        out.put("revision", resource.revision());
        out.put("status", resource.status());
        out.put("source", resource.source());
        out.put("size", resource.size());
        if (resource.displayPath() != null) out.put("displayPath", resource.displayPath());
        return out;
    }

    private static Map<String, Object> artifactDto(ChatArtifactStore.Artifact artifact) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("artifactId", artifact.artifactId());
        out.put("name", artifact.name());
        out.put("state", artifact.state());
        out.put("size", artifact.size());
        out.put("createdAt", artifact.createdAt().toString());
        if (artifact.savedPath() != null) out.put("savedPath", artifact.savedPath());
        if (artifact.error() != null) out.put("error", artifact.error());
        return out;
    }

    private static Map<String, Object> sendDto(ChatResourceScopeService.SendStatus status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sendId", status.sendId());
        out.put("state", status.state());
        out.put("attachments", status.attachments());
        if (status.error() != null) out.put("error", status.error());
        if (status.committedResult() != null) out.put("committedResult", status.committedResult());
        return out;
    }

    public record NativeAttachmentDto(String attachmentId, String path, String kind) {}
    public record PrepareSendRequest(String sendId, List<NativeAttachmentDto> attachments) {}
    public record OutputTargetRequest(String path) {}
    public record SaveRequest(String targetPath) {}
    public record ConversationBinding(Long conversationId) {}
}
