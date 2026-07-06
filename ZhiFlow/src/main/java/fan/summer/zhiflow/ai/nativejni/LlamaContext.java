package fan.summer.zhiflow.ai.nativejni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core JNI wrapper around llama.cpp inference engine.
 * <p>
 * Holds a native {@code LlamaWrapper} pointer and provides synchronous/streaming
 * generation. Each instance owns its own KV cache — not thread-safe.
 * Use {@link #close()} or try-with-resources to release native resources.
 *
 * <pre>
 * try (LlamaContext ctx = new LlamaContext(params)) {
 *     ctx.generate("Hello, world!", genParams, (token) -> {
 *         System.out.print(token);
 *         return true; // continue
 *     });
 * }
 * </pre>
 */
public class LlamaContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LlamaContext.class);

    private volatile long nativePtr; // LlamaWrapper* in C++

    public LlamaContext(ModelParams params) {
        if (!NativeLoader.isLoaded()) {
            throw new IllegalStateException("Native llama library not loaded");
        }
        nativePtr = nativeInit(
            params.getModelPath(),
            params.getCtxLength(),
            params.getGpuLayers(),
            params.getThreads(),
            params.isFlashAttention() ? 1 : 0
        );
        if (nativePtr == 0) {
            throw new RuntimeException("Failed to initialize llama context for: " + params.getModelPath());
        }
        log.info("LlamaContext initialized: model={}", params.getModelPath());
    }

    /**
     * Generate text with streaming callback.
     *
     * @param prompt input prompt (pre-tokenized by native code)
     * @param params generation sampling parameters
     * @param callback receives tokens; return false from onToken to interrupt
     * @return the complete generated text
     */
    public String generate(String prompt, GenerateParams params, GenerateCallback callback) {
        checkPtr();
        return nativeGenerate(
            nativePtr,
            prompt,
            params.getMaxNewTokens(),
            params.getTemperature(),
            params.getTopP(),
            params.getRepeatPenalty(),
            params.getSeed(),
            callback
        );
    }

    /**
     * Count the number of tokens in the given text.
     */
    public int tokenize(String text) {
        checkPtr();
        return nativeTokenize(nativePtr, text);
    }

    /**
     * Get the llama.cpp version string.
     */
    public static native String version();

    @Override
    public void close() {
        if (nativePtr != 0) {
            nativeFree(nativePtr);
            nativePtr = 0;
            log.debug("LlamaContext closed");
        }
    }

    private void checkPtr() {
        if (nativePtr == 0) {
            throw new IllegalStateException("LlamaContext has been closed or not initialized");
        }
    }

    // ── Native method declarations ─────────────────────────────

    private static native long nativeInit(String modelPath, int nCtx, int nGpuLayers,
                                           int nThreads, int flashAttn);

    private static native String nativeGenerate(long ptr, String prompt,
                                                 int maxNewTokens, float temperature,
                                                 float topP, float repeatPenalty, long seed,
                                                 GenerateCallback callback);

    private static native int nativeTokenize(long ptr, String text);

    private static native void nativeFree(long ptr);
}
