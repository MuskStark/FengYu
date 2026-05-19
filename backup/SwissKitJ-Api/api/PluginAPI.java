package fan.summer.api;

import fan.summer.api.plugin.PluginRegistry;
import javafx.scene.Node;

/**
 * Plugin API interface that plugins use to communicate with the host application.
 * Each plugin receives a PluginContext instance when it is loaded.
 *
 * <p>Plugins should implement KitPage to be discovered, and can optionally
 * implement this interface to receive lifecycle callbacks.</p>
 *
 * <p>Example plugin implementation:</p>
 * <pre>{@code
 * @SwissKitPage(menuName = "My Tool", order = 10)
 * public class MyToolPage implements KitPage, PluginAPI {
 *     private Node content;
 *     private PluginContext context;
 *
 *     @Override
 *     public Node getContent() {
 *         return content;
 *     }
 *
 *     @Override
 *     public void onLoad(PluginContext context) {
 *         this.context = context;
 *         // Initialize plugin resources
 *     }
 *
 *     @Override
 *     public void onUnload() {
 *         // Cleanup resources
 *     }
 *
 *     @Override
 *     public void onSelected() {
 *         // Plugin was selected in UI
 *     }
 * }
 * }</pre>
 */
public interface PluginAPI {

    /**
     * Called when the plugin is loaded by the host application.
     *
     * @param context provides access to host services
     */
    default void onLoad(PluginContext context) {}

    /**
     * Called when the plugin is unloaded or the application shuts down.
     * Release any resources acquired during onLoad().
     */
    default void onUnload() {}

    /**
     * Called when the plugin is selected/activated in the UI.
     * Use this to refresh data or start background tasks.
     */
    default void onSelected() {}

    /**
     * Called when the plugin is deselected.
     * Use this to pause background tasks or save state.
     */
    default void onDeselected() {}

    /**
     * Returns the main content Node for this plugin.
     * This will be injected into the application's content area.
     *
     * @return the Node to display
     */
    Node getContent();

    /**
     * Returns metadata about this plugin.
     *
     * @return plugin metadata
     */
    default PluginAPIMetadata getMetadata() {
        return new PluginAPIMetadata(getClass().getSimpleName(), "1.0.0");
    }

    /**
     * Context interface providing access to host application services.
     */
    interface PluginContext {
        /**
         * Get the application stage controller.
         */
        Object getMainController();

        /**
         * Get the plugin registry for registering content.
         */
        PluginRegistry getRegistry();

        /**
         * Get a configuration value.
         *
         * @param key configuration key
         * @return configuration value or null
         */
        String getConfig(String key);

        /**
         * Show a notification to the user.
         *
         * @param message notification message
         * @param type notification type (info, warning, error)
         */
        void showNotification(String message, NotificationType type);

        /**
         * Get the current locale.
         */
        String getLocale();
    }

    /**
     * Notification type for showNotification.
     */
    enum NotificationType {
        INFO,
        WARNING,
        ERROR
    }

    /**
     * Metadata about this plugin API implementation.
     */
    record PluginAPIMetadata(String name, String version) {}
}