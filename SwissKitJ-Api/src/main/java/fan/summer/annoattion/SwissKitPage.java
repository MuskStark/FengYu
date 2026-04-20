package fan.summer.annoattion;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark a class as a KitPage implementation.
 * Used for automatic page registration and menu configuration.
 *
 * <h2>Usage Example:</h2>
 * <pre>
 * {@code @SwissKitPage(menuName = "Excel", menuTooltip = "Excel file processing", order = 1)}
 * public class ExcelKitPage extends JPanel {
 *     public ExcelKitPage() {
 *         // initialize your UI
 *     }
 * }
 * </pre>
 *
 * @author phoebej
 * @since 1.0.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SwissKitPage {

    /**
     * Unique identifier for the plugin.
     * Used for plugin lifecycle management and dependency resolution.
     *
     * @return plugin name
     */
    String pluginName();

    /**
     * Version string of the plugin in semver format (e.g., "1.0.0").
     * Used for compatibility checks and update detection.
     *
     * @return plugin version
     */
    String pluginVersion();

    /**
     * Menu display name shown in the sidebar navigation.
     * If empty, the simple class name will be used as fallback.
     *
     * @return menu display name
     */
    String menuName();

    /**
     * Menu tooltip text shown on hover.
     *
     * @return tooltip text
     */
    String menuTooltip() default "";

    /**
     * Whether this page is visible in the menu.
     * Set to false to hide from menu but keep the page accessible programmatically.
     *
     * @return true if visible, false otherwise
     */
    boolean visible() default true;

    /**
     * Menu item order for sorting.
     * Lower values appear first in the menu.
     *
     * @return order value
     */
    int order() default 0;

    /**
     * Menu icon path (optional).
     * Can be a classpath resource path or file path.
     *
     * @return icon path, or empty string for no icon
     */
    String iconPath() default "";
}
