package fan.summer.ai.service;

import fan.summer.ai.inference.LlamaRunner;
import fan.summer.ai.model.ChatTemplate;
import fan.summer.ai.model.GGUFReader;
import fan.summer.ai.nativejni.GenerateCallback;
import fan.summer.ai.nativejni.GenerateParams;
import fan.summer.ai.nativejni.ModelParams;
import fan.summer.ai.nativejni.NativeWorkerClient;
import fan.summer.ai.tools.FunctionGemmaAdapter;
import fan.summer.ai.tools.ToolCallParser;
import fan.summer.ai.tools.ToolExecutor;
import fan.summer.ai.tools.ToolSchemaBuilder;
import fan.summer.api.ai.*;
import fan.summer.ui.setting.SwissKitJSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link AiService} implementation for local AI models (GGUF format).
 * Uses a child JVM process ({@link NativeWorkerClient}) for native inference
 * to prevent native crashes from killing the main application.
 *
 * @see AiService
 * @see NativeWorkerClient
 */
public class AiServiceImpl implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    private enum Backend { NATIVE, JAVA }

    private final Backend backend;
    private LlamaRunner javaRunner;
    private NativeWorkerClient workerClient;
    private ChatTemplate nativeChatTemplate;
    private volatile String loadedModelPath;
    private FunctionGemmaAdapter functionGemmaAdapter;
    private boolean isFunctionGemma;

    /**
     * Creates a new AI service with the specified backend.
     *
     * @param useNative if true, use llama.cpp JNI for inference;
     *                  if false, use the pure Java inference engine.
     *                  The caller must ensure the native library is loaded beforehand
     *                  when useNative is true.
     */
    public AiServiceImpl(boolean useNative) {
        if (useNative) {
            backend = Backend.NATIVE;
            log.info("AI backend: native (llama.cpp JNI, out-of-process)");
        } else {
            backend = Backend.JAVA;
            log.info("AI backend: pure Java");
        }
    }

    private void detectModelType(String modelPath) {
        isFunctionGemma = false;
        functionGemmaAdapter = null;
        if (backend != Backend.NATIVE && backend != Backend.JAVA) return;
        String name = Path.of(modelPath).getFileName().toString().toLowerCase();
        isFunctionGemma = name.contains("functiongemma");
        if (isFunctionGemma) {
            functionGemmaAdapter = new FunctionGemmaAdapter();
            log.info("FunctionGemma detected — using native tool calling protocol");
        }
    }

    // ── Model management ──────────────────────────────────────

    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        try {
            loadedModelPath = modelPath.toString();
            detectModelType(modelPath.toString());
            log.info("Loading AI model [{}]: {}", backend, modelPath);

            if (backend == Backend.NATIVE) {
                if (workerClient != null) workerClient.close();
                workerClient = new NativeWorkerClient();

                ModelParams params = new ModelParams()
                    .modelPath(modelPath.toString())
                    .ctxLength(4096)
                    .threads(Runtime.getRuntime().availableProcessors());
                workerClient.spawn();
                workerClient.loadModel(params);

                try {
                    Map<String, Object> meta = GGUFReader.loadMetadata(modelPath);
                    String rawTemplate = meta.get("tokenizer.chat_template") instanceof String s ? s : "";
                    nativeChatTemplate = new ChatTemplate(rawTemplate);
                    log.info("Native chat template: {} (raw len={})",
                             nativeChatTemplate.getType(), rawTemplate.length());
                } catch (Exception e) {
                    log.warn("Failed to read chat template metadata, defaulting to ChatML: {}", e.getMessage());
                    nativeChatTemplate = new ChatTemplate("");
                }
            } else {
                if (javaRunner == null) javaRunner = new LlamaRunner();
                javaRunner.load(modelPath.toString());
            }

            log.info("AI model loaded successfully [{}]", backend);
        } catch (Exception e) {
            throw new AiServiceException("Failed to load model: " + e.getMessage(), e);
        }
    }

    @Override
    public void unloadModel() {
        if (backend == Backend.NATIVE && workerClient != null) {
            workerClient.close();
            workerClient = null;
            nativeChatTemplate = null;
        } else if (javaRunner != null) {
            javaRunner.unload();
        }
        isFunctionGemma = false;
        functionGemmaAdapter = null;
        loadedModelPath = null;
    }

    @Override public boolean isReady() {
        if (backend == Backend.NATIVE) return workerClient != null && workerClient.isAlive();
        return javaRunner != null && javaRunner.isReady();
    }

    @Override
    public Optional<String> getModelName() {
        if (backend == Backend.NATIVE) {
            return Optional.ofNullable(loadedModelPath)
                .map(p -> p.substring(p.lastIndexOf('/') + 1));
        }
        return Optional.ofNullable(javaRunner != null ? javaRunner.getModelName() : null);
    }

    @Override public long getMemoryUsage() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    @Override
    public boolean isNativeAvailable() {
        return backend == Backend.NATIVE && workerClient != null && !workerClient.shouldFallback();
    }

    // ── Chat ──────────────────────────────────────────────────

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, SwissKitJSettingUi.getAiTemperature(), SwissKitJSettingUi.getAiTopP(),
             SwissKitJSettingUi.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) {
            callback.onError(new AiServiceException("No model loaded"));
            return;
        }

        if (backend == Backend.NATIVE && workerClient != null && workerClient.shouldFallback()) {
            log.warn("Native worker crashed repeatedly — falling back to Java backend");
            ensureJavaRunnerLoaded();
            chatJava(history, temperature, topP, maxTokens, callback);
            return;
        }

        if (backend == Backend.NATIVE) {
            chatNative(history, temperature, topP, maxTokens, callback);
        } else {
            chatJava(history, temperature, topP, maxTokens, callback);
        }
    }

    // ── Native backend chat ───────────────────────────────────

    private void chatNative(List<AiChatMessage> history, float temperature, float topP,
                            int maxTokens, AiStreamCallback callback) {
        if (isFunctionGemma) {
            chatFunctionGemmaNative(history, temperature, topP, maxTokens, callback);
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                String systemPrompt = buildSystemPrompt();
                String prompt = buildNativePrompt(history, systemPrompt);
                AtomicBoolean hadToolCall = new AtomicBoolean(false);
                generateNativeWithToolLoop(prompt, temperature, topP, maxTokens,
                                           history, callback, 0, hadToolCall);
            } catch (Exception e) {
                log.error("Native generation error", e);
                Platform.runLater(() -> callback.onError(e));
            }
        });
    }

    private void generateNativeWithToolLoop(String prompt, float temperature, float topP,
                                             int maxTokens, List<AiChatMessage> history,
                                             AiStreamCallback callback, int round,
                                             AtomicBoolean hadToolCall) {
        if (round >= MAX_TOOL_ROUNDS || workerClient == null || !workerClient.isAlive()) return;

        GenerateParams genParams = new GenerateParams()
            .temperature(temperature).topP(topP).maxTokens(maxTokens);

        TokenBatcher batcher = new TokenBatcher(callback);

        workerClient.generate(prompt, genParams, new GenerateCallback() {
            @Override
            public boolean onToken(String tokenText) {
                batcher.add(tokenText);
                return true;
            }

            @Override
            public void onDone(String fullText, int tokenCount, double tokPerSec) {
                batcher.close();
                List<AiToolCall> toolCalls = ToolCallParser.parse(fullText);
                if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
                    hadToolCall.set(true);
                    history.add(AiChatMessage.assistantWithTools("", toolCalls));
                    ToolExecutor.executeAndFeed(toolCalls, history, callback);
                    String newPrompt = buildNativePrompt(history, buildSystemPrompt());
                    generateNativeWithToolLoop(newPrompt, temperature, topP, maxTokens,
                                               history, callback, round + 1, hadToolCall);
                } else {
                    String clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(fullText) : fullText;
                    Platform.runLater(() -> callback.onComplete(clean, tokenCount, tokPerSec));
                }
            }

            @Override
            public void onError(String message) {
                batcher.close();
                Platform.runLater(() -> callback.onError(new RuntimeException(message)));
            }
        });
    }

    // ── FunctionGemma single-turn tool calling ────────────────

    private void chatFunctionGemmaNative(List<AiChatMessage> history, float temperature,
                                          float topP, int maxTokens, AiStreamCallback callback) {
        Thread.ofVirtual().start(() -> {
            try {
                String toolDecls = functionGemmaAdapter.buildToolDeclarations(AiServiceProvider.getTools());
                String prompt = functionGemmaAdapter.buildPrompt(history, toolDecls);

                GenerateParams genParams = new GenerateParams()
                    .temperature(temperature).topP(topP).maxTokens(maxTokens);

                workerClient.generate(prompt, genParams, new GenerateCallback() {
                    @Override
                    public boolean onToken(String tokenText) {
                        Platform.runLater(() -> callback.onToken(tokenText));
                        return true;
                    }

                    @Override
                    public void onDone(String fullText, int tokenCount, double tokPerSec) {
                        if (functionGemmaAdapter.containsToolCall(fullText)) {
                            List<AiToolCall> toolCalls = functionGemmaAdapter.parseToolCalls(fullText);
                            if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
                                AiToolCall tc = toolCalls.get(0);
                                Platform.runLater(() -> callback.onToolCall(tc));

                                AiToolResult result = ToolExecutor.execute(tc.name(), tc.arguments());
                                Platform.runLater(() -> callback.onToolResult(tc.id(), result));

                                history.add(AiChatMessage.assistantWithTools(
                                    functionGemmaAdapter.stripToolCalls(fullText), toolCalls));
                                history.add(AiChatMessage.toolResult(tc.id(), tc.name(), result.output()));

                                String newPrompt = functionGemmaAdapter.buildPrompt(history, toolDecls);
                                generateFinalAnswer(newPrompt, temperature, topP, maxTokens, callback);
                                return;
                            }
                        }

                        Platform.runLater(() -> callback.onComplete(fullText, tokenCount, tokPerSec));
                    }

                    @Override
                    public void onError(String message) {
                        Platform.runLater(() -> callback.onError(new RuntimeException(message)));
                    }
                });
            } catch (Exception e) {
                log.error("FunctionGemma generation error", e);
                Platform.runLater(() -> callback.onError(e));
            }
        });
    }

    private void generateFinalAnswer(String prompt, float temperature, float topP,
                                      int maxTokens, AiStreamCallback callback) {
        GenerateParams genParams = new GenerateParams()
            .temperature(temperature).topP(topP).maxTokens(maxTokens);

        workerClient.generate(prompt, genParams, new GenerateCallback() {
            @Override
            public boolean onToken(String tokenText) {
                Platform.runLater(() -> callback.onToken(tokenText));
                return true;
            }

            @Override
            public void onDone(String fullText, int tokenCount, double tokPerSec) {
                Platform.runLater(() -> callback.onComplete(fullText, tokenCount, tokPerSec));
            }

            @Override
            public void onError(String message) {
                Platform.runLater(() -> callback.onError(new RuntimeException(message)));
            }
        });
    }

    // ── Java backend chat ─────────────────────────────────────

    private void chatJava(List<AiChatMessage> history, float temperature, float topP,
                          int maxTokens, AiStreamCallback callback) {
        if (javaRunner == null || !javaRunner.isReady()) {
            callback.onError(new AiServiceException("Java backend not ready"));
            return;
        }
        if (javaRunner.isGenerating()) {
            callback.onError(new AiServiceException("Generation already in progress"));
            return;
        }

        Thread.ofVirtual().start(() -> {
            try {
                String systemPrompt = buildSystemPrompt();
                javaRunner.resetCache();
                String prompt = javaRunner.buildPrompt(history, systemPrompt);

                AtomicBoolean hadToolCall = new AtomicBoolean(false);
                generateJavaWithToolLoop(prompt, temperature, topP, maxTokens,
                                         history, callback, 0, hadToolCall);
            } catch (Exception e) {
                log.error("Java generation error", e);
                Platform.runLater(() -> callback.onError(e));
            }
        });
    }

    private void generateJavaWithToolLoop(String prompt, float temperature, float topP, int maxTokens,
                                           List<AiChatMessage> history, AiStreamCallback callback,
                                           int round, AtomicBoolean hadToolCall) {
        if (round >= MAX_TOOL_ROUNDS) return;

        StringBuilder response = new StringBuilder();
        TokenBatcher batcher = new TokenBatcher(callback);

        javaRunner.generate(prompt, temperature, topP, maxTokens, new LlamaRunner.TokenCallback() {
            @Override
            public void onToken(String fragment) {
                response.append(fragment);
                batcher.add(fragment);
            }

            @Override
            public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
                batcher.close();
                List<AiToolCall> toolCalls = ToolCallParser.parse(fullResponse);
                if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
                    hadToolCall.set(true);
                    history.add(AiChatMessage.assistantWithTools("", toolCalls));
                    ToolExecutor.executeAndFeed(toolCalls, history, callback);
                    try {
                        String newPrompt = javaRunner.buildPrompt(history, buildSystemPrompt());
                        javaRunner.resetCache();
                        generateJavaWithToolLoop(newPrompt, temperature, topP, maxTokens,
                                                 history, callback, round + 1, hadToolCall);
                    } catch (Exception e) {
                        Platform.runLater(() -> callback.onError(e));
                    }
                } else {
                    String clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(fullResponse) : fullResponse;
                    Platform.runLater(() -> callback.onComplete(clean, tokensGenerated, tokensPerSecond));
                }
            }
        });
    }

    // ── Prompt building ───────────────────────────────────────

    private String buildSystemPrompt() {
        if (isFunctionGemma) return "";
        String base = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = ToolSchemaBuilder.buildPromptDefinitions(AiServiceProvider.getTools());
        if (toolDefs.isEmpty()) return base;
        return base + "\n\n" + toolDefs;
    }

    private String buildNativePrompt(List<AiChatMessage> history, String systemPrompt) {
        ChatTemplate template = nativeChatTemplate != null ? nativeChatTemplate : new ChatTemplate("");
        return template.buildPrompt(history, systemPrompt);
    }

    // ── Lifecycle ─────────────────────────────────────────────

    @Override public void cancelGeneration() {
        if (javaRunner != null) javaRunner.cancel();
    }

    @Override public boolean isGenerating() {
        if (backend == Backend.NATIVE) return false;
        return javaRunner != null && javaRunner.isGenerating();
    }

    // ── Fallback ──────────────────────────────────────────────

    private void ensureJavaRunnerLoaded() {
        if (javaRunner == null) javaRunner = new LlamaRunner();
        if (!javaRunner.isReady() && loadedModelPath != null) {
            try {
                javaRunner.load(loadedModelPath);
            } catch (Exception e) {
                log.error("Failed to load model into Java fallback runner: {}", e.getMessage());
            }
        }
    }

    // ── Tool management ───────────────────────────────────────

    @Override public void registerTool(AiTool tool) { AiServiceProvider.registerTool(tool); }
    @Override public void unregisterTool(String toolName) { AiServiceProvider.unregisterTool(toolName); }
    @Override public List<AiTool> getTools() { return AiServiceProvider.getTools(); }

    // ── Token batching ────────────────────────────────────────

    /**
     * Batches token text to avoid flooding the FX thread with individual
     * {@code Platform.runLater} calls during high-speed generation.
     * Tokens are accumulated in a StringBuffer and flushed every ~50ms or
     * when the generation ends, whichever comes first.
     */
    static final class TokenBatcher {
        private final AiStreamCallback callback;
        private final StringBuilder buffer = new StringBuilder();
        private final AtomicReference<javafx.animation.Animation> flushTimer = new AtomicReference<>();
        private volatile boolean active = true;

        TokenBatcher(AiStreamCallback callback) {
            this.callback = callback;
        }

        /** Appends a token and schedules a flush if none is pending. */
        void add(String token) {
            synchronized (buffer) {
                buffer.append(token);
            }
            scheduleFlush();
        }

        /** Flushes any remaining buffered tokens immediately. */
        void flush() {
            String batch;
            synchronized (buffer) {
                if (buffer.isEmpty()) return;
                batch = buffer.toString();
                buffer.setLength(0);
            }
            final String text = batch;
            Platform.runLater(() -> callback.onToken(text));
        }

        /** Disables further batching. Call when generation ends. */
        void close() {
            active = false;
            javafx.animation.Animation timer = flushTimer.getAndSet(null);
            if (timer != null) timer.stop();
            flush();
        }

        private void scheduleFlush() {
            if (!active) return;
            javafx.animation.Animation existing = flushTimer.get();
            if (existing != null) return; // a flush is already scheduled
            javafx.animation.PauseTransition pause =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(50));
            pause.setOnFinished(e -> {
                flushTimer.compareAndSet(pause, null);
                flush();
            });
            if (flushTimer.compareAndSet(null, pause)) {
                pause.play();
            }
        }
    }
}
