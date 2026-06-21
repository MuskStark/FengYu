package fan.summer.ai.adapter;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import fan.summer.api.ai.AiStreamCallback;
import fan.summer.api.ai.AiToolCall;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Bridges LangChain4j's {@link StreamingChatResponseHandler} events to SwissKitJ's
 * {@link AiStreamCallback}, and captures tool-execution requests so the host can
 * drive the multi-round tool loop manually (preserving UI tool-call/tool-result events).
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@code onPartialResponse} → {@code callback.onToken} (on FX thread)</li>
 *   <li>{@code onCompleteResponse} with no tool requests → {@code callback.onComplete}</li>
 *   <li>{@code onCompleteResponse} with tool requests → populate {@link #pendingToolCalls()};
 *       the host loop reads them, fires {@code callback.onToolCall/onToolResult} via
 *       {@code ToolExecutor}, then re-invokes the model</li>
 *   <li>{@code onError} → {@code callback.onError}</li>
 * </ul>
 */
public final class StreamingResponseHandlerBridge implements StreamingChatResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamingResponseHandlerBridge.class);

    private final AiStreamCallback callback;
    private final StringBuffer accumulated = new StringBuffer();
    private volatile List<AiToolCall> pendingToolCalls = List.of();

    public StreamingResponseHandlerBridge(AiStreamCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        if (partialResponse == null || partialResponse.isEmpty()) return;
        accumulated.append(partialResponse);
        String token = partialResponse;
        Platform.runLater(() -> callback.onToken(token));
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        AiMessage ai = completeResponse.aiMessage();
        if (ai.hasToolExecutionRequests()) {
            List<AiToolCall> calls = new ArrayList<>();
            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                calls.add(AiToolCall.of(req.name(), parseArgs(req.arguments())));
            }
            this.pendingToolCalls = Collections.unmodifiableList(calls);
            return;
        }
        String full = ai.text() == null ? accumulated.toString() : ai.text();
        int tokens = estimateTokens(full);
        Platform.runLater(() -> callback.onComplete(full, tokens, 0));
    }

    @Override
    public void onError(Throwable error) {
        Platform.runLater(() -> callback.onError(error));
    }

    /** Tool calls captured by the most recent {@link #onCompleteResponse}; empty if it was a final response. */
    public List<AiToolCall> pendingToolCalls() {
        return pendingToolCalls;
    }

    /** Resets accumulator and pending tool calls for the next round. Called by the loop driver between rounds. */
    public void resetForNextRound() {
        accumulated.setLength(0);
        pendingToolCalls = List.of();
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 4);
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return fan.summer.ai.util.JsonHelper.parseObject(json);
        } catch (Exception e) {
            log.warn("Failed to parse tool-call arguments JSON, falling back to empty map: '{}'", json, e);
            return Map.of();
        }
    }
}
