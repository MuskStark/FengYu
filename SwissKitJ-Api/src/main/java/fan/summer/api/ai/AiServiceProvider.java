package fan.summer.api.ai;

import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry for the active {@link AiService} instance and its registered tools.
 * Acts as a static singleton providing unified access to AI service operations across
 * the application, decoupling service consumers from the specific implementation in use.
 *
 * <p>Mode switching is coordinated through {@link #switchMode(String, AiService)}, which
 * safely unloads the previous service before installing the new one and fires state-change
 * notifications to all registered listeners.
 *
 * <p>Tool registration is global: tools registered via this provider are visible to all
 * {@link AiService} implementations that delegate to it (e.g., {@code AnthropicService},
 * {@code OpenAiService}, {@code AiServiceImpl}).
 *
 * @see AiService
 * @see AiTool
 */
public final class AiServiceProvider {

    private static final PluginLogger log = LoggerFactory.getLogger(AiServiceProvider.class);

    private static volatile AiService instance;
    private static volatile String currentMode = "local";
    private static final List<Runnable> stateChangeListeners = new CopyOnWriteArrayList<>();
    private static final Map<String, AiTool> tools = new ConcurrentHashMap<>();

    private AiServiceProvider() {}

    // ── Service management ────────────────────────────────────

    /**
     * Sets the global AI service instance directly, replacing any previously set instance.
     * This does not trigger a state-change notification.
     *
     * @param service the new {@link AiService} instance, or {@code null} to clear
     */
    public static void setService(AiService service) {
        instance = service;
    }

    /**
     * Returns the currently active AI service instance, if any.
     *
     * @return an {@link Optional} containing the active service, or empty if none is set
     */
    public static Optional<AiService> getService() {
        return Optional.ofNullable(instance);
    }

    /**
     * Switches the active AI service to a new mode and instance. The previously loaded
     * service is unloaded before the new one is installed. All state-change listeners
     * are notified after the switch.
     *
     * @param mode      the mode label (e.g., {@code "local"}, {@code "openai"}, {@code "anthropic"})
     * @param newService the new {@link AiService} to install
     */
    public static synchronized void switchMode(String mode, AiService newService) {
        if (instance != null) {
            try {
                instance.unloadModel();
            } catch (Exception e) {
                log.warn("Failed to unload previous AI service: {}", e.getMessage());
            }
        }
        currentMode = mode;
        instance = newService;
        notifyStateChanged();
    }

    // ── State change listeners ────────────────────────────────

    /**
     * Adds a listener to be notified whenever the AI service state changes
     * (e.g., mode switch, model load/unload).
     *
     * @param listener the {@link Runnable} to invoke on state changes
     */
    public static void addOnStateChangeListener(Runnable listener) {
        stateChangeListeners.add(listener);
    }

    /**
     * Removes a previously registered state-change listener.
     *
     * @param listener the listener to remove
     */
    public static void removeOnStateChangeListener(Runnable listener) {
        stateChangeListeners.remove(listener);
    }

    /**
     * Notifies all registered state-change listeners by invoking each in turn.
     */
    public static void notifyStateChanged() {
        for (Runnable listener : stateChangeListeners) {
            listener.run();
        }
    }

    // ── Mode ──────────────────────────────────────────────────

    /**
     * Returns the current AI mode label.
     *
     * @return the current mode string (e.g., {@code "local"}, {@code "openai"})
     */
    public static String getCurrentMode() {
        return currentMode;
    }

    /**
     * Sets the current AI mode label without changing the service instance.
     *
     * @param mode the new mode label
     */
    public static void setCurrentMode(String mode) {
        currentMode = mode;
    }

    // ── Shared tool registry ──────────────────────────────────

    /**
     * Registers a tool globally, making it available to the active AI service.
     * If a tool with the same name is already registered, it is replaced.
     *
     * @param tool the {@link AiTool} to register
     */
    public static void registerTool(AiTool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * Unregisters the tool with the given name.
     *
     * @param toolName the name of the tool to remove
     */
    public static void unregisterTool(String toolName) {
        tools.remove(toolName);
    }

    /**
     * Returns an immutable list of all currently registered tools.
     *
     * @return a list of registered {@link AiTool} instances
     */
    public static List<AiTool> getTools() {
        return List.copyOf(tools.values());
    }

    /**
     * Returns {@code true} if there is at least one registered tool.
     *
     * @return true if any tools are registered, false otherwise
     */
    public static boolean hasTools() {
        return !tools.isEmpty();
    }

    /**
     * Returns the tool with the given name, if registered.
     *
     * @param name the tool's name
     * @return the {@link AiTool} with that name, or {@code null} if not found
     */
    public static AiTool getTool(String name) {
        return tools.get(name);
    }

    /**
     * Removes all registered tools. Useful during shutdown or when switching
     * AI backends that require a fresh tool set.
     */
    public static void clearTools() {
        tools.clear();
    }

    // ── Backend health ──────────────────────────────────────────

    /**
     * Returns {@code true} if the current AI service is using a healthy native
     * (JNI) inference backend. Returns {@code false} for pure-Java fallback,
     * cloud backends, or when no service is configured.
     */
    public static boolean isNativeAvailable() {
        AiService svc = instance;
        return svc != null && svc.isNativeAvailable();
    }
}
