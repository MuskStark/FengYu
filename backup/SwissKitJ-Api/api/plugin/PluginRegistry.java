package fan.summer.api.plugin;

import javafx.scene.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Plugin registry for managing plugin UI injection and lifecycle.
 * Provides a bridge between plugins and the main application window.
 *
 * <p>Plugins use this registry to:</p>
 * <ul>
 *   <li>Register their pages with the application</li>
 *   <li>Inject UI nodes into the main content area</li>
 *   <li>Subscribe to application lifecycle events</li>
 *   <li>Access shared services</li>
 * </ul>
 */
public class PluginRegistry {

    private static final Logger logger = LoggerFactory.getLogger(PluginRegistry.class);
    private static volatile PluginRegistry instance;

    /**
     * Content provider function type - returns a Node for injection
     */
    @FunctionalInterface
    public interface ContentProvider {
        Node get();
    }

    /**
     * Lifecycle event type
     */
    @FunctionalInterface
    public interface LifecycleEvent {
        void onEvent();
    }

    private final Map<String, ContentProvider> contentProviders = new ConcurrentHashMap<>();
    private final Map<String, Node> pluginNodes = new ConcurrentHashMap<>();
    private final Map<Class<?>, LifecycleEvent> lifecycleSubscribers = new ConcurrentHashMap<>();

    private Consumer<Node> contentInjector;
    private volatile boolean appReady = false;

    public PluginRegistry() {
    }

    /**
     * Get the singleton instance.
     */
    public static PluginRegistry getInstance() {
        if (instance == null) {
            synchronized (PluginRegistry.class) {
                if (instance == null) {
                    instance = new PluginRegistry();
                }
            }
        }
        return instance;
    }

    /**
     * Register the main content injector callback.
     * Called by MainController to receive plugin content.
     */
    public void setContentInjector(Consumer<Node> injector) {
        this.contentInjector = injector;
    }

    /**
     * Register a content provider for a plugin class.
     * When the plugin is selected, its content will be injected into the main window.
     */
    public void registerContentProvider(Class<?> pluginClass, ContentProvider provider) {
        contentProviders.put(pluginClass.getName(), provider);
        logger.debug("Registered content provider for: {}", pluginClass.getName());
    }

    /**
     * Register a plugin's Node directly.
     */
    public void registerPluginNode(Class<?> pluginClass, Node node) {
        pluginNodes.put(pluginClass.getName(), node);
        logger.debug("Registered node for: {}", pluginClass.getName());
    }

    /**
     * Unregister a plugin's content.
     */
    public void unregisterPlugin(Class<?> pluginClass) {
        contentProviders.remove(pluginClass.getName());
        pluginNodes.remove(pluginClass.getName());
        logger.debug("Unregistered plugin: {}", pluginClass.getName());
    }

    /**
     * Inject a plugin's content into the main window.
     */
    public boolean injectContent(Class<?> pluginClass) {
        ContentProvider provider = contentProviders.get(pluginClass.getName());
        if (provider != null) {
            Node node = provider.get();
            if (node != null && contentInjector != null) {
                contentInjector.accept(node);
                logger.info("Injected content for plugin: {}", pluginClass.getName());
                return true;
            }
        }

        Node node = pluginNodes.get(pluginClass.getName());
        if (node != null && contentInjector != null) {
            contentInjector.accept(node);
            logger.info("Injected node for plugin: {}", pluginClass.getName());
            return true;
        }

        logger.warn("No content provider or node found for plugin: {}", pluginClass.getName());
        return false;
    }

    /**
     * Subscribe to application lifecycle events.
     */
    public void subscribeLifecycle(LifecycleEvent event) {
        lifecycleSubscribers.put(event.getClass(), event);
        if (appReady) {
            event.onEvent();
        }
    }

    /**
     * Notify all subscribers that the application is ready.
     */
    public void notifyAppReady() {
        this.appReady = true;
        lifecycleSubscribers.values().forEach(LifecycleEvent::onEvent);
        logger.info("Application ready - notified {} lifecycle subscribers", lifecycleSubscribers.size());
    }

    public boolean isAppReady() {
        return appReady;
    }

    public Node getPluginNode(Class<?> pluginClass) {
        return pluginNodes.get(pluginClass.getName());
    }

    public boolean hasContent(Class<?> pluginClass) {
        return contentProviders.containsKey(pluginClass.getName())
                || pluginNodes.containsKey(pluginClass.getName());
    }

    public void clear() {
        contentProviders.clear();
        pluginNodes.clear();
        logger.debug("Plugin registry cleared");
    }
}
