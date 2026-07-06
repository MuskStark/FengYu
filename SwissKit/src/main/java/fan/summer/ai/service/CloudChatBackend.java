package fan.summer.ai.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import fan.summer.ai.adapter.AiToolToToolSpecification;
import fan.summer.ai.adapter.ChatMessageMapper;
import fan.summer.ai.tools.ToolExecutor;
import fan.summer.ai.util.JsonHelper;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiServiceException;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.ChatBackend;
import fan.summer.zhiflow.api.ai.AiStreamCallback;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolCall;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cloud-mode {@link ChatBackend} backed by LangChain4j. One class serves both
 * OpenAI-compatible and Anthropic providers — select via the {@link #openAi} /
 * {@link #anthropic} static factories.
 *
 * <p>Each {@link #chat} call constructs a fresh streaming model (cheap; just
 * allocates state) so sampling parameters always take effect. HTTP/SSE/tool-loop
 * plumbing is delegated entirely to LC4j; this class only:
 * <ul>
 *   <li>translates {@link AiChatMessage} ↔ LC4j {@link ChatMessage}</li>
 *   <li>converts registered {@link AiTool}s to LC4j {@link ToolSpecification}s</li>
 *   <li>drives the multi-round tool loop (≤{@value #MAX_TOOL_ROUNDS} rounds) and
 *       fires {@link AiStreamCallback} events on the FX thread</li>
 * </ul>
 */
public final class CloudChatBackend implements ChatBackend {

    private static final Logger log = LoggerFactory.getLogger(CloudChatBackend.class);
    private static final int MAX_TOOL_ROUNDS = 5;
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(120);

    /**
     * Anthropic's default API base URL. {@code baseUrl} is only passed to the
     * model builder when the configured endpoint differs from this, so users
     * hitting the official API do not need to configure anything extra.
     */
    private static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";

    /** Identifies which cloud provider this backend serves. */
    public enum Provider { OPENAI, ANTHROPIC }

    private final Provider provider;
    private final String endpoint;
    private final String apiKey;
    private final String modelName;

    private final AtomicBoolean generating = new AtomicBoolean(false);

    private CloudChatBackend(Provider provider, String endpoint, String apiKey, String modelName) {
        this.provider = provider;
        this.endpoint = endpoint == null ? "" : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    // ── Factories ─────────────────────────────────────────────

    /**
     * Creates a backend targeting an OpenAI-compatible chat completion API
     * ({@code POST {endpoint}/v1/chat/completions}).
     *
     * @param endpoint  base URL (e.g. {@code "https://api.openai.com"}); trailing slash stripped
     * @param apiKey    bearer token sent in the {@code Authorization} header
     * @param modelName model identifier (e.g. {@code "gpt-4o"})
     */
    public static CloudChatBackend openAi(String endpoint, String apiKey, String modelName) {
        return new CloudChatBackend(Provider.OPENAI, endpoint, apiKey, modelName);
    }

    /**
     * Creates a backend targeting the Anthropic Messages API
     * ({@code POST {endpoint}/v1/messages}). If {@code endpoint} is blank or
     * equals {@link #DEFAULT_ANTHROPIC_BASE_URL}, LC4j's internal default is used.
     *
     * @param endpoint  base URL, or blank/official URL to use the built-in default
     * @param apiKey    value sent in the {@code x-api-key} header
     * @param modelName model identifier (e.g. {@code "claude-3-5-sonnet-20241022"})
     */
    public static CloudChatBackend anthropic(String endpoint, String apiKey, String modelName) {
        return new CloudChatBackend(Provider.ANTHROPIC, endpoint, apiKey, modelName);
    }

    // ── Public accessors (for SynchronousChatHelper and testConnection) ──

    public Provider provider() { return provider; }
    public String getEndpoint() { return endpoint; }
    public String getApiKey() { return apiKey; }
    public String getModelNameInternal() { return modelName; }

    // ── ChatBackend ───────────────────────────────────────────

    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        throw new AiServiceException("Local model loading not supported for cloud backend");
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

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, SwissKitJSettingUi.getAiTemperature(), SwissKitJSettingUi.getAiTopP(),
             SwissKitJSettingUi.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) {
            throw new AiServiceException(provider + " cloud backend not configured");
        }
        if (!generating.compareAndSet(false, true)) {
            throw new AiServiceException("Generation already in progress");
        }

        Thread.ofVirtual().start(() -> {
            try {
                runToolLoop(history, temperature, topP, maxTokens, callback);
            } catch (Exception e) {
                log.error("{} chat failed", provider, e);
                Platform.runLater(() -> callback.onError(e));
            } finally {
                generating.set(false);
            }
        });
    }

    @Override
    public void cancelGeneration() {
        log.debug("cancelGeneration() requested; LangChain4j 1.x streaming models do not support mid-stream abort");
    }

    // ── Tool loop ─────────────────────────────────────────────

    /**
     * Drives the multi-round tool loop synchronously (caller runs on a virtual thread).
     * Terminates when either the model returns no more tool calls or {@code MAX_TOOL_ROUNDS}
     * rounds have been completed.
     *
     * <p>The model is built fresh on every invocation to honour per-call sampling
     * parameters (temperature, topP, maxTokens). The cloud API contract requires the
     * message sequence {@code [user, assistantWithTools, toolResult]} when tools are
     * involved, so this method appends an {@code assistantWithTools} message to
     * {@code history} before executing tools. On the final (no-tools) response it
     * appends a plain assistant message so subsequent multi-turn user messages retain
     * the prior assistant reply in context.</p>
     *
     * <p>The system prompt from {@code SwissKitJSettingUi.getAiSystemPrompt()} is
     * injected as a leading {@link SystemMessage} on every round — LangChain4j's
     * Anthropic mapper routes this to the API's {@code system} field.</p>
     */
    private void runToolLoop(List<AiChatMessage> history, float temperature, float topP,
                             int maxTokens, AiStreamCallback callback) throws Exception {
        StreamingChatModel streamingModel = buildStreamingModel(temperature, topP, maxTokens);

        List<ToolSpecification> toolSpecs = null;
        List<AiTool> tools = AiServiceProvider.getTools();
        if (!tools.isEmpty()) {
            toolSpecs = tools.stream().map(AiToolToToolSpecification::convert).toList();
        }

        String systemPrompt = currentSystemPrompt();

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            Lc4jStreamHandler handler = new Lc4jStreamHandler(callback);
            List<ChatMessage> lcMessages = new ArrayList<>(history.size() + 1);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                lcMessages.add(SystemMessage.from(systemPrompt));
            }
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
            streamingModel.chat(request, handler);

            List<AiToolCall> calls = handler.pendingToolCalls();
            if (calls.isEmpty()) {
                // Final response: append assistant message to history for multi-turn continuity.
                // Callers like AiChatPlugin.onComplete only update UI; they rely on the backend
                // to mutate history, so without this the next user message would be sent with
                // no memory of the prior assistant reply.
                String finalText = handler.lastAssistantText();
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
            // tools to satisfy the cloud API contract that a tool result must follow
            // an assistant-with-tool-calls message. Without this, round 2 sends
            // [user, toolResult] and the server rejects it with HTTP 400.
            history.add(AiChatMessage.assistantWithTools(handler.lastAssistantText(), calls));
            ToolExecutor.executeAndFeed(calls, history, callback);
            // No resetForNextRound needed — a fresh handler is constructed on the next iteration.
        }
    }

    private StreamingChatModel buildStreamingModel(float temperature, float topP, int maxTokens) {
        switch (provider) {
            case OPENAI -> {
                return OpenAiStreamingChatModel.builder()
                    .baseUrl(endpoint)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature((double) temperature)
                    .topP((double) topP)
                    .maxTokens(maxTokens)
                    .timeout(MODEL_TIMEOUT)
                    .build();
            }
            case ANTHROPIC -> {
                AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder b =
                    AnthropicStreamingChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .temperature((double) temperature)
                        .topP((double) topP)
                        .maxTokens(maxTokens)
                        .timeout(MODEL_TIMEOUT);
                // Only override baseUrl for non-default endpoints (proxy/mirror setups).
                // LC4j defaults to https://api.anthropic.com internally.
                if (!endpoint.isBlank() && !endpoint.equalsIgnoreCase(DEFAULT_ANTHROPIC_BASE_URL)) {
                    b.baseUrl(endpoint);
                }
                return b.build();
            }
        }
        throw new IllegalStateException("Unsupported provider: " + provider);
    }

    private static String currentSystemPrompt() {
        try {
            return SwissKitJSettingUi.getAiSystemPrompt();
        } catch (Throwable t) {
            log.debug("Could not read system prompt from settings: {}", t.getMessage());
            return null;
        }
    }

    // ── testConnection (used by Settings UI) ──────────────────

    /**
     * Sends a minimal non-streaming request to validate credentials and endpoint.
     * Returns {@code null} on success (HTTP 200), or an error string otherwise.
     * Uses a direct HTTP call (independent of LangChain4j) so that connection
     * issues surface as actionable error strings rather than wrapped LC4j exceptions.
     */
    public String testConnection() {
        return switch (provider) {
            case OPENAI -> testOpenAi();
            case ANTHROPIC -> testAnthropic();
        };
    }

    private String testOpenAi() {
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
            // Some IOExceptions (e.g. ConnectException on connection refused) carry a null
            // message — falling back to e.toString() keeps testConnection() from returning
            // null (which the Settings UI interprets as success).
            String msg = e.getMessage();
            return msg != null ? msg : e.getClass().getSimpleName() + ": " + e;
        }
    }

    private String testAnthropic() {
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build()) {
            String url = endpoint + "/v1/messages";
            String body = JsonHelper.toJson(Map.of(
                "model", modelName,
                "max_tokens", 5,
                "messages", List.of(Map.of("role", "user", "content", "Hi"))
            ));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return null;
            return "HTTP " + resp.statusCode() + ": " + resp.body();
        } catch (Exception e) {
            // See testOpenAi() — ConnectException often carries a null message.
            String msg = e.getMessage();
            return msg != null ? msg : e.getClass().getSimpleName() + ": " + e;
        }
    }

    // ── LC4j → AiStreamCallback bridge ────────────────────────

    /**
     * Bridges LC4j stream events to {@link AiStreamCallback} and captures pending
     * tool calls for the host-driven multi-round loop. Same logic as the
     * {@code StreamingResponseHandlerBridge}, inlined here as a private inner class
     * so the cloud backend is self-contained.
     */
    private static final class Lc4jStreamHandler implements StreamingChatResponseHandler {

        private final AiStreamCallback callback;
        private final StringBuffer accumulated = new StringBuffer();
        private volatile String lastAssistantText = "";
        private volatile List<AiToolCall> pendingToolCalls = List.of();

        Lc4jStreamHandler(AiStreamCallback callback) {
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
            // Capture the assistant text BEFORE branching on tool calls so callers can
            // retrieve it even when the response carries tool-execution requests. The
            // cloud API contract requires the message sequence
            // [user, assistantWithTools, toolResult] — the host loop reads
            // {@link #lastAssistantText()} to populate the assistantWithTools message.
            String text = ai.text() == null ? accumulated.toString() : ai.text();
            this.lastAssistantText = text;

            if (ai.hasToolExecutionRequests()) {
                List<AiToolCall> calls = new ArrayList<>();
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    // Preserve the server-issued tool-call ID. Anthropic requires
                    // tool_result.tool_use_id to match the original tool_use.id exactly,
                    // so we must NOT regenerate it. Fall back only when the provider
                    // omits it (shouldn't happen in practice for Anthropic/OpenAI).
                    String id = req.id() != null && !req.id().isEmpty()
                        ? req.id()
                        : "tc_" + System.currentTimeMillis();
                    calls.add(AiToolCall.of(id, req.name(), parseArgs(req.arguments())));
                }
                this.pendingToolCalls = Collections.unmodifiableList(calls);
                return;
            }
            int tokens = Math.max(1, text.length() / 4);
            String finalText = text;
            Platform.runLater(() -> callback.onComplete(finalText, tokens, 0));
        }

        @Override
        public void onError(Throwable error) {
            Platform.runLater(() -> callback.onError(error));
        }

        List<AiToolCall> pendingToolCalls() { return pendingToolCalls; }
        String lastAssistantText() { return lastAssistantText; }

        private static Map<String, Object> parseArgs(String json) {
            if (json == null || json.isBlank()) return Map.of();
            try {
                return JsonHelper.parseObject(json);
            } catch (Exception e) {
                log.warn("Failed to parse tool-call arguments JSON, falling back to empty map: '{}'", json, e);
                return Map.of();
            }
        }
    }
}
