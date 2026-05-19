package fan.summer.api;

import javafx.scene.Node;

/**
 * Interface implemented by all tools, both built-in and external plugins.
 * The host application depends solely on this interface, fully decoupled from implementations.
 *
 * Plugin developer steps:
 *   1. Declare SwissKitJ-Api as a provided dependency in pom.xml:
 *      {@code
 *      <dependency>
 *          <groupId>fan.summer.api</groupId>
 *          <artifactId>SwissKitJ-Api</artifactId>
 *          <version>3.0.0</version>
 *          <scope>provided</scope>
 *      </dependency>
 *      }
 *   2. Implement this interface
 *   3. Declare the implementation's fully-qualified class name in
 *      META-INF/services/fan.summer.api.SwissKitJPlugin
 *   4. Package the plugin as a fat-JAR (include all private dependencies)
 *      and drop it into the host application's plugins/ directory
 */
public interface SwissKitJPlugin {

    // ── Metadata (used by sidebar, search, and detail panel) ────────────

    /** Globally unique ID; reverse-domain notation recommended: com.example.my-tool */
    String getId();

    /** Display name shown on the tool card, e.g. "JSON Formatter" */
    String getName();

    /** One-line description shown on the card and detail panel */
    String getDescription();

    /** Category matching sidebar navigation. See {@link ToolCategory}. */
    ToolCategory getCategory();

    /** Version string, e.g. "1.0.0" */
    String getVersion();

    /**
     * Material Design Icons class name (without "mdi" prefix), e.g. "file-excel".
     * Full list: https://pictogrammers.com/library/mdi/
     * Override getIconNode() if a custom icon Node is needed.
     */
    String getMdiIcon();

    /**
     * CSS class and colour for the icon background. See {@link IconStyle}.
     */
    default IconStyle getIconStyle() { return IconStyle.BLUE; }

    /**
     * Type tag: built-in tools return {@link ToolType#BUILTIN},
     * external plugins return {@link ToolType#PLUGIN} (default).
     */
    default ToolType getType() { return ToolType.PLUGIN; }

    // ── UI lifecycle ──────────────────────────────────────

    /**
     * Returns the main UI node for this tool.
     * The host embeds it inside the content area StackPane.
     * Created once on first call; the same instance is reused thereafter.
     */
    Node createView();

    /**
     * Called when the tool is brought to the foreground.
     * Use this to resume state, start timers, etc.
     * Default is a no-op; override as needed.
     */
    default void onActivate() {}

    /**
     * Called when the tool is moved to the background.
     * Use this to pause timers, persist temporary state, etc.
     * Default is a no-op; override as needed.
     */
    default void onDeactivate() {}

    /**
     * Called before the plugin is unloaded.
     * Release all resources here: threads, file handles, etc.
     * Default is a no-op; override as needed.
     */
    default void onUnload() {}
}
