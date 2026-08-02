package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.ChatFileContext;
import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.ai.ChatFileGrantService;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.ai.service.AiModeService;
import fan.summer.fengyu.ai.service.OllamaLocalBackend;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.time.Instant;

/**
 * AI chat over Server-Sent Events. AI chat is a permanent core built-in — never routed through
 * the plugin {@code invoke} path.
 *
 * <p>Flow: {@code POST /api/ai/chat} accepts the conversation, stashes it under a random
 * {@code streamId}, and returns it. {@code GET /api/ai/stream?streamId=...} opens an
 * {@link SseEmitter} (EventSource-compatible, GET-only) and drives the chat, bridging
 * {@link AiStreamCallback} events to SSE events: {@code token}, {@code thinking}, {@code tool},
 * {@code done}, {@code error}.
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final AiModeService aiMode;
    private final ChatToolApprovalGate toolApprovalGate;
    private final ChatFileGrantService fileGrants;
    private final PluginFileGrantService pluginFiles;

    public AiController(AiModeService aiMode, ChatToolApprovalGate toolApprovalGate,
            ChatFileGrantService fileGrants, PluginFileGrantService pluginFiles) {
        this.aiMode = aiMode;
        this.toolApprovalGate = toolApprovalGate;
        this.fileGrants = fileGrants;
        this.pluginFiles = pluginFiles;
    }

    /** Pending turns keyed by streamId; consumed once when the SSE opens. */
    private final Map<String, PendingTurn> pending = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeStreamId = new AtomicReference<>();

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req) {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));
        pending.entrySet().removeIf(entry -> {
            if (!entry.getValue().createdAt().isBefore(cutoff)) return false;
            for (ActiveFileRef ref : entry.getValue().activeFileRefs()) {
                pluginFiles.revoke(ref.pluginId(), ref.ref().id());
            }
            return true;
        });
        if (pending.size() >= 100) throw new IllegalStateException("Too many pending AI streams");
        List<AiChatMessage> history = new ArrayList<>();
        if (req.messages() != null) {
            for (ChatMessageDto m : req.messages()) {
                history.add(toDomain(m));
            }
        }
        List<ActiveFileRef> refs = new ArrayList<>();
        if (req.activeFileRefs() != null) {
            for (ActiveFileRefDto dto : req.activeFileRefs()) {
                pluginFiles.validate(dto.pluginId(), dto.ref());
                refs.add(new ActiveFileRef(dto.pluginId(), dto.ref()));
            }
        }
        // A path typed into the composer is just as explicit as a picker selection. Resolve only
        // the latest USER message, only when it names an existing absolute path, then turn it into
        // normal plugin-scoped grants. The model can never create grants by mentioning a path in
        // an assistant/tool message.
        refs.addAll(fileGrants.grantPathsFromUserText(latestUserText(req.messages())));
        String streamId = UUID.randomUUID().toString();
        pending.put(streamId, new PendingTurn(history, refs, AiPermissionMode.from(req.permissionMode()), Instant.now()));
        List<ActiveFileRefDto> responseRefs = refs.stream()
            .map(ref -> new ActiveFileRefDto(ref.pluginId(), ref.ref())).toList();
        return Map.of("streamId", streamId, "activeFileRefs", responseRefs);
    }

    @PostMapping("/tool-approvals/{approvalId}")
    public Map<String, Object> resolveToolApproval(@PathVariable String approvalId,
                                                   @RequestBody ToolApprovalDecision decision) {
        boolean resolved = toolApprovalGate.resolve(approvalId, decision.approved());
        return resolved
                ? Map.of("ok", true, "approved", decision.approved())
                : Map.of("ok", false, "error", "Unknown, expired, or already resolved approval");
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestParam String streamId) {
        if (!activeStreamId.compareAndSet(streamId, null)) {
            return Map.of("ok", false, "error", "Stream is not the active generation");
        }
        aiMode.getService().ifPresent(ChatBackend::cancelGeneration);
        return Map.of("ok", true, "streamId", streamId);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String streamId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — chat length is unbounded
        PendingTurn turn = pending.remove(streamId);
        if (turn == null) {
            completeWithError(emitter, "Unknown or expired streamId");
            return emitter;
        }
        List<AiChatMessage> history = turn.history();

        Optional<ChatBackend> svc = aiMode.getService();
        if (svc.isEmpty()) {
            completeWithError(emitter, "AI backend not configured");
            return emitter;
        }
        ChatBackend backend = svc.get();
        // Local (Ollama) backends resolve their ChatModel lazily in loadModel; trigger it on
        // first chat so isReady() can flip to true. After Task 3's BackendReactivator the backend
        // is registered at startup but never loadModel'd — without this, local mode always errored
        // as "not configured or not ready".
        if (!backend.isReady() && backend instanceof OllamaLocalBackend ob) {
            try {
                ob.loadModel(null);
            } catch (Exception e) {
                completeWithError(emitter, "Ollama backend not ready: " + e.getMessage());
                return emitter;
            }
        }
        if (!backend.isReady()) {
            completeWithError(emitter, "AI backend not ready (check provider config and connection)");
            return emitter;
        }
        if (!activeStreamId.compareAndSet(null, streamId)) {
            completeWithError(emitter, "Another AI generation is already in progress");
            return emitter;
        }

        // Send an immediate heartbeat so the response stream is "opened" before the model
        // produces its first token. WKWebView (Tauri desktop on macOS) will silently drop an
        // SSE connection that has been accepted but has not yet received any data by the time
        // its internal idle timer fires — Chrome is more lenient. Flushing one byte right away
        // guarantees the connection is live for every client, and costs nothing (a comment line
        // is a valid SSE frame the browser ignores).
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            log.debug("SSE initial heartbeat failed: {}", e.getMessage());
        }

        try {
            // Set the per-turn file context BEFORE chat() so the singleton plugin ToolCallbacks
            // (Task 3's AiToolFileInjector) can read it during synchronous tool execution. The
            // virtual-thread worker runs chat() inline under this binding, so the ThreadLocal is
            // visible for the whole tool-execution window. Cleared in finally to avoid leakage.
            ChatFileContext.set(turn.activeFileRefs());
            AiPermissionContext.set(turn.permissionMode());
            svc.get().chat(history,
                AiConfigServiceHeadless.getAiTemperature(),
                AiConfigServiceHeadless.getAiTopP(),
                AiConfigServiceHeadless.getAiMaxTokens(),
                turn.activeFileRefs(),
                new SseCallback(emitter, () -> activeStreamId.compareAndSet(streamId, null)));
        } catch (Exception e) {
            activeStreamId.compareAndSet(streamId, null);
            completeWithError(emitter, e.getMessage());
        } finally {
            ChatFileContext.clear();
            AiPermissionContext.clear();
        }
        return emitter;
    }

    // ── AiStreamCallback → SSE bridge ──────────────────────────────────────────────────

    private static final class SseCallback implements AiStreamCallback {
        private final SseEmitter emitter;
        private final Runnable terminal;

        SseCallback(SseEmitter emitter, Runnable terminal) {
            this.emitter = emitter;
            this.terminal = terminal;
        }

        @Override public void onToken(String fragment) {
            send("token", Map.of("text", fragment == null ? "" : fragment));
        }

        @Override public void onThinking(String fragment) {
            send("thinking", Map.of("text", fragment == null ? "" : fragment));
        }

        @Override public void onToolCall(AiToolCall toolCall) {
            send("tool", Map.of("phase", "call", "id", toolCall.id() == null ? "" : toolCall.id(), "name", toolCall.name(),
                "arguments", toolCall.arguments() == null ? Map.of() : toolCall.arguments()));
        }

        @Override
        public void onToolApprovalRequired(String approvalId, AiToolCall toolCall,
                                           java.time.Instant expiresAt) {
            send("tool", Map.of(
                    "phase", "approval_required",
                    "approvalId", approvalId,
                    "id", toolCall.id() == null ? "" : toolCall.id(),
                    "name", toolCall.name(),
                    "arguments", toolCall.arguments() == null ? Map.of() : toolCall.arguments(),
                    "expiresAt", expiresAt.toString()));
        }

        @Override public void onToolResult(String toolCallId, AiToolResult result) {
            send("tool", Map.of("phase", "result", "id", toolCallId == null ? "" : toolCallId,
                "success", result.success(), "output", result.output() == null ? "" : result.output()));
        }

        @Override public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
            send("done", Map.of("text", fullResponse == null ? "" : fullResponse,
                "tokens", tokensGenerated, "tps", tokensPerSecond));
            emitter.complete();
            terminal.run();
        }

        @Override public void onError(Throwable error) {
            send("error", Map.of("message", error == null ? "unknown" : String.valueOf(error.getMessage())));
            emitter.complete();
            terminal.run();
        }

        private void send(String event, Object data) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                log.debug("SSE send failed ({}): {}", event, e.getMessage());
            }
        }
    }

    private void completeWithError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                .data(Map.of("message", message == null ? "unknown" : message), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.debug("SSE error send failed: {}", e.getMessage());
        }
        emitter.complete();
    }

    private static AiChatMessage toDomain(ChatMessageDto m) {
        String role = m.role() == null ? "user" : m.role();
        String content = m.content() == null ? "" : m.content();
        return switch (role) {
            case "system" -> AiChatMessage.system(content);
            case "assistant" -> AiChatMessage.assistant(content);
            default -> AiChatMessage.user(content);
        };
    }

    private static String latestUserText(List<ChatMessageDto> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto message = messages.get(i);
            if (message != null && (message.role() == null || "user".equals(message.role()))) {
                return message.content() == null ? "" : message.content();
            }
        }
        return "";
    }

    // ── DTOs ────────────────────────────────────────────────────────────────────────────

    public record ChatRequest(List<ChatMessageDto> messages, List<ActiveFileRefDto> activeFileRefs,
                              String permissionMode) {
        public ChatRequest(List<ChatMessageDto> messages, List<ActiveFileRefDto> activeFileRefs) {
            this(messages, activeFileRefs, null);
        }
    }
    public record ChatMessageDto(String role, String content) {}
    public record ToolApprovalDecision(boolean approved) {}
    public record ActiveFileRefDto(String pluginId, PluginFileGrantService.FileRef ref) {}

    /** Carries a stashed turn's history + active file refs from {@code POST /chat} to {@code GET /stream}. */
    private record PendingTurn(List<AiChatMessage> history, List<ActiveFileRef> activeFileRefs,
                               AiPermissionMode permissionMode, Instant createdAt) {}
}
