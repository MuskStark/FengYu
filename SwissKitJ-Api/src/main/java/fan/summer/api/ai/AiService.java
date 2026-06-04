package fan.summer.api.ai;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * AI inference service exposed by the host application via SwissKitJ-Api.
 * <p>
 * Third-party plugins can obtain an instance through
 * {@link AiServiceProvider#getService()} to integrate AI capabilities
 * without depending on any specific inference implementation.
 *
 * <pre>
 *   AiService ai = AiServiceProvider.getService().orElse(null);
 *   if (ai != null && ai.isReady()) {
 *       ai.chat(List.of(AiChatMessage.user("Hello")), callback);
 *   }
 * </pre>
 */
public interface AiService {

    // ── Model management ──────────────────────────────────────

    /**
     * Load a GGUF model from the given file path.
     * Blocks until the model is fully loaded.
     *
     * @param modelPath path to the .gguf model file
     * @throws AiServiceException if loading fails (invalid format, OOM, I/O error)
     */
    void loadModel(Path modelPath) throws AiServiceException;

    /**
     * Release the currently loaded model and free all associated memory.
     */
    void unloadModel();

    /**
     * @return true if a model is loaded and ready for inference
     */
    boolean isReady();

    /**
     * @return the model name/identifier, or empty if no model is loaded
     */
    Optional<String> getModelName();

    /**
     * @return estimated memory usage in bytes, or -1 if unknown
     */
    long getMemoryUsage();

    // ── Chat ──────────────────────────────────────────────────

    /**
     * Send a chat completion request with streaming output.
     * The callback receives tokens as they are generated.
     *
     * @param history conversation history (system + user + assistant messages)
     * @param callback receives streamed response fragments
     * @throws AiServiceException if no model is loaded or inference fails
     */
    void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException;

    /**
     * Send a chat completion request with streaming output and custom parameters.
     *
     * @param history conversation history
     * @param temperature sampling temperature (0 = greedy, typical 0.7)
     * @param topP nucleus sampling threshold (0-1, typical 0.9)
     * @param maxTokens maximum tokens to generate
     * @param callback receives streamed response fragments
     * @throws AiServiceException if no model is loaded or inference fails
     */
    void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
              AiStreamCallback callback) throws AiServiceException;

    /**
     * Cancel the in-progress generation (if any).
     */
    void cancelGeneration();

    /**
     * @return true if a generation is currently in progress
     */
    boolean isGenerating();

    // ── Tool management ───────────────────────────────────────

    /**
     * @return true if the native (JNI) inference backend is loaded and healthy.
     *         Returns false when using pure-Java fallback or cloud backends.
     */
    default boolean isNativeAvailable() {
        return false;
    }

    /**
     * Register a tool that the AI model can invoke during generation.
     *
     * @param tool the tool to register
     */
    void registerTool(AiTool tool);

    /**
     * Unregister a previously registered tool.
     *
     * @param toolName the name of the tool to remove
     */
    void unregisterTool(String toolName);

    /**
     * @return list of currently registered tools
     */
    List<AiTool> getTools();
}
