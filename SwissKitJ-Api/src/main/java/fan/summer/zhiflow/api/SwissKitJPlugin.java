package fan.summer.zhiflow.api;

import fan.summer.zhiflow.api.ai.AiTool;
import javafx.scene.Node;

import java.util.List;

/**
 * Main entry point contract for all SwissKitJ tools and plugins.
 *
 * <p>The host application ({@code SwissKit}) depends solely on this interface,
 * fully decoupled from any concrete implementation — whether built-in or
 * external third-party JAR.</p>
 *
 * <h2>Implementing a plugin</h2>
 * <ol>
 *   <li>Declare {@code SwissKitJ-Api} as a {@code provided} dependency in your pom.xml:
 *      {@code <artifactId>SwissKitJ-Api</artifactId>}</li>
 *   <li>Implement this interface in your plugin class</li>
 *   <li>Register the implementation by creating the file
 *       {@code META-INF/services/fan.summer.zhiflow.api.SwissKitJPlugin}
 *       containing the fully-qualified class name</li>
 *   <li>Package all private dependencies into a fat-JAR and drop it into
 *       the host's {@code plugins/} directory (hot-reload supported)</li>
 * </ol>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #createView()} is called once on first activation; the returned
 *       {@code Node} is cached and reused</li>
 *   <li>{@link #onActivate()} fires each time the tool enters the foreground</li>
 *   <li>{@link #onDeactivate()} fires each time the tool is pushed to the background</li>
 *   <li>{@link #onUnload()} fires once when the plugin is being uninstalled or the
 *       application is shutting down — use it to release threads, file handles, etc.</li>
 * </ul>
 *
 * @see ToolCategory
 * @see ToolType
 * @see IconStyle
 * @since 1.0
 */
public interface SwissKitJPlugin {

    // ── Metadata (used by sidebar, search, and detail panel) ────────────

    /**
     * Globally unique identifier for this tool.
     * Reverse-domain notation is recommended (e.g. {@code "com.example.json-formatter"}).
     *
     * @return a non-null, unique ID string
     */
    String getId();

    /**
     * Display name shown on the tool card and in the sidebar.
     *
     * @return a short, user-facing name, e.g. {@code "JSON Formatter"}
     */
    String getName();

    /**
     * One-line description shown on the tool card and in the detail panel.
     *
     * @return a concise description of what the tool does
     */
    String getDescription();

    /**
     * Navigation category this tool belongs to. Must be one of the values in {@link ToolCategory}.
     *
     * @return the category for sidebar grouping and filtering
     * @see ToolCategory
     */
    ToolCategory getCategory();

    /**
     * Version string of this plugin, formatted as {@code "major.minor.patch"}.
     *
     * @return the version, e.g. {@code "1.0.0"}
     */
    String getVersion();

    /**
     * Material Design Icons class name <i>without</i> the {@code mdi} prefix.
     *
     * <p>Example: {@code "file-excel"}, not {@code "mdi-file-excel"}.</p>
     *
     * <p>Full icon list: <a href="https://pictogrammers.com/library/mdi/">
     *     https://pictogrammers.com/library/mdi/</a></p>
     *
     * <p>If you need a custom {@link Node} rather than an MDI glyph, override
     * {@link #createView()} directly to supply it.</p>
     *
     * @return the icon name, e.g. {@code "file-excel"}
     * @see MdiIconUtil
     */
    String getMdiIcon();

    /**
     * Visual style for the tool's icon background — determines CSS class and accent colour.
     *
     * <p>Used by the host to apply the correct tint to the icon wrapper and
     * to render the {@code DropShadow} glow effect.</p>
     *
     * @return the icon style; defaults to {@link IconStyle#BLUE}
     * @see IconStyle
     */
    default IconStyle getIconStyle() { return IconStyle.BLUE; }

    /**
     * Distinguishes built-in tools from externally-loaded plugins.
     *
     * <p>Built-in tools (registered via {@code BuiltinToolRegistrar}) should
     * return {@link ToolType#BUILTIN}; external plugins should return the
     * default {@link ToolType#PLUGIN}.</p>
     *
     * @return the tool type; defaults to {@link ToolType#PLUGIN}
     * @see ToolType
     */
    default ToolType getType() { return ToolType.PLUGIN; }

