package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.ai.AiConfigService;
import fan.summer.zhiflow.ai.adapter.AiToolCallback;
import fan.summer.zhiflow.ai.adapter.MessageMapper;
import fan.summer.zhiflow.ai.spring.AiSpringContext;
import fan.summer.zhiflow.ai.ToolExecutor;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiServiceException;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiStreamCallback;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolCall;
import fan.summer.zhiflow.api.ai.ChatBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local-mode {@link ChatBackend} backed by Ollama via Spring AI's
 * {@code OllamaChatModel}. Replaces the entire custom GGUF/JNI/worker stack.
 *
 * <p>The model is served by an external {@code ollama serve} process; this class
 * only talks to its HTTP API (through Spring AI). "Loading a model" is now
 * selecting an Ollama tag ({@code qwen3:4b}); there is no in-process weight file.
 *
 * <p>Tool loop: driven manually (same shape as {@code SpringAiCloudBackend}) so
 * {@code AiStreamCallback.onToolCall/onToolResult} events still fire on the FX
 * thread for UI feedback. Tools are pulled from {@link AiServiceProvider} and
 * wrapped as {@link AiToolCallback}.
 *
 * <p>Phase 1: thinking surfacing is NOT wired (Task 8 spike fallback — Ollama
 * unavailable on the build host to confirm the streaming thinking-metadata key).
 * {@code AiStreamCallback.onThinking} is never invoked here; see the plan's Task 8
 * outcome. TODO: surface thinking once the metadata key is confirmed.
 */
public final class OllamaLocalBackend implements ChatBackend {

    private static final Logger log = LoggerFactory.getLogger(OllamaLocalBackend.class);
    private static final int MAX_TOOL_ROUNDS = 8;

    private final AtomicBoolean generating = new AtomicBoolean(false);
    private volatile String ollamaModelTag;
    private volatile ChatModel chatModel;

    public OllamaLocalBackend() {
        this.ollamaModelTag = AiConfigService.getAiOllamaModel();
        // The ChatModel bean is built from H2 config at context start; look it up lazily.
    }

    // ── ChatBackend lifecycle ────────────────────────────────────────

    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        // In the Ollama world, "load model" = "select the tag". The path argument
        // is honoured only if the user dropped a model file (we read its name as
        // a tag); otherwise the H2-configured tag wins.
        String configured = AiConfigService.getAiOllamaModel();
        if (configured != null && !configured.isBlank()) {
            this.ollamaModelTag = configured;
        } else if (modelPath != null) {
            this.ollamaModelTag = modelPath.getFileName().toString();
        }
        log.info("Ollama local backend: model tag = {}", ollamaModelTag);

        // Resolve the ChatModel bean (built by ChatModelConfig from the Ollama base URL).
        try {
            this.chatModel = AiSpringContext.getBean("ollamaChatModel", ChatModel.class);
        } catch (Exception e) {
            throw new AiServiceException("Ollama ChatModel bean unavailable; is the AI Spring context started? " + e.getMessage(), e);
        }
        if (!probeReachable(AiConfigService.getAiOllamaBaseUrl())) {
            log.warn("Ollama server not reachable at {} — chat will fail at call time. "
                     + "Run `ollama serve` and `ollama pull {}`.",
                     AiConfigService.getAiOllamaBaseUrl(), ollamaModelTag);
        }
    }

    @Override public void unloadModel() {
        // Nothing to release — the model lives in the Ollama server.
        chatModel = null;
    }

    @Override public boolean isReady() {
        return chatModel != null && ollamaModelTag != null && !ollamaModelTag.isBlank();
    }

    @Override
    public Optional<String> getModelName() {
        return Optional.ofNullable(ollamaModelTag);
    }

    @Override public long getMemoryUsage() {
        // Ollama owns the weights; the JVM's heap usage is not meaningful here.
        return -1;
    }

    @Override public boolean isNativeAvailable() {
        // There is no JNI surface anymore. Return true if the Ollama server is up —
        // this drives the "degraded banner" the AiChatPlugin shows when false.
        return probeReachable(AiConfigService.getAiOllamaBaseUrl());
    }

    // ── Chat ──────────────────────────────────────────────────────────

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, AiConfigServiceHeadless.getAiTemperature(), AiConfigServiceHeadless.getAiTopP(),
             AiConfigServiceHeadless.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) throw new AiServiceException("Ollama backend not ready (model=" + ollamaModelTag + ")");
        if (!generating.compareAndSet(false, true)) throw new AiServiceException("Generation already in progress");

        Thread.ofVirtual().start(() -> {
            try {
                runToolLoop(history, callback);
            } catch (Exception e) {
                log.error("Ollama chat failed", e);
                callback.onError(e);
            } finally {
                generating.set(false);
            }
        });
    }

    @Override public void cancelGeneration() {
        // Best-effort: Spring AI 2.0 streaming Flux can be cancelled via downstream dispose,
        // but the manual loop here doesn't hold the Disposable. Logged for parity.
        log.debug("cancelGeneration() requested; mid-stream abort not wired in Phase 1");
    }

    @Override public boolean isGenerating() { return generating.get(); }

    // ── Tool loop ─────────────────────────────────────────────────────

    private void runToolLoop(List<AiChatMessage> history, AiStreamCallback callback) {
        buildToolCallbacks();   // tools are resolved by Spring AI-side wiring; kept for parity/logging
        String systemPrompt = currentSystemPrompt();

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            List<Message> msgs = new ArrayList<>(history.size() + 1);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                msgs.add(new SystemMessage(systemPrompt));
            }
            for (AiChatMessage m : history) msgs.add(MessageMapper.toSpringAi(m));

            Prompt prompt = new Prompt(msgs);

            // Stream this round; accumulate the assistant text + capture the last chunk
            // (tool calls arrive in the terminal chunk once the round completes).
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
                    callback.onToken(delta);
                }
                // Task 8 fallback: thinking content is NOT surfaced in Phase 1.
            }).blockLast();   // virtual thread, blocking is fine

            AssistantMessage finalAm = lastMsg.get();
            List<AiToolCall> calls = finalAm != null ? MessageMapper.extractToolCalls(finalAm) : List.of();

            if (calls.isEmpty()) {
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
            history.add(AiChatMessage.assistantWithTools(accumulated.toString(), calls));
            ToolExecutor.executeAndFeed(calls, history, callback);
        }
    }

    private List<ToolCallback> buildToolCallbacks() {
        List<AiTool> tools = AiServiceProvider.getTools();
        List<ToolCallback> cbs = new ArrayList<>(tools.size());
        for (AiTool t : tools) cbs.add(new AiToolCallback(t));
        return cbs;
    }

    private static String currentSystemPrompt() {
        try { return AiConfigServiceHeadless.getAiSystemPrompt(); }
        catch (Throwable t) { return null; }
    }

    // ── Connection probe (also used by the connection test) ───────────

    /**
     * Pings {@code {base}/api/tags} to check whether an Ollama server is listening.
     * Public so the unit test can drive a fake server.
     */
    public static boolean probeReachable(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(baseUrl) + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
