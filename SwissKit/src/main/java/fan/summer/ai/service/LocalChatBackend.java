package fan.summer.ai.service;

import fan.summer.ai.inference.LlamaRunner;
import fan.summer.ai.model.ChatTemplate;
import fan.summer.ai.model.GGUFReader;
import fan.summer.ai.nativejni.GenerateCallback;
import fan.summer.ai.nativejni.GenerateParams;
import fan.summer.ai.nativejni.ModelParams;
import fan.summer.ai.nativejni.NativeWorkerClient;
import fan.summer.ai.tools.Qwen3Adapter;
import fan.summer.ai.tools.ThinkingStreamSegmenter;
import fan.summer.ai.tools.ToolCallParser;
import fan.summer.ai.tools.ToolExecutor;
import fan.summer.ai.tools.ToolSchemaBuilder;
import fan.summer.api.ai.AiChatMessage;
import fan.summer.api.ai.AiServiceException;
import fan.summer.api.ai.AiStreamCallback;
import fan.summer.api.ai.AiToolCall;
import fan.summer.api.ai.AiToolResult;
import fan.summer.api.ai.AiServiceProvider;
import fan.summer.api.ai.ChatBackend;
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
 * {@link ChatBackend} implementation for local AI models (GGUF format).
 * Uses a child JVM process ({@link NativeWorkerClient}) for native inference
 * to prevent native crashes from killing the main application.
 *
 * @see ChatBackend
 * @see NativeWorkerClient
 */
public class LocalChatBackend implements ChatBackend {

    private static final Logger log = LoggerFactory.getLogger(LocalChatBackend.class);
    private static final int MAX_TOOL_ROUNDS = 8;
    /**
     * Minimum generation budget for Qwen3 hybrid-reasoning models. Thinking models
     * spend the first N tokens inside {@code <think>…</think>}; if {@code maxTokens}
     * is too low the model is truncated mid-think, the {@code <think>} block never
     * closes, and {@link ThinkingStreamSegmenter#stripThink} then wipes the whole
     * output — yielding a silently empty answer. 512 (the old default) is far too
     * small; 2048 leaves room to finish reasoning and emit the actual answer.
     * Referenced by the settings UI as the default max-tokens value so a fresh
     * install never sits below the floor a thinking model needs.
     */
    public static final int QWEN3_MIN_MAX_TOKENS = 2048;

    /**
     * Number of transformer layers to offload to the GPU on the native backend. The
     * llama.cpp JNI exposes no GPU-probe, so this is also the "use GPU if available"
     * switch: llama.cpp reads it as "offload min(N, model_layers) layers to the
     * Metal/CUDA/Vulkan backend if one is compiled into the native lib AND a device
     * is present, otherwise 0". 9999 is far above any real model's layer count
     * (Qwen3-4B has 36), so on Apple Silicon the whole model goes to Metal, while a
     * CPU-only native build is unaffected (ngl clamped to 0 internally — load does
     * NOT fail). The shared log's [worker-stderr] lines show dev=Metal/GPU vs CPU.
     */
    static final int NATIVE_GPU_LAYERS_OFFLOAD = 9999;

    /**
     * Returns the effective max-tokens budget, raising it to
     * {@link #QWEN3_MIN_MAX_TOKENS} for Qwen3 (thinking models) so reasoning isn't
     * truncated. Non-Qwen3 backends are passed through unchanged.
     */
    static int effectiveMaxTokens(boolean qwen3, int requested) {
        return qwen3 ? Math.max(requested, QWEN3_MIN_MAX_TOKENS) : requested;
    }

    private enum Backend { NATIVE, JAVA }

    private final Backend backend;
    private LlamaRunner javaRunner;
    private NativeWorkerClient workerClient;
    private ChatTemplate nativeChatTemplate;
    private volatile String loadedModelPath;
    private Qwen3Adapter qwen3Adapter;
    private boolean isQwen3;

    /**
     * Creates a new AI service with the specified backend.
     *
     * @param useNative if true, use llama.cpp JNI for inference;
     *                  if false, use the pure Java inference engine.
     *                  The caller must ensure the native library is loaded beforehand
     *                  when useNative is true.
     */
    public LocalChatBackend(boolean useNative) {
        if (useNative) {
            backend = Backend.NATIVE;
            log.info("AI backend: native (llama.cpp JNI, out-of-process)");
        } else {
            backend = Backend.JAVA;
            log.info("AI backend: pure Java");
        }
    }

