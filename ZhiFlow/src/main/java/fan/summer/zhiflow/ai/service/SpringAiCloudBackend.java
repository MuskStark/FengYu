package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.ai.adapter.MessageMapper;
import fan.summer.zhiflow.ai.spring.AiSpringContext;
import fan.summer.zhiflow.ai.ToolExecutor;
import fan.summer.zhiflow.ai.util.JsonHelper;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiServiceException;
import fan.summer.zhiflow.api.ai.AiStreamCallback;
import fan.summer.zhiflow.api.ai.AiToolCall;
import fan.summer.zhiflow.api.ai.ChatBackend;
import fan.summer.zhiflow.ui.setting.ZhiFlowSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

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
 * construction time. Each {@code chat()} call streams via
 * {@link ChatModel#stream(Prompt)} and drives the multi-round tool loop manually,
 * preserving {@link AiStreamCallback#onToolCall} / {@code onToolResult} events on the
 * FX thread for UI feedback (same contract as the old backend).
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

    // ── Production constructors (look up the ChatModel bean) ──────────

    public static SpringAiCloudBackend openAi(String endpoint, String apiKey, String modelName) {
        ChatModel model = AiSpringContext.getBean("openAiChatModel", ChatModel.class);
        return new SpringAiCloudBackend(Provider.OPENAI, endpoint, apiKey, modelName, model);
    }

    public static SpringAiCloudBackend anthropic(String endpoint, String apiKey, String modelName) {
        ChatModel model = AiSpringContext.getBean("anthropicChatModel", ChatModel.class);
        return new SpringAiCloudBackend(Provider.ANTHROPIC, endpoint, apiKey, modelName, model);
    }

    /** DeepSeek uses an OpenAI-compatible API; the bean reuses the OpenAI model path. */
    public static SpringAiCloudBackend deepSeek(String endpoint, String apiKey, String modelName) {
        ChatModel model = AiSpringContext.getBean("deepSeekChatModel", ChatModel.class);
        return new SpringAiCloudBackend(Provider.DEEPSEEK, endpoint, apiKey, modelName, model);
    }

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
    }

    // ── Public accessors (preserved for SynchronousChatHelper + Settings UI) ──

    public Provider provider()           { return provider; }
    public String getEndpoint()          { return endpoint; }
    public String getApiKey()            { return apiKey; }
    public String getModelNameInternal() { return modelName; }

    // ── ChatBackend ───────────────────────────────────────────────────

    @Override public void loadModel(Path modelPath) throws AiServiceException {
        throw new AiServiceException("Local model loading not supported for cloud backend");
    }

    @Override public void unloadModel() { /* model bean is reused; nothing to release */ }

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
        chat(history, ZhiFlowSettingUi.getAiTemperature(), ZhiFlowSettingUi.getAiTopP(),
             ZhiFlowSettingUi.getAiMaxTokens(), callback);
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
                Platform.runLater(() -> callback.onError(e));
            } finally {
                generating.set(false);
            }
        });
    }

    @Override public void cancelGeneration() {
        log.debug("cancelGeneration() requested; mid-stream abort not wired in Phase 1");
    }

    // ── Tool loop ─────────────────────────────────────────────────────

    private void runToolLoop(List<AiChatMessage> history, AiStreamCallback callback) {
        String systemPrompt = currentSystemPrompt();

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            List<Message> msgs = new ArrayList<>(history.size() + 1);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                msgs.add(new SystemMessage(systemPrompt));
            }
            for (AiChatMessage m : history) msgs.add(MessageMapper.toSpringAi(m));
            Prompt prompt = new Prompt(msgs);

            StringBuilder accumulated = new StringBuilder();
            AtomicReference<AssistantMessage> lastMsg = new AtomicReference<>();

            chatModel.stream(prompt).doOnNext(resp -> {
                if (resp == null || resp.getResult() == null) return;
                AssistantMessage am = resp.getResult().getOutput();
                if (am == null) return;
                lastMsg.set(am);
                String delta = am.getText();
                if (delta != null && !delta.isEmpty()) {
                    accumulated.append(delta);
                    final String token = delta;
                    Platform.runLater(() -> callback.onToken(token));
                }
            }).blockLast();

            AssistantMessage finalAm = lastMsg.get();
            List<AiToolCall> calls = finalAm != null ? MessageMapper.extractToolCalls(finalAm) : List.of();

            if (calls.isEmpty()) {
                String finalText = accumulated.toString();
                if (!finalText.isBlank()) history.add(AiChatMessage.assistant(finalText));
                int tokens = Math.max(1, finalText.length() / 4);
                Platform.runLater(() -> callback.onComplete(finalText, tokens, 0));
                return;
            }
            if (round == MAX_TOOL_ROUNDS) {
                String warn = "Reached MAX_TOOL_ROUNDS (" + MAX_TOOL_ROUNDS + ")";
                log.warn(warn);
                Platform.runLater(() -> callback.onComplete(warn, 0, 0));
                return;
            }
            history.add(AiChatMessage.assistantWithTools(accumulated.toString(), calls));
            ToolExecutor.executeAndFeed(calls, history, callback);
        }
    }

    private static String currentSystemPrompt() {
        try { return ZhiFlowSettingUi.getAiSystemPrompt(); }
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
