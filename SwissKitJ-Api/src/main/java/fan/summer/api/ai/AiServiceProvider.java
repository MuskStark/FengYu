package fan.summer.api.ai;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Global access point for the AI service.
 * <p>
 * The host application installs the service instance during startup.
 * Plugins can then access it via {@link #getService()}.
 * <p>
 * Listeners can be registered via {@link #addOnStateChangeListener(Runnable)}
 * to be notified when the model is loaded or unloaded.
 *
 * <pre>
 *   Optional&lt;AiService&gt; ai = AiServiceProvider.getService();
 *   if (ai.isPresent()) {
 *       ai.get().chat(messages, callback);
 *   }
 * </pre>
 */
public final class AiServiceProvider {

    private static volatile AiService instance;
    private static volatile String currentMode = "local";
    private static final List<Runnable> stateChangeListeners = new CopyOnWriteArrayList<>();

    private AiServiceProvider() {}

    /** Install the service instance (called by the host application). */
    public static void setService(AiService service) {
        instance = service;
    }

    /** Obtain the installed AI service, or empty if unavailable. */
    public static Optional<AiService> getService() {
        return Optional.ofNullable(instance);
    }

    /** Register a callback to be invoked when the model state changes (load/unload). */
    public static void addOnStateChangeListener(Runnable listener) {
        stateChangeListeners.add(listener);
    }

    /** Remove a previously registered state-change listener. */
    public static void removeOnStateChangeListener(Runnable listener) {
        stateChangeListeners.remove(listener);
    }

    /** Notify all listeners that the model state has changed. */
    public static void notifyStateChanged() {
        for (Runnable listener : stateChangeListeners) {
            listener.run();
        }
    }

    /** Returns the current mode string: "local", "openai", or "anthropic". */
    public static String getCurrentMode() {
        return currentMode;
    }

    /**
     * Set the current mode label. Does NOT create the service —
     * the caller (host app) is responsible for instantiating the
     * appropriate AiService and passing it to {@link #switchMode(String, AiService)}.
     *
     * @param mode one of "local", "openai", "anthropic"
     */
    public static void setCurrentMode(String mode) {
        currentMode = mode;
    }

    /**
     * Switch to a new AI backend mode. Unloads the previous service's model
     * (if applicable), sets the new service, and notifies listeners.
     *
     * @param mode one of "local", "openai", "anthropic"
     * @param newService the newly instantiated AiService for the target mode
     */
    public static synchronized void switchMode(String mode, AiService newService) {
        if (instance != null) {
            try {
                instance.unloadModel();
            } catch (Exception e) {
                Logger.getLogger(AiServiceProvider.class.getName())
                    .log(Level.WARNING, "Failed to unload previous AI service", e);
            }
        }
        currentMode = mode;
        instance = newService;
        notifyStateChanged();
    }
}