    private void detectModelType(String modelPath) {
        isQwen3 = false;
        qwen3Adapter = null;
        if (backend != Backend.NATIVE && backend != Backend.JAVA) return;
        String name = Path.of(modelPath).getFileName().toString().toLowerCase();
        isQwen3 = name.contains("qwen3");
        if (isQwen3) {
            qwen3Adapter = new Qwen3Adapter();
            log.info("Qwen3 detected — Hermes tool calling + thinking stream");
            // Qwen3.5 ships a hybrid recurrent-memory architecture
            // (general.architecture=qwen35) that SIGABRTs the native worker around turn 2.
            // The transformer Qwen3-4B is the safe pick. Warn loudly so the crash isn't
            // first discovered mid-conversation.
            if (name.contains("qwen3.5") || name.contains("qwen35")) {
                log.warn("Model '{}' looks like the hybrid Qwen3.5 architecture — the native " +
                         "worker is known to crash on multi-turn with it. Use Qwen3-4B (transformer) instead.",
                         name);
            }
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
                    .ctxLength(8192)
                    .gpuLayers(NATIVE_GPU_LAYERS_OFFLOAD)
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
        isQwen3 = false;
        qwen3Adapter = null;
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

    @Override public boolean isNativeAvailable() {
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

        // Apply the Qwen3 thinking-model floor ONCE at the unified entry so both the
        // native and Java paths benefit. Previously it lived only in chatQwen3Native,
        // which left the pure-Java backend (and the shouldFallback() reroute below)
        // running Qwen3 with an undersized budget and silently empty answers when
        // maxTokens was truncated mid-<think>. Non-Qwen3 models pass through unchanged.
        int budget = effectiveMaxTokens(isQwen3, maxTokens);
        if (budget > maxTokens) {
            log.info("Qwen3 maxTokens raised {} -> {} (thinking model needs room to finish <think>)",
                     maxTokens, budget);
        }

        if (backend == Backend.NATIVE && workerClient != null && workerClient.shouldFallback()) {
            log.warn("Native worker crashed repeatedly — falling back to Java backend");
            ensureJavaRunnerLoaded();
            chatJava(history, temperature, topP, budget, callback);
            return;
        }

        if (backend == Backend.NATIVE) {
            chatNative(history, temperature, topP, budget, callback);
        } else {
            chatJava(history, temperature, topP, budget, callback);
        }
    }

    // ── Native backend chat ───────────────────────────────────

    private void chatNative(List<AiChatMessage> history, float temperature, float topP,
                            int maxTokens, AiStreamCallback callback) {
        if (isQwen3) {
            chatQwen3Native(history, temperature, topP, maxTokens, callback);
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

        TokenBatcher batcher = TokenBatcher.forCallback(callback);

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

    // ── Qwen3 native chat (Hermes tool calling + thinking stream) ─────────

    private void chatQwen3Native(List<AiChatMessage> history, float temperature,
                                 float topP, int maxTokens, AiStreamCallback callback) {
        // maxTokens is already floored to QWEN3_MIN_MAX_TOKENS by chat() at the unified
        // entry point — no per-path raise (or log) needed here.
        Thread.ofVirtual().start(() -> {
            try {
                String systemPrompt = buildSystemPrompt();
                String prompt = buildNativePrompt(history, systemPrompt);
                generateQwen3Loop(prompt, temperature, topP, maxTokens, history, callback, 0);
            } catch (Exception e) {
                log.error("Qwen3 generation error", e);
                Platform.runLater(() -> callback.onError(e));
            }
        });
    }

    private void generateQwen3Loop(String prompt, float temperature, float topP,
                                   int maxTokens, List<AiChatMessage> history,
                                   AiStreamCallback callback, int round) {
        if (round >= MAX_TOOL_ROUNDS || workerClient == null || !workerClient.isAlive()) return;

        GenerateParams genParams = new GenerateParams()
            .temperature(temperature).topP(topP).maxTokens(maxTokens);

        ThinkingStreamSegmenter segmenter = new ThinkingStreamSegmenter();
        TokenBatcher batcher = TokenBatcher.forCallback(callback);

        workerClient.generate(prompt, genParams, new GenerateCallback() {
            @Override
            public boolean onToken(String tokenText) {
                routeSegments(segmenter.feed(tokenText), batcher, callback);
                return true;
            }

            @Override
            public void onDone(String fullText, int tokenCount, double tokPerSec) {
                // Drain any tail the segmenter held back, then close the batcher.
                routeSegments(segmenter.flush(), batcher, callback);
                batcher.close();

                List<AiToolCall> toolCalls = ToolCallParser.parse(fullText);
                if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
                    // Clean prose (no think, no tool-call markers) + tool calls into history;
                    // thinking is intentionally dropped — it never enters the next prompt.
                    String assistantText = ToolCallParser.stripToolCalls(
                        ThinkingStreamSegmenter.stripThink(fullText));
                    history.add(AiChatMessage.assistantWithTools(assistantText, toolCalls));
                    ToolExecutor.executeAndFeed(toolCalls, history, callback);
                    String newPrompt = buildNativePrompt(history, buildSystemPrompt());
                    generateQwen3Loop(newPrompt, temperature, topP, maxTokens,
                                      history, callback, round + 1);
                } else {
                    String clean = ToolCallParser.stripToolCalls(
                        ThinkingStreamSegmenter.stripThink(fullText));
                    // Guard the silent-empty failure: if there was raw output but nothing
                    // survives stripThink, the model produced only a <think> block. The
                    // usual cause is maxTokens cutting it off before </think> — which looks
                    // to the user like the AI simply refused to answer. Surface it so the
                    // symptom is diagnosable instead of invisible.
                    if (clean.isEmpty() && !fullText.isBlank()) {
                        boolean thinkClosed = fullText.contains("</think>");
                        log.warn("Qwen3 produced no answer text (fullLen={}, thinkClosed={}) — {}",
                                 fullText.length(), thinkClosed,
                                 thinkClosed ? "think block closed with no content after it"
                                             : "thinking truncated before </think>; raise maxTokens");
                    }
                    final String answer = clean;
                    Platform.runLater(() -> callback.onComplete(answer, tokenCount, tokPerSec));
                }
            }

            @Override
            public void onError(String message) {
                batcher.close();
                Platform.runLater(() -> callback.onError(new RuntimeException(message)));
            }
        });
    }

    // ── Qwen3 segment routing (shared by native + Java loops) ────────────────

    /**
     * Routes segmented Qwen3 output: THINK regions go to the collapsible thinking
     * card via {@link AiStreamCallback#onThinking} (on the FX thread), CONTENT
     * regions go to the token batcher for the visible answer. Shared by the native
     * and Java Qwen3 generation loops so the routing logic stays in one place.
     */
    private void routeSegments(List<ThinkingStreamSegmenter.Segment> segments,
                               TokenBatcher batcher, AiStreamCallback callback) {
        for (ThinkingStreamSegmenter.Segment seg : segments) {
            if (seg.type() == ThinkingStreamSegmenter.Type.THINK) {
                final String t = seg.text();
                Platform.runLater(() -> callback.onThinking(t));
            } else {
                batcher.add(seg.text());
            }
        }
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

        TokenBatcher batcher = TokenBatcher.forCallback(callback);
        // Qwen3 on the Java backend emits the same <think>/<tool_call> markers as on
        // native — route thinking to the collapsible card (streaming) and strip it from
        // the final answer/history, mirroring generateQwen3Loop. The Java backend is the
        // fallback path, but without this a Qwen3 model leaks raw <think> tags into the
        // visible answer. Native stays the primary path.
        ThinkingStreamSegmenter segmenter = isQwen3 ? new ThinkingStreamSegmenter() : null;

        javaRunner.generate(prompt, temperature, topP, maxTokens, new LlamaRunner.TokenCallback() {
            @Override
            public void onToken(String fragment) {
                if (segmenter != null) {
                    routeSegments(segmenter.feed(fragment), batcher, callback);
                } else {
                    batcher.add(fragment);
                }
            }

            @Override
            public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
                if (segmenter != null) {
                    routeSegments(segmenter.flush(), batcher, callback);
                }
                batcher.close();

                List<AiToolCall> toolCalls = ToolCallParser.parse(fullResponse);
                if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
                    hadToolCall.set(true);
                    // Qwen3: keep cleaned prose (no think, no tool-call markers) in the
                    // assistant turn, matching the native Qwen3 path. Non-Qwen3 keeps
                    // the original empty assistant text.
                    String assistantText = isQwen3
                        ? ToolCallParser.stripToolCalls(ThinkingStreamSegmenter.stripThink(fullResponse))
                        : "";
                    history.add(AiChatMessage.assistantWithTools(assistantText, toolCalls));
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
                    final String clean;
                    if (isQwen3) {
                        clean = ToolCallParser.stripToolCalls(ThinkingStreamSegmenter.stripThink(fullResponse));
                        // Same silent-empty guard as the native path: think-only output
                        // (usually a maxTokens truncation) is diagnosable, not invisible.
                        if (clean.isEmpty() && !fullResponse.isBlank()) {
                            boolean thinkClosed = fullResponse.contains("</think>");
                            log.warn("Qwen3 (java) produced no answer text (fullLen={}, thinkClosed={}) — {}",
                                     fullResponse.length(), thinkClosed,
                                     thinkClosed ? "think block closed with no content after it"
                                                 : "thinking truncated before </think>; raise maxTokens");
                        }
                    } else {
                        clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(fullResponse) : fullResponse;
                    }
                    Platform.runLater(() -> callback.onComplete(clean, tokensGenerated, tokensPerSecond));
                }
            }
        });
    }

    // ── Prompt building ───────────────────────────────────────

    private String buildSystemPrompt() {
        String base = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = ToolSchemaBuilder.buildPromptDefinitions(AiServiceProvider.getTools());
        String composed = toolDefs.isEmpty() ? base : base + "\n\n" + toolDefs;
        if (isQwen3 && qwen3Adapter != null) {
            return qwen3Adapter.augmentSystemPrompt(composed);
        }
        return composed;
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

    // ── Token batching ────────────────────────────────────────

    /**
     * Batches token text to avoid flooding the FX thread with individual
     * {@code Platform.runLater} calls during high-speed generation.
     * Tokens are accumulated in a StringBuffer and flushed every ~50ms or
     * when the generation ends, whichever comes first.
     */
    static final class TokenBatcher {
        /** Shared daemon scheduler for all batchers — avoids one thread per generation. */
        private static final java.util.concurrent.ScheduledExecutorService FLUSH_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ai-token-flusher");
                t.setDaemon(true);
                return t;
            });

        private static final long FLUSH_DELAY_MS = 50;

        private final java.util.function.Consumer<String> emitter;
        private final StringBuilder buffer = new StringBuilder();
        private final AtomicReference<java.util.concurrent.ScheduledFuture<?>> pendingFlush =
            new AtomicReference<>();
        private volatile boolean active = true;

        /**
         * Test seam: batch tokens and emit each flushed batch via {@code emitter}
         * (no JavaFX dependency, so the batching logic is unit-testable).
         */
        TokenBatcher(java.util.function.Consumer<String> emitter) {
            this.emitter = emitter;
        }

        /** Production factory: emits each batch on the FX thread via the callback. */
        static TokenBatcher forCallback(AiStreamCallback callback) {
            return new TokenBatcher(text -> Platform.runLater(() -> callback.onToken(text)));
        }

        /** Appends a token and schedules a flush if none is pending. */
        void add(String token) {
            if (!active) return;
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
            emitter.accept(batch);
        }

        /** Disables further batching, cancels the pending flush, and flushes any remainder. */
        void close() {
            active = false;
            java.util.concurrent.ScheduledFuture<?> f = pendingFlush.getAndSet(null);
            if (f != null) f.cancel(false);
            flush();
        }

        private void scheduleFlush() {
            if (!active) return;
            if (pendingFlush.get() != null) return; // a flush is already scheduled
            java.util.concurrent.ScheduledFuture<?> task = FLUSH_SCHEDULER.schedule(
                this::runScheduledFlush, FLUSH_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            pendingFlush.compareAndSet(null, task);
        }

        private void runScheduledFlush() {
            pendingFlush.set(null);   // allow the next add() to schedule again
            flush();
        }
    }
}
