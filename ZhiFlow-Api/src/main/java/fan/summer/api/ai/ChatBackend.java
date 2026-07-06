package fan.summer.api.ai;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Unified AI inference backend contract. Two host-supplied implementations exist:
 * {@code fan.summer.ai.service.CloudChatBackend} (LangChain4j-backed cloud providers)
 * and {@code fan.summer.ai.service.LocalChatBackend} (in-process GGUF).
 *
 * <p>UI consumers should treat this as opaque — call
 * {@link #chat(List, AiStreamCallback)} and react to events via the callback.
 * Use {@code instanceof CloudChatBackend} / {@code instanceof LocalChatBackend}
 * for backend-specific behavior (e.g. showing the local-mode degraded banner
 * when {@link #isNativeAvailable()} returns false).
 *
 * <p>Tool registration is global via {@link AiServiceProvider}; backends read
 * from there at chat time. Plugins that want to register tools should call
 * {@link AiServiceProvider#registerTool(AiTool)}.
 *
 * <p>Note: this interface is intentionally non-sealed (not {@code sealed}).
 * Java forbids cross-module sealed permits, and the implementations live in
 * the host module ({@code ZhiFlow}), while this contract lives in
 * {@code ZhiFlow-Api}. The two known implementors are documented above.
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