    // ── UI lifecycle ──────────────────────────────────────

    /**
     * Creates and returns the main JavaFX UI for this tool.
     *
     * <p>The returned {@link Node} is embedded by the host into the content-area
     * {@code StackPane}. This method is called <b>once</b> on first activation;
     * the same {@code Node} instance is cached and reused for all subsequent
     * activations.</p>
     *
     * <p>Implementations may return any JavaFX {@code Node} subtype. For multi-step
     * workflows, consider using {@link fan.summer.zhiflow.api.component.StepWizard}.</p>
     *
     * @return the root {@code Node} of this tool's UI, never {@code null}
     * @see fan.summer.zhiflow.api.component.StepWizard
     */
    Node createView();

    /**
     * Called by the host each time this tool enters the foreground.
     *
     * <p>Use to resume background work, restart timers, or restore UI state
     * that was modified while the tool was inactive.</p>
     *
     * <p>The default implementation is a no-op.</p>
     */
    default void onActivate() {}

    /**
     * Called by the host each time this tool moves to the background.
     *
     * <p>Use to pause timers, persist transient UI state, or defer expensive
     * operations until the tool is reactivated.</p>
     *
     * <p>The default implementation is a no-op.</p>
     */
    default void onDeactivate() {}

    /**
     * Called once when the plugin is being unloaded — either because the
     * user uninstalled it or because the application is shutting down.
     *
     * <p>Release all held resources here: stop running threads, close file or
     * network handles, cancel scheduled tasks, etc.</p>
     *
     * <p>The default implementation is a no-op.</p>
     */
    default void onUnload() {}

    /**
     * Returns whether this plugin has background tasks currently running.
     *
     * <p>The host calls this method when the user navigates away from this
     * plugin. If it returns {@code true}, the host keeps the plugin's view
     * cached and calls {@link #onBackground()} instead of
     * {@link #onDeactivate()}, allowing tasks to continue.</p>
     *
     * <p>The default implementation returns {@code false}.</p>
     *
     * @return {@code true} if this plugin has running tasks that should
     *         continue in the background
     */
    default boolean hasRunningTasks() { return false; }

    /**
     * Called when this plugin moves to the background while having running tasks.
     *
     * <p>Replaces {@link #onDeactivate()} when {@link #hasRunningTasks()}
     * returns {@code true}. Use to perform any state adjustments needed
     * for background operation (e.g. disabling UI polling).</p>
     *
     * <p>The default implementation is a no-op.</p>
     */
    default void onBackground() {}

    /**
     * Called when this plugin returns to the foreground from a background state.
     *
     * <p>Fired after {@link #onActivate()} when the plugin was previously
     * backgrounded. Use to refresh any UI state that may need manual updates
     * after being detached from the scene graph (e.g. layout recalculations
     * based on node dimensions).</p>
     *
     * <p>The default implementation is a no-op.</p>
     */
    default void onForeground() {}

    // ── Host facade injection ─────────────────────────────

    /**
     * Called exactly once by the host, on the JavaFX Application Thread, after
     * the plugin is instantiated and before it becomes visible in the registry
     * (and before {@link #aiTools()} registration). The plugin's ClassLoader is
     * already on the thread-context ClassLoader. Store the reference — it stays
     * valid for the plugin's whole lifetime.
     *
     * <p>The default implementation is a no-op; existing plugins need no change.</p>
     *
     * @param host the host facade bound to this plugin instance
     * @since 3.2.0
     */
    default void init(fan.summer.zhiflow.api.host.PluginHost host) {}

    // ── AI tools ─────────────────────────────────────────

    /**
     * AI tools this plugin exposes to the AI layer.
     *
     * <p>Default is empty — plugins that don't integrate with AI don't need to do anything.
     * Tools are registered when the plugin is added to {@code PluginRegistry} and
     * unregistered when the plugin is removed.</p>
     *
     * <p>Threading: called on the JavaFX Application Thread (same thread as
     * {@code addPlugins}). Implementations must return a deterministic, idempotent
     * list — subsequent calls must return tools with the same names so unregister
     * can match them.</p>
     *
     * @return the AI tools this plugin exposes; empty list if none
     */
    default List<AiTool> aiTools() { return List.of(); }
}
