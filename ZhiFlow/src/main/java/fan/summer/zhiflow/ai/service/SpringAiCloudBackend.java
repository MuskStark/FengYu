package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.ai.spring.AiSpringContext;
import fan.summer.zhiflow.ai.util.JsonHelper;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiServiceException;
import fan.summer.zhiflow.api.ai.AiStreamCallback;
import fan.summer.zhiflow.api.ai.AiToolCall;
import fan.summer.zhiflow.api.ai.AiToolResult;
import fan.summer.zhiflow.api.ai.ChatBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cloud-mode {@link ChatBackend} backed by Spring AI's {@code OpenAiChatModel} /
 * {@code AnthropicChatModel}. Replaces the LangChain4j {@code CloudChatBackend}.
 *
 * <p>The bean is looked up from {@link AiSpringContext} by name
 * ({@code openAiChatModel} / {@code anthropicChatModel}); the provider is fixed at
 * construction time. A {@link ChatClient} is built lazily from the resolved
 * {@link ChatModel}.
 *
 * <p><b>Tool execution (4.0.0 refactor):</b> tool calling now runs on Spring AI's
 * non-deprecated {@link ToolCallingManager} (user-controlled execution). Each
 * {@code chat()} call streams the model response via {@link ChatModel#stream(Prompt)},
 * aggregates the streamed chunks with {@link MessageAggregator}, and — when the model
 * requests tool calls — hands them to {@link ToolCallingManager#executeToolCalls} and
 * re-streams. This fixes a latent bug where tool callbacks were never passed to the
 * Prompt (they are now supplied via {@link ToolCallingChatOptions#getToolCallbacks()}).
 * {@link AiStreamCallback#onToken} / {@code onToolCall} / {@code onToolResult} /
 * {@code onComplete} all still fire so the UI tool-progress contract is preserved.
 */
public final class SpringAiCloudBackend implements ChatBackend {

    private static final Logger log = LoggerFactory.getLogger(SpringAiCloudBackend.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    public enum Provider { OPENAI, ANTHROPIC, DEEPSEEK }

    private final Provider provider;
    private final String endpoint;
    private final String apiKey;
    private final String modelName;
    private final ChatModel chatModel;          // resolved at construction
    private final AtomicBoolean generating = new AtomicBoolean(false);

    /** Cached ChatClient built from {@link #chatModel} (built lazily; null when model is null). */
    private volatile ChatClient chatClient;

    /**
     * The {@link ToolCallback}s made available to the model. Injected by the host wiring
     * (Task 13 registers the first {@code @Tool} bean) or by tests; until then the list is
     * empty and the model simply never requests a tool. Tolerates {@code null} (treated as
     * empty). Replaces the old global tool-registry discovery path.
     */
    private volatile List<ToolCallback> toolCallbacks = List.of();

    /** Shared {@link ToolCallingManager} that drives user-controlled tool execution. */
    private volatile ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

    // ── Production constructors (look up the ChatModel bean) ──────────

    public static SpringAiCloudBackend openAi(String endpoint, String apiKey, String modelName) {
        ChatModel model = resolveModel("openAiChatModel", Provider.OPENAI, endpoint, apiKey, modelName);
        return new SpringAiCloudBackend(Provider.OPENAI, endpoint, apiKey, modelName, model);
    }

    public static SpringAiCloudBackend anthropic(String endpoint, String apiKey, String modelName) {
        ChatModel model = resolveModel("anthropicChatModel", Provider.ANTHROPIC, endpoint, apiKey, modelName);
        return new SpringAiCloudBackend(Provider.ANTHROPIC, endpoint, apiKey, modelName, model);
    }

    /** DeepSeek uses an OpenAI-compatible API; the bean reuses the OpenAI model path. */
    public static SpringAiCloudBackend deepSeek(String endpoint, String apiKey, String modelName) {
        ChatModel model = resolveModel("deepSeekChatModel", Provider.DEEPSEEK, endpoint, apiKey, modelName);
        return new SpringAiCloudBackend(Provider.DEEPSEEK, endpoint, apiKey, modelName, model);
    }

    /**
     * Resolves the (lazy) {@code ChatModel} bean only when the provider is fully
     * configured. The vendor SDK client throws immediately if the API key is blank,
     * and forcing the lazy bean at mode-switch time would surface that as an uncaught
     * exception on the FX thread. When not configured we return {@code null}: the
     * backend still registers, {@link #isReady()} returns false, and {@code chat()}
     * throws a clean "not configured" message instead of crashing. The bean is
     * resolved on the next mode switch once the user fills in the key.
     */
    private static ChatModel resolveModel(String beanName, Provider provider,
                                          String endpoint, String apiKey, String modelName) {
        if (isBlank(endpoint) || isBlank(apiKey) || isBlank(modelName)) {
            log.info("{} backend not fully configured (missing endpoint/apiKey/model); "
                     + "deferring ChatModel resolution until configured", provider);
            return null;
        }
        try {
            return AiSpringContext.getBean(beanName, ChatModel.class);
        } catch (Exception e) {
            log.warn("Failed to build {} ChatModel bean '{}': {}", provider, beanName, e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    // ── Test constructor (inject ChatModel directly, bypass Spring) ───

    SpringAiCloudBackend(ChatModel chatModel) {
        this(Provider.OPENAI, "test", "test-key", "test-model", chatModel);
    }

    private SpringAiCloudBackend(Provider provider, String endpoint, String apiKey, String modelName, ChatModel chatModel) {
        this.provider = provider;
        this.endpoint = endpoint == null ? "" : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.chatModel = chatModel;
        if (chatModel != null) {
            this.chatClient = ChatClient.builder(chatModel).build();
        }
    }

    // ── Public accessors (preserved for SynchronousChatHelper + Settings UI) ──

    public Provider provider()           { return provider; }
    public String getEndpoint()          { return endpoint; }
    public String getApiKey()            { return apiKey; }
    public String getModelNameInternal() { return modelName; }

    /**
     * Sets the {@link ToolCallback}s available to the model (host wiring + tests). Accepts
     * {@code null} (treated as "no tools"). Defensive copy is intentionally NOT made — the
     * caller is expected to pass an effectively-immutable list.
     */
    public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
        this.toolCallbacks = toolCallbacks != null ? toolCallbacks : List.of();
    }

    // ── ChatBackend ───────────────────────────────────────────────────

    @Override public void loadModel(Path modelPath) throws AiServiceException {
        throw new AiServiceException("Local model loading not supported for cloud backend");
    }

    @Override public void unloadModel() { /* model bean is reused; nothing to release */ }

    @Override public boolean isReady() {
        return chatModel != null
            && endpoint != null && !endpoint.isBlank()
            && apiKey != null && !apiKey.isBlank()
            && modelName != null && !modelName.isBlank();
    }

    @Override public Optional<String> getModelName() { return Optional.ofNullable(modelName); }
    @Override public long getMemoryUsage() { return -1; }
    @Override public boolean isGenerating() { return generating.get(); }

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, AiConfigServiceHeadless.getAiTemperature(), AiConfigServiceHeadless.getAiTopP(),
             AiConfigServiceHeadless.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) throw new AiServiceException(provider + " cloud backend not configured");
        if (!generating.compareAndSet(false, true)) throw new AiServiceException("Generation already in progress");

        Thread.ofVirtual().start(() -> {
            try {
                runToolLoop(history, callback);
            } catch (Exception e) {
                log.error("{} chat failed", provider, e);
                callback.onError(e);
            } finally {
                generating.set(false);
            }
        });
    }

    @Override public void cancelGeneration() {
        log.debug("cancelGeneration() requested; mid-stream abort not wired in Phase 1");
    }

    // ── Tool loop (Spring AI ToolCallingManager, user-controlled) ──────

    private void runToolLoop(List<AiChatMessage> history, AiStreamCallback callback) {
        String systemPrompt = currentSystemPrompt();

        // Tool-callback options are attached to every Prompt so the model CAN request
        // tools (this is the bug fix: previously tools were never passed to the Prompt).
        ToolCallback[] callbacks = this.toolCallbacks.toArray(new ToolCallback[0]);
        ToolCallingChatOptions options = callbacks.length == 0
                ? null
                : ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();

        // The Spring AI conversation is the source of truth sent to the model. It starts
        // from ZhiFlow history; once tool calls happen, ToolCallingManager extends it
        // (assistant tool-call msg + ToolResponseMessage) and we carry that forward.
        List<Message> conversation = buildSpringAiMessages(history, systemPrompt);

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            Prompt prompt = options != null ? new Prompt(conversation, options) : new Prompt(conversation);

            // Stream this round; fire onToken per token delta; the aggregator hands us the
            // fully-assembled ChatResponse (including any tool calls) on completion.
            StringBuilder accumulated = new StringBuilder();
            AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
            streamAndCollect(prompt, accumulated, aggregated, callback);

            ChatResponse roundResp = aggregated.get();
            boolean hasToolCalls = roundResp != null && roundResp.hasToolCalls();

            if (!hasToolCalls) {
                String finalText = accumulated.toString();
                if (!finalText.isBlank()) history.add(AiChatMessage.assistant(finalText));
                int tokens = Math.max(1, finalText.length() / 4);
                callback.onComplete(finalText, tokens, 0);
                return;
            }
            if (round == MAX_TOOL_ROUNDS) {
                String warn = "Reached MAX_TOOL_ROUNDS (" + MAX_TOOL_ROUNDS + ")";
                log.warn(warn);
                callback.onComplete(warn, 0, 0);
                return;
            }

            // User-controlled tool execution: let Spring AI's ToolCallingManager run the
            // requested tools (it resolves them against the options' toolCallbacks), firing
            // onToolCall/onToolResult for each so the UI shows tool progress.
            AssistantMessage assistantMsg = roundResp.getResult().getOutput();
            history.add(AiChatMessage.assistantWithTools(accumulated.toString(), mapToolCalls(assistantMsg)));

            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, roundResp);
            fireToolEvents(assistantMsg, result, callback);

            // Carry the manager's extended conversation (original msgs + assistant tool-call
            // msg + ToolResponseMessage) into the next round, and mirror tool results into
            // ZhiFlow's own history for UI parity.
            conversation = result.conversationHistory();
            mirrorToolResultsToHistory(result.conversationHistory(), history, assistantMsg);
        }
    }

    /** Stream a prompt, fire onToken per token delta, and capture the aggregated response. */
    private void streamAndCollect(Prompt prompt, StringBuilder accumulated,
                                  AtomicReference<ChatResponse> aggregated, AiStreamCallback callback) {
        new MessageAggregator().aggregate(
                chatModel.stream(prompt),
                aggregated::set
        ).doOnNext(resp -> {
            if (resp == null || resp.getResult() == null) return;
            AssistantMessage am = resp.getResult().getOutput();
            if (am == null) return;
            String delta = am.getText();
            if (delta != null && !delta.isEmpty()) {
                accumulated.append(delta);
                callback.onToken(delta);
            }
        }).blockLast();   // virtual thread, blocking is fine
    }

    private List<Message> buildSpringAiMessages(List<AiChatMessage> history, String systemPrompt) {
        List<Message> msgs = new ArrayList<>(history.size() + 1);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.add(new SystemMessage(systemPrompt));
        }
        for (AiChatMessage m : history) msgs.add(AiMessageBridge.toSpringAi(m));
        return msgs;
    }

    private static List<AiToolCall> mapToolCalls(AssistantMessage am) {
        if (am == null || !am.hasToolCalls()) return List.of();
        List<AiToolCall> out = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
            String id = tc.id() != null && !tc.id().isEmpty() ? tc.id() : "tc_" + System.currentTimeMillis();
            out.add(AiToolCall.of(id, tc.name(), parseArgs(tc.arguments())));
        }
        return out;
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return JsonHelper.parseObject(json); }
        catch (Exception e) { return Map.of(); }
    }

    /**
     * Fire {@code onToolCall}/{@code onToolResult} for each requested tool call, mapping
     * the Spring AI {@link ToolResponseMessage} results back to ZhiFlow's
     * {@link AiToolResult}.
     */
    private static void fireToolEvents(AssistantMessage assistantMsg, ToolExecutionResult result,
                                       AiStreamCallback callback) {
        ToolResponseMessage trm = lastToolResponseMessage(result.conversationHistory());
        if (trm == null || assistantMsg == null || !assistantMsg.hasToolCalls()) return;
        // The ToolResponseMessage responses line up by index with the assistant's tool calls.
        List<AssistantMessage.ToolCall> calls = assistantMsg.getToolCalls();
        List<ToolResponseMessage.ToolResponse> responses = trm.getResponses();
        int n = Math.min(calls.size(), responses.size());
        for (int i = 0; i < n; i++) {
            AssistantMessage.ToolCall tc = calls.get(i);
            ToolResponseMessage.ToolResponse tr = responses.get(i);
            callback.onToolCall(AiToolCall.of(
                    tc.id() != null && !tc.id().isEmpty() ? tc.id() : tr.id(),
                    tc.name(), parseArgs(tc.arguments())));
            callback.onToolResult(tr.id(), AiToolResult.success(tr.responseData()));
        }
    }

    private static ToolResponseMessage lastToolResponseMessage(List<Message> messages) {
        ToolResponseMessage found = null;
        for (Message m : messages) {
            if (m instanceof ToolResponseMessage trm) found = trm;
        }
        return found;
    }

    /**
     * Mirror the tool-result messages Spring AI added (so the model sees them) back into
     * ZhiFlow's own history list — preserves the [user, assistantWithTools, toolResult,
     * assistant-final] shape the old loop produced. Best-effort; the Spring AI
     * conversation history is the source of truth sent to the model.
     */
    private static void mirrorToolResultsToHistory(List<Message> springAiHistory, List<AiChatMessage> zhiflowHistory,
                                                   AssistantMessage assistantMsg) {
        ToolResponseMessage trm = lastToolResponseMessage(springAiHistory);
        if (trm == null || assistantMsg == null || !assistantMsg.hasToolCalls()) return;
        List<AssistantMessage.ToolCall> calls = assistantMsg.getToolCalls();
        List<ToolResponseMessage.ToolResponse> responses = trm.getResponses();
        int n = Math.min(calls.size(), responses.size());
        for (int i = 0; i < n; i++) {
            AssistantMessage.ToolCall tc = calls.get(i);
            ToolResponseMessage.ToolResponse tr = responses.get(i);
            zhiflowHistory.add(AiChatMessage.toolResult(
                    tc.id() != null && !tc.id().isEmpty() ? tc.id() : tr.id(),
                    tc.name(), tr.responseData()));
        }
    }

    private static String currentSystemPrompt() {
        try { return AiConfigServiceHeadless.getAiSystemPrompt(); }
        catch (Throwable t) { return null; }
    }

    // ── testConnection (used by Settings UI) ──────────────────────────
    // Raw HTTP probe, independent of the AI library, so connection issues surface
    // as actionable strings rather than wrapped exceptions. Returns null on success.

    public String testConnection() {
        return switch (provider) {
            case OPENAI, DEEPSEEK -> testOpenAi();   // DeepSeek is OpenAI-compatible
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
            String msg = e.getMessage();
            return msg != null ? msg : e.getClass().getSimpleName() + ": " + e;
        }
    }
}
