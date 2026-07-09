package fan.summer.zhiflow.web.controller;

import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiStreamCallback;
import fan.summer.zhiflow.api.ai.AiToolCall;
import fan.summer.zhiflow.api.ai.AiToolResult;
import fan.summer.zhiflow.api.ai.ChatBackend;
import fan.summer.zhiflow.ai.service.AiConfigServiceHeadless;
import fan.summer.zhiflow.ai.service.AiModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    public AiController(AiModeService aiMode) { this.aiMode = aiMode; }

    /** Pending turns keyed by streamId; consumed once when the SSE opens. */
    private final Map<String, List<AiChatMessage>> pending = new ConcurrentHashMap<>();

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req) {
        List<AiChatMessage> history = new ArrayList<>();
        if (req.messages() != null) {
            for (ChatMessageDto m : req.messages()) {
                history.add(toDomain(m));
            }
        }
        String streamId = UUID.randomUUID().toString();
        pending.put(streamId, history);
        return Map.of("streamId", streamId);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String streamId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — chat length is unbounded
        List<AiChatMessage> history = pending.remove(streamId);

        if (history == null) {
            completeWithError(emitter, "Unknown or expired streamId");
            return emitter;
        }

        Optional<ChatBackend> svc = aiMode.getService();
        if (svc.isEmpty() || !svc.get().isReady()) {
            completeWithError(emitter, "AI backend not configured or not ready");
            return emitter;
        }

        try {
            svc.get().chat(history,
                AiConfigServiceHeadless.getAiTemperature(),
                AiConfigServiceHeadless.getAiTopP(),
                AiConfigServiceHeadless.getAiMaxTokens(),
                new SseCallback(emitter));
        } catch (Exception e) {
            completeWithError(emitter, e.getMessage());
        }
        return emitter;
    }

    // ── AiStreamCallback → SSE bridge ──────────────────────────────────────────────────

    private static final class SseCallback implements AiStreamCallback {
        private final SseEmitter emitter;

        SseCallback(SseEmitter emitter) { this.emitter = emitter; }

        @Override public void onToken(String fragment) {
            send("token", Map.of("text", fragment == null ? "" : fragment));
        }

        @Override public void onThinking(String fragment) {
            send("thinking", Map.of("text", fragment == null ? "" : fragment));
        }

        @Override public void onToolCall(AiToolCall toolCall) {
            send("tool", Map.of("phase", "call", "name", toolCall.name(),
                "arguments", toolCall.arguments() == null ? Map.of() : toolCall.arguments()));
        }

        @Override public void onToolResult(String toolCallId, AiToolResult result) {
            send("tool", Map.of("phase", "result", "id", toolCallId == null ? "" : toolCallId,
                "success", result.success(), "output", result.output() == null ? "" : result.output()));
        }

        @Override public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
            send("done", Map.of("text", fullResponse == null ? "" : fullResponse,
                "tokens", tokensGenerated, "tps", tokensPerSecond));
            emitter.complete();
        }

        @Override public void onError(Throwable error) {
            send("error", Map.of("message", error == null ? "unknown" : String.valueOf(error.getMessage())));
            emitter.complete();
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

    // ── DTOs ────────────────────────────────────────────────────────────────────────────

    public record ChatRequest(List<ChatMessageDto> messages) {}
    public record ChatMessageDto(String role, String content) {}
}
