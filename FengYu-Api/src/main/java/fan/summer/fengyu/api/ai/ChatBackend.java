package fan.summer.fengyu.api.ai;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Unified AI inference backend contract. Two host-supplied implementations exist:
 * {@code fan.summer.fengyu.ai.service.SpringAiCloudBackend} (Spring AI-backed cloud providers:
 * OpenAI / Anthropic / DeepSeek) and {@code fan.summer.fengyu.ai.service.OllamaLocalBackend}
 * (Ollama-served local models).
 *
 * <p>UI consumers should treat this as opaque — call
 * {@link #chat(List, AiStreamCallback)} and react to events via the callback.
 * Use {@code instanceof SpringAiCloudBackend} / {@code instanceof OllamaLocalBackend}
 * for backend-specific behavior (e.g. showing the local-mode degraded banner
 * when {@link #isNativeAvailable()} returns false).
 *
 * <p>Tool discovery is Spring AI-native: tool beans (classes with {@code @Tool}-annotated
 * methods that implement the host's {@code FengYuTool} marker) are aggregated by
 * {@code fan.summer.fengyu.ai.tools.AiToolDiscoveryConfig} into a single
 * {@code ToolCallback[]} bean, which the host wiring injects into each backend via
 * {@code setToolCallbacks(...)}. Backends then attach those callbacks to every prompt so the
 * model can request tools in chat. Plugins that want to expose tools should declare a
 * Spring {@code @Component} that {@code implements FengYuTool} with {@code @Tool} methods —
 * it is picked up automatically with no config edit.
 *
 * <p>Note: this interface is intentionally non-sealed (not {@code sealed}).
 * Java forbids cross-module sealed permits, and the implementations live in
 * the host module ({@code FengYu}), while this contract lives in
 * {@code FengYu-Api}. The two known implementors are documented above.
 */
public interface ChatBackend {

    // ── Lifecycle ─────────────────────────────────────────────

    /**
     * Cloud backends: no-op (no local model). Local backend: blocks until the
     * GGUF model is loaded.
     *
     * @param modelPath path to the .gguf model file
     * @throws AiServiceException if loading fails (local mode only)
     */
    void loadModel(Path modelPath) throws AiServiceException;

    /** Releases any loaded model. Cloud backends: clears cached state. */
    void unloadModel();

    /** @return true if the backend can immediately serve a {@link #chat} request */
    boolean isReady();

    /** @return model name for display, or empty if none loaded */
    Optional<String> getModelName();

    /**
     * @return estimated memory usage in bytes, or {@code -1} if unknown
     *         (cloud always returns {@code -1})
     */
    long getMemoryUsage();

    // ── Chat ──────────────────────────────────────────────────

    /**
     * Streaming chat with default sampling parameters (read from settings).
     *
     * @param history conversation history (system + user + assistant messages)
     * @param callback receives streamed response fragments and tool-call events
     * @throws AiServiceException if no model is loaded or inference fails
     */
    void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException;

    /**
     * Streaming chat with tool callbacks disabled. Planning phases use this to produce a
     * proposal without executing tools before the workflow has been approved.
     */
    default void chatWithoutTools(List<AiChatMessage> history, AiStreamCallback callback)
            throws AiServiceException {
        chat(history, callback);
    }

    /**
     * Streaming chat with explicit sampling parameters. All callback events
     * are delivered on the JavaFX Application Thread.
     */
    void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
              AiStreamCallback callback) throws AiServiceException;

    /** Best-effort abort of the in-progress generation. */
    void cancelGeneration();

    /** @return true if a generation is currently streaming */
    boolean isGenerating();

    /**
     * Local-only: returns true when the native (JNI) backend is healthy.
     * Cloud backends return false (they have no JNI surface).
     */
    default boolean isNativeAvailable() {
        return false;
    }
}
