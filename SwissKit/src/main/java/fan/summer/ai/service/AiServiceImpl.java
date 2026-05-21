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
import fan.summer.ai.tools.ToolRegistry;
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
 * AiService implementation with dual-backend support:
 * <ol>
 *   <li><b>Native (JNI/llama.cpp)</b> — if the native library is available,
 *       uses llama.cpp for maximum performance with GPU acceleration.</li>
 *   <li><b>Pure Java</b> — fallback using the built-in GGUF reader + transformer
 *       inference engine. No native dependencies required.</li>
 * </ol>
 * Backend selection is automatic: native is preferred when the shared library
 * is found at startup. Both backends support tool calling.
 */
public class AiServiceImpl implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    private enum Backend { NATIVE, JAVA }

    private final Backend backend;
    private final LlamaRunner javaRunner;        // pure Java backend
    private LlamaContext nativeContext;           // JNI/llama.cpp backend
    private ChatTemplate nativeChatTemplate;      // template auto-detected for native backend
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private volatile String loadedModelPath;

    public AiServiceImpl() {
        // Attempt to load native library
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

                // Read the model's chat_template metadata directly from the GGUF
                // file so the native path stops hard-coding ChatML — that mismatch
                // is why non-Qwen models (Llama 3 / Gemma / Mistral) used to just
                // echo the user's input back verbatim.
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

    @Override
    public boolean isReady() {
        if (backend == Backend.NATIVE) return nativeContext != null;
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

    @Override
    public long getMemoryUsage() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
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
                // Prefer our locally-tracked response: it has been truncated at the
                // first stop sequence, while fullText from native may include extra
                // tokens that slipped past the EOG check.
                String finalText = response.toString();
                int n = tokenCount.get();
                // Exclude prefill (time before first token) so tok/s reflects pure
                // generation throughput, matching how the Java backend reports it.
                long baseNanos = firstTokenNanos[0] != 0L ? firstTokenNanos[0] : genStartNanos;
                long elapsedMs = (System.nanoTime() - baseNanos) / 1_000_000;
                double tokPerSec = (n > 0 && elapsedMs > 0) ? n * 1000.0 / elapsedMs : 0;

                List<AiToolCall> toolCalls = ToolCallParser.parse(finalText);
                if (!toolCalls.isEmpty() && toolRegistry.hasTools()) {
                    hadToolCall.set(true);
                    handleToolCallsNative(toolCalls, history, temperature, topP, maxTokens,
                                          callback, round, hadToolCall);
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

    private void handleToolCallsNative(List<AiToolCall> toolCalls, List<AiChatMessage> history,
                                       float temperature, float topP, int maxTokens,
                                       AiStreamCallback callback, int round, AtomicBoolean hadToolCall) {
        history.add(AiChatMessage.assistantWithTools("", toolCalls));
        executeAndFeedToolResults(toolCalls, history, callback);
        String newPrompt = buildNativePrompt(history, buildSystemPrompt());
        generateNativeWithToolLoop(newPrompt, temperature, topP, maxTokens,
                                   history, callback, round + 1, hadToolCall);
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
                if (!toolCalls.isEmpty() && toolRegistry.hasTools()) {
                    hadToolCall.set(true);
                    handleToolCallsJava(toolCalls, history, temperature, topP, maxTokens,
                                        callback, round, hadToolCall);
                } else {
                    String clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(fullResponse) : fullResponse;
                    Platform.runLater(() -> callback.onComplete(clean, tokensGenerated, tokensPerSecond));
                }
            }
        });
    }

    private void handleToolCallsJava(List<AiToolCall> toolCalls, List<AiChatMessage> history,
                                     float temperature, float topP, int maxTokens,
                                     AiStreamCallback callback, int round, AtomicBoolean hadToolCall) {
        history.add(AiChatMessage.assistantWithTools("", toolCalls));
        executeAndFeedToolResults(toolCalls, history, callback);
        try {
            String newPrompt = javaRunner.buildPrompt(history, buildSystemPrompt());
            javaRunner.resetCache();
            generateJavaWithToolLoop(newPrompt, temperature, topP, maxTokens,
                                     history, callback, round + 1, hadToolCall);
        } catch (Exception e) {
            Platform.runLater(() -> callback.onError(e));
        }
    }

    // ── Shared tool execution ─────────────────────────────────

    private void executeAndFeedToolResults(List<AiToolCall> toolCalls, List<AiChatMessage> history,
                                           AiStreamCallback callback) {
        for (AiToolCall toolCall : toolCalls) {
            Platform.runLater(() -> callback.onToolCall(toolCall));
            log.info("Executing tool: name={}, args={}", toolCall.name(), toolCall.arguments());
            AiToolResult result = toolRegistry.execute(toolCall.name(), toolCall.arguments());
            log.info("Tool result: success={}", result.success());
            Platform.runLater(() -> callback.onToolResult(toolCall.id(), result));
            history.add(AiChatMessage.toolResult(toolCall.id(), toolCall.name(), result.output()));
        }
    }

    // ── Prompt building ───────────────────────────────────────

    private String buildSystemPrompt() {
        String base = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = toolRegistry.buildToolDefinitions();
        if (toolDefs.isEmpty()) return base;
        return base + "\n\n" + toolDefs;
    }

    /**
     * Build prompt for the native backend using the model's GGUF-declared chat template.
     * Falls back to ChatML if metadata could not be read at load time.
     */
    private String buildNativePrompt(List<AiChatMessage> history, String systemPrompt) {
        ChatTemplate template = nativeChatTemplate != null ? nativeChatTemplate : new ChatTemplate("");
        return template.buildPrompt(history, systemPrompt);
    }

    // ── Lifecycle ─────────────────────────────────────────────

    @Override
    public void cancelGeneration() {
        if (javaRunner != null) javaRunner.cancel();
        // Native cancellation would require interrupting the native thread
    }

    @Override
    public boolean isGenerating() {
        if (backend == Backend.NATIVE) return false; // native runs synchronously on virtual thread
        return javaRunner != null && javaRunner.isGenerating();
    }

    // ── Tool management ───────────────────────────────────────

    @Override
    public void registerTool(AiTool tool) {
        toolRegistry.register(tool);
        log.info("Registered AI tool: {}", tool.getName());
    }

    @Override
    public void unregisterTool(String toolName) {
        toolRegistry.unregister(toolName);
        log.info("Unregistered AI tool: {}", toolName);
    }

    @Override
    public List<AiTool> getTools() {
        return toolRegistry.getAll();
    }
}
