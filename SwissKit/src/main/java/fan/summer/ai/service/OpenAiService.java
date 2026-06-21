package fan.summer.ai.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import fan.summer.ai.adapter.AiToolToToolSpecification;
import fan.summer.ai.adapter.ChatMessageMapper;
import fan.summer.ai.adapter.CloudAiConfigProvider;
import fan.summer.ai.adapter.StreamingResponseHandlerBridge;
import fan.summer.ai.tools.ToolExecutor;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.ai.*;
import fan.summer.ui.setting.SwissKitJSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link AiService} implementation for calling OpenAI-compatible chat completion APIs.
 *
 * <p>HTTP/SSE plumbing is delegated to LangChain4j's {@link OpenAiStreamingChatModel};
 * the multi-round tool loop is driven manually via {@link StreamingResponseHandlerBridge}
 * and {@link ToolExecutor#executeAndFeed} so that UI tool-call/tool-result events
 * are preserved. Local model loading is not supported.</p>
 *
 * <p>Configure the service using {@link #configure(String, String, String)}
 * before invoking {@link #chat(List, AiStreamCallback)}.</p>
 *
 * @see AiService
 * @see CloudAiConfigProvider
 */
public class OpenAiService implements AiService, CloudAiConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private static final int MAX_TOOL_ROUNDS = 5;
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(120);

    private final AtomicBoolean generating = new AtomicBoolean(false);

    private String endpoint;
    private String apiKey;
    private String modelName;

    public OpenAiService() {}

    /**
     * Configures the endpoint, API key, and model name for this service.
     *
     * @param endpoint  the base URL of the OpenAI-compatible API; trailing slashes are stripped
     * @param apiKey    the API key for authentication
     * @param modelName the model identifier (e.g., {@code "gpt-4o"})
     */
    public void configure(String endpoint, String apiKey, String modelName) {
        this.endpoint = endpoint == null ? "" : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        throw new AiServiceException("Local model loading not supported for OpenAI mode");
    }

    @Override public void unloadModel() {
        // No-op — model is built fresh on each chat() call, so there is nothing to unload.
    }

    @Override public boolean isReady() {
        return endpoint != null && !endpoint.isBlank()
            && apiKey != null && !apiKey.isBlank()
            && modelName != null && !modelName.isBlank();
    }

    @Override public Optional<String> getModelName() { return Optional.ofNullable(modelName); }
    @Override public long getMemoryUsage() { return -1; }
    @Override public boolean isGenerating() { return generating.get(); }

    @Override public String getEndpoint() { return endpoint; }
    @Override public String getApiKey() { return apiKey; }
    @Override public String getModelNameInternal() { return modelName; }

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, SwissKitJSettingUi.getAiTemperature(), SwissKitJSettingUi.getAiTopP(),
             SwissKitJSettingUi.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) {
            throw new AiServiceException("OpenAI service not configured");
        }
        if (!generating.compareAndSet(false, true)) {
            throw new AiServiceException("Generation already in progress");
        }

        Thread.ofVirtual().start(() -> {
            try {
                runToolLoop(history, temperature, topP, maxTokens, callback);
            } catch (Exception e) {
                log.error("OpenAI chat failed", e);
                Platform.runLater(() -> callback.onError(e));
            } finally {
                generating.set(false);
            }
        });
    }

    /**
     * Drives the multi-round tool loop synchronously (caller runs on a virtual thread).
     * Terminates when either the model returns no more tool calls or {@code MAX_TOOL_ROUNDS}
     * rounds have been completed.
     *
     * <p>The model is built fresh on every invocation to honour per-call sampling
     * parameters (temperature, topP, maxTokens). The OpenAI API contract requires the
     * message sequence {@code [user, assistantWithTools, toolResult]} when tools are
     * involved, so this method appends an {@code assistantWithTools} message to
     * {@code history} before executing tools. On the final (no-tools) response it
     * appends a plain assistant message so subsequent multi-turn user messages retain
     * the prior assistant reply in context.</p>
     */
    private void runToolLoop(List<AiChatMessage> history, float temperature, float topP,
                             int maxTokens, AiStreamCallback callback) {
        OpenAiStreamingChatModel m = OpenAiStreamingChatModel.builder()
            .baseUrl(endpoint)
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature((double) temperature)
            .topP((double) topP)
            .maxTokens(maxTokens)
            .timeout(MODEL_TIMEOUT)
            .build();

        List<ToolSpecification> toolSpecs = null;
        List<AiTool> tools = AiServiceProvider.getTools();
        if (!tools.isEmpty()) {
            toolSpecs = tools.stream().map(AiToolToToolSpecification::convert).toList();
        }

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            StreamingResponseHandlerBridge bridge = new StreamingResponseHandlerBridge(callback);
            List<ChatMessage> lcMessages = new ArrayList<>(history.size());
            for (AiChatMessage msg : history) {
                lcMessages.add(ChatMessageMapper.toLc4j(msg));
            }

            ChatRequest request;
            if (toolSpecs != null) {
                request = ChatRequest.builder()
                    .messages(lcMessages)
                    .toolSpecifications(toolSpecs)
                    .build();
            } else {
                request = ChatRequest.builder()
                    .messages(lcMessages)
                    .build();
            }
            m.chat(request, bridge);

            List<AiToolCall> calls = bridge.pendingToolCalls();
            if (calls.isEmpty()) {
                // Final response: append assistant message to history for multi-turn continuity.
                // Callers like AiChatPlugin.onComplete only update UI; they rely on the service
                // to mutate history, so without this the next user message would be sent with
                // no memory of the prior assistant reply.
                String finalText = bridge.lastAssistantText();
                if (finalText != null && !finalText.isBlank()) {
                    history.add(AiChatMessage.assistant(finalText));
                }
                return;
            }
            if (round == MAX_TOOL_ROUNDS) {
                String warn = "Reached MAX_TOOL_ROUNDS (" + MAX_TOOL_ROUNDS + ")";
                log.warn(warn);
                Platform.runLater(() -> callback.onComplete(warn, 0, 0));
                return;
            }
            // Tool round: append the assistant-with-tool-calls message BEFORE executing
            // tools to satisfy the OpenAI API contract that a tool result must follow
            // an assistant-with-tool-calls message. Without this, round 2 sends
            // [user, toolResult] and the server rejects it with HTTP 400.
            history.add(AiChatMessage.assistantWithTools(bridge.lastAssistantText(), calls));
            ToolExecutor.executeAndFeed(calls, history, callback);
            // No resetForNextRound needed — a fresh bridge is constructed on the next iteration.
        }
    }

    @Override
    public void cancelGeneration() {
        log.debug("cancelGeneration() requested; LangChain4j 1.0.x streaming model does not support mid-stream abort");
    }

    @Override public void registerTool(AiTool tool) { AiServiceProvider.registerTool(tool); }
    @Override public void unregisterTool(String toolName) { AiServiceProvider.unregisterTool(toolName); }
    @Override public List<AiTool> getTools() { return AiServiceProvider.getTools(); }

    /**
     * Tests connectivity to the configured endpoint with a minimal non-streaming request.
     * Uses a direct HTTP call (independent of LangChain4j) so that connection issues
     * surface as actionable error strings rather than wrapped LC4j exceptions.
     *
     * @return {@code null} if the connection succeeds (HTTP 200), otherwise
     *         an error string describing the failure
     */
    public String testConnection() {
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build()) {
            String url = endpoint + "/v1/chat/completions";
            String body = JsonHelper.toJson(Map.of(
                "model", modelName,
                "messages", List.of(Map.of("role", "user", "content", "Hi")),
                "max_tokens", 5
            ));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return null;
            return "HTTP " + resp.statusCode() + ": " + resp.body();
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
