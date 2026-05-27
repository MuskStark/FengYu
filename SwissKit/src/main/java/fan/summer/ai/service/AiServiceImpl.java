package fan.summer.ai.service;

import fan.summer.ai.inference.LlamaRunner;
import fan.summer.ai.inference.StopDetector;
import fan.summer.ai.model.ChatTemplate;
import fan.summer.ai.model.GGUFReader;
import fan.summer.ai.nativejni.GenerateCallback;
import fan.summer.ai.nativejni.GenerateParams;
import fan.summer.ai.nativejni.LlamaContext;
import fan.summer.ai.nativejni.ModelParams;
import fan.summer.ai.nativejni.NativeLoader;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AiService} implementation for local AI models (GGUF format).
 * Supports two backends: a native llama.cpp JNI backend when native libraries
 * are available, or a pure Java fallback ({@link LlamaRunner}) when they are not.
 *
 * <p>Both backends support streaming, tool calling, and multi-round conversations
 * with automatic stop-sequence detection (native) or fixed-length generation (Java).
 *
 * <p>To use, call {@link #loadModel(Path)} with the path to a GGUF model file,
 * then invoke {@link #chat(List, AiStreamCallback)}.
 *
 * @see AiService
 * @see LlamaRunner
 */
public class AiServiceImpl implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    private enum Backend { NATIVE, JAVA }

    private final Backend backend;
    private final LlamaRunner javaRunner;
    private LlamaContext nativeContext;
    private ChatTemplate nativeChatTemplate;
    private volatile String loadedModelPath;

    /**
     * Constructs an {@code AiServiceImpl}, automatically selecting the native
     * JNI backend if native libraries are loaded, otherwise falling back to
     * the pure Java {@link LlamaRunner}.
     *
     * @see NativeLoader#isLoaded()
     */
    public AiServiceImpl() {
        NativeLoader.load();
        if (NativeLoader.isLoaded()) {
            backend = Backend.NATIVE;
            javaRunner = null;
            log.info("AI backend: native (llama.cpp JNI)");
        } else {
            backend = Backend.JAVA;
            javaRunner = new LlamaRunner();
            log.info("AI backend: pure Java (fallback)");
        }
    }

    // ── Model management ──────────────────────────────────────

    /**
     * Loads a GGUF model from the given path, initializing either the native
     * llama.cpp context or the Java runner depending on the selected backend.
     *
     * @param modelPath the path to the GGUF model file
     * @throws AiServiceException if loading fails for any reason
     */
    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        try {
            loadedModelPath = modelPath.toString();
            log.info("Loading AI model [{}]: {}", backend, modelPath);

            if (backend == Backend.NATIVE) {
                if (nativeContext != null) nativeContext.close();
                ModelParams params = new ModelParams()
                    .modelPath(modelPath.toString())
                    .ctxLength(4096)
                    .threads(Runtime.getRuntime().availableProcessors());
                nativeContext = new LlamaContext(params);

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
                javaRunner.load(modelPath.toString());
            }

            log.info("AI model loaded successfully [{}]", backend);
        } catch (Exception e) {
            throw new AiServiceException("Failed to load model: " + e.getMessage(), e);
        }
    }

    /**
     * Unloads the currently loaded model, releasing resources.
     * Closes the native context if using the native backend, or calls
     * {@code unload()} on the Java runner.
     */
    @Override
    public void unloadModel() {
        if (backend == Backend.NATIVE && nativeContext != null) {
            nativeContext.close();
            nativeContext = null;
            nativeChatTemplate = null;
        } else if (javaRunner != null) {
            javaRunner.unload();
        }
        loadedModelPath = null;
    }

    /**
     * Returns {@code true} if a model is ready for inference.
     * For the native backend, checks that {@code nativeContext} is non-null;
     * for the Java backend, delegates to {@link LlamaRunner#isReady()}.
     *
     * @return true if a model is loaded and ready, false otherwise
     */
    @Override public boolean isReady() {
        if (backend == Backend.NATIVE) return nativeContext != null;
        return javaRunner != null && javaRunner.isReady();
    }

    /**
     * Returns the name of the currently loaded model, derived from the file path
     * for the native backend, or from the runner for the Java backend.
     *
     * @return an {@link Optional} containing the model name, or empty if no model is loaded
     */
    @Override
    public Optional<String> getModelName() {
        if (backend == Backend.NATIVE) {
            return Optional.ofNullable(loadedModelPath)
                .map(p -> p.substring(p.lastIndexOf('/') + 1));
        }
        return Optional.ofNullable(javaRunner != null ? javaRunner.getModelName() : null);
    }

    /**
     * Returns the approximate JVM memory usage (total minus free memory).
     *
     * @return estimated memory usage in bytes, or -1 if not applicable
     */
    @Override public long getMemoryUsage() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    // ── Chat ──────────────────────────────────────────────────

    /**
     * Initiates a chat session using the default settings from
     * {@link SwissKitJSettingUi#getAiTemperature()}, {@link SwissKitJSettingUi#getAiTopP()},
     * and {@link SwissKitJSettingUi#getAiMaxTokens()}.
     *
     * @param history  the conversation history
     * @param callback the stream callback for tokens and completion events
     * @throws AiServiceException if the service is not ready or generation fails
     */
    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, SwissKitJSettingUi.getAiTemperature(), SwissKitJSettingUi.getAiTopP(),
             SwissKitJSettingUi.getAiMaxTokens(), callback);
    }

    /**
     * Initiates a chat session with explicit generation parameters.
     * Dispatches to either the native or Java backend depending on which is active.
     *
     * @param history    the conversation history
     * @param temperature sampling temperature (0.0 - 2.0)
     * @param topP       nucleus sampling top-p value (0.0 - 1.0)
     * @param maxTokens  maximum tokens to generate
     * @param callback   the stream callback for tokens and completion events
     * @throws AiServiceException if the service is not ready or generation fails
     */
    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) {
            callback.onError(new AiServiceException("No model loaded"));
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

    /**
     * Runs generation in the native llama.cpp backend with automatic stop-sequence
     * detection. Supports a multi-round tool-call loop up to {@code MAX_TOOL_ROUNDS}.
     */
    private void generateNativeWithToolLoop(String prompt, float temperature, float topP,
                                             int maxTokens, List<AiChatMessage> history,
                                             AiStreamCallback callback, int round,
                                             AtomicBoolean hadToolCall) {
        if (round >= MAX_TOOL_ROUNDS || nativeContext == null) return;

        GenerateParams genParams = new GenerateParams()
            .temperature(temperature).topP(topP).maxTokens(maxTokens);

        StringBuilder response = new StringBuilder();
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicInteger tokenCount = new AtomicInteger(0);
        long[] firstTokenNanos = {0L};
        long genStartNanos = System.nanoTime();

        nativeContext.generate(prompt, genParams, new GenerateCallback() {
            @Override
            public boolean onToken(String tokenText) {
                if (stopped.get()) return false;
                if (firstTokenNanos[0] == 0L) firstTokenNanos[0] = System.nanoTime();
                tokenCount.incrementAndGet();

                int prevLen = response.length();
                response.append(tokenText);
                int stopIdx = StopDetector.findStop(response);
                if (stopIdx >= 0) {
                    response.setLength(stopIdx);
                    int safeLen = stopIdx - prevLen;
                    if (safeLen > 0) {
                        String safe = tokenText.substring(0, Math.min(tokenText.length(), safeLen));
                        Platform.runLater(() -> callback.onToken(safe));
                    }
                    stopped.set(true);
                    return false;
                }
                Platform.runLater(() -> callback.onToken(tokenText));
                return true;
            }

            @Override
            public void onDone(String fullText) {
                String finalText = response.toString();
                int n = tokenCount.get();
                long baseNanos = firstTokenNanos[0] != 0L ? firstTokenNanos[0] : genStartNanos;
                long elapsedMs = (System.nanoTime() - baseNanos) / 1_000_000;
                double tokPerSec = (n > 0 && elapsedMs > 0) ? n * 1000.0 / elapsedMs : 0;

                List<AiToolCall> toolCalls = ToolCallParser.parse(finalText);
                if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
                    hadToolCall.set(true);
                    history.add(AiChatMessage.assistantWithTools("", toolCalls));
                    ToolExecutor.executeAndFeed(toolCalls, history, callback);
                    String newPrompt = buildNativePrompt(history, buildSystemPrompt());
                    generateNativeWithToolLoop(newPrompt, temperature, topP, maxTokens,
                                               history, callback, round + 1, hadToolCall);
                } else {
                    String clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(finalText) : finalText;
                    Platform.runLater(() -> callback.onComplete(clean, n, tokPerSec));
                }
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

    /**
     * Runs generation in the pure Java backend with a multi-round tool-call loop
     * up to {@code MAX_TOOL_ROUNDS}. Resets the runner's cache between rounds.
     */
    private void generateJavaWithToolLoop(String prompt, float temperature, float topP, int maxTokens,
                                           List<AiChatMessage> history, AiStreamCallback callback,
                                           int round, AtomicBoolean hadToolCall) {
        if (round >= MAX_TOOL_ROUNDS) return;

        StringBuilder response = new StringBuilder();
        javaRunner.generate(prompt, temperature, topP, maxTokens, new LlamaRunner.TokenCallback() {
            @Override
            public void onToken(String fragment) {
                response.append(fragment);
                Platform.runLater(() -> callback.onToken(fragment));
            }

            @Override
            public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
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

    /**
     * Builds the system prompt by combining the base prompt from settings
     * with the tool definitions from {@link ToolSchemaBuilder}.
     *
     * @return the complete system prompt string, or just the base prompt if no tools are registered
     */
    private String buildSystemPrompt() {
        String base = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = ToolSchemaBuilder.buildPromptDefinitions(AiServiceProvider.getTools());
        if (toolDefs.isEmpty()) return base;
        return base + "\n\n" + toolDefs;
    }

    /**
     * Builds a prompt for the native backend using the loaded chat template.
     *
     * @param history      the conversation history
     * @param systemPrompt the system prompt to include
     * @return the formatted prompt string
     */
    private String buildNativePrompt(List<AiChatMessage> history, String systemPrompt) {
        ChatTemplate template = nativeChatTemplate != null ? nativeChatTemplate : new ChatTemplate("");
        return template.buildPrompt(history, systemPrompt);
    }

    // ── Lifecycle ─────────────────────────────────────────────

    /**
     * Cancels any in-progress generation. For the Java backend, calls
     * {@link LlamaRunner#cancel()}; the native backend is non-interruptible.
     */
    @Override public void cancelGeneration() {
        if (javaRunner != null) javaRunner.cancel();
    }

    /**
     * Returns {@code true} if generation is currently in progress in the Java backend.
     * For the native backend, always returns {@code false} (non-interruptible).
     *
     * @return true if generating, false otherwise
     */
    @Override public boolean isGenerating() {
        if (backend == Backend.NATIVE) return false;
        return javaRunner != null && javaRunner.isGenerating();
    }

    // ── Tool management (delegate to AiServiceProvider) ────────

    @Override public void registerTool(AiTool tool) { AiServiceProvider.registerTool(tool); }
    @Override public void unregisterTool(String toolName) { AiServiceProvider.unregisterTool(toolName); }
    @Override public List<AiTool> getTools() { return AiServiceProvider.getTools(); }
}
