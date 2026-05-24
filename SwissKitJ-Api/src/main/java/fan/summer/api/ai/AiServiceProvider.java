package fan.summer.api.ai;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

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

    /** Notify all listeners that the model state has changed. */
    public static void notifyStateChanged() {
        for (Runnable listener : stateChangeListeners) {
            listener.run();
        }
    }
}
