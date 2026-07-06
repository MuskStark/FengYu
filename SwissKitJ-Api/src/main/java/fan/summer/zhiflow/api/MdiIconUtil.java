package fan.summer.zhiflow.api;

import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;

/**
 * Utility class for rendering Material Design Icons (MDI) glyphs in JavaFX.
 *
 * <p>This class maps icon names (e.g. {@code "file-excel"}) to their Unicode
 * codepoints in the <b>Material Design Icons</b> webfont
 * ({@code materialdesignicons-webfont.ttf}, bundled as a classpath resource at
 * {@code /fonts/}).</p>
 *
 * <p>The codepoint map is loaded lazily from
 * {@code /fonts/mdi-codemap.properties} on first use. Additional icons can be
 * registered at runtime via {@link #putIcon(String, String)}.</p>
 *
 * <p>Example usage:
 * <pre>{@code
 * Text icon = MdiIconUtil.createIcon("file-excel", 24.0);
 * icon.setStyle("-fx-fill: #4CAF50;");
 * }</pre>
 *
 * @see <a href="https://pictogrammers.com/library/mdi/">Material Design Icons — Pictogrammers</a>
 */
public class MdiIconUtil {

    private static final String FONT_FAMILY = "MaterialDesignIcons";
    private static final PluginLogger log = LoggerFactory.getLogger(MdiIconUtil.class);

    private static volatile boolean fontLoaded = false;
    private static volatile Font loadedMdiFont = null;

    private static final Map<String, String> CODEMAP = new HashMap<>();

    static {
        loadCodeMap();
    }

    /**
     * Loads the icon codepoint map from the bundled properties resource.
     * Uses {@link java.util.Properties} so that {@code \\uXXXX} escapes are
     * correctly decoded into Unicode surrogate pairs.
     */
    private static void loadCodeMap() {
        try (InputStream is = MdiIconUtil.class.getResourceAsStream("/fonts/mdi-codemap.properties")) {
            if (is == null) {
                throw new IllegalStateException("mdi-codemap.properties not found in classpath");
            }
            java.util.Properties props = new java.util.Properties();
            props.load(is);
            for (String key : props.stringPropertyNames()) {
                CODEMAP.put(key, props.getProperty(key));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load MDI codepoint map", e);
        }
    }

    private static void ensureFontLoaded() {
        if (!fontLoaded) {
            try (InputStream fontStream = MdiIconUtil.class.getResourceAsStream("/fonts/materialdesignicons-webfont.ttf")) {
                if (fontStream != null) {
                    loadedMdiFont = Font.loadFont(fontStream, 12);
                }
            } catch (Exception e) {
                // The only realistic failure is a missing/corrupt bundled font; log it so a
                // packaging bug is diagnosable instead of silently falling back to system font.
                log.warn("Failed to load MDI webfont; icons will fall back to the system font", e);
            }
            fontLoaded = true;
        }
    }

    /**
     * Creates a JavaFX {@link Text} node rendering the named MDI icon at the given size.
     *
     * @param iconName the icon name from the MDI library, e.g. {@code "folder-open"}
     * @param size     the font size in logical pixels
     * @return a {@code Text} node with the icon glyph; falls back to the {@code "star"} icon
     *         if the name is not found
     */
    public static Text createIcon(String iconName, double size) {
        return createIcon(iconName, size, null);
    }

    /**
     * Creates a JavaFX {@link Text} node rendering the named MDI icon at the given size,
     * with optional additional inline CSS applied to the {@code Text} node.
     *
     * @param iconName   the icon name from the MDI library, e.g. {@code "folder-open"}
     * @param size       the font size in logical pixels
     * @param extraStyle additional inline CSS to append to the default white fill
     *                   (e.g. {@code "-fx-fill: #FF5722;"}); may be {@code null}
     * @return a {@code Text} node with the icon glyph; falls back to the {@code "star"} icon
     *         if the name is not found
     */
    public static Text createIcon(String iconName, double size, String extraStyle) {
        String codepoint = CODEMAP.getOrDefault(iconName, CODEMAP.get("star"));
        Text text = new Text(codepoint);

        ensureFontLoaded();

        if (loadedMdiFont != null) {
            text.setFont(Font.font(loadedMdiFont.getFamily(), size));
        } else {
            text.setFont(Font.font("Material Design Icons", size));
        }
        text.setStyle("-fx-fill: white;" + (extraStyle != null ? extraStyle : ""));
        return text;
    }

    /**
     * Adds or overrides a single icon name → codepoint mapping at runtime.
     * Useful for plugins that extend the icon set with custom glyphs.
     *
     * @param name      the icon name to register, e.g. {@code "my-custom-icon"}
     * @param codepoint the Unicode string representation of the glyph, e.g. {@code "󰇉"}
     */
    public static void putIcon(String name, String codepoint) {
        CODEMAP.put(name, codepoint);
    }

    /**
     * Returns the Unicode codepoint string for the given icon name.
     *
     * @param iconName the icon name, e.g. {@code "check-bold"}
     * @return the codepoint string; falls back to the {@code "star"} icon if unknown
     * @see #putIcon(String, String)
     */
    public static String getCodepoint(String iconName) {
        return CODEMAP.getOrDefault(iconName, CODEMAP.get("star"));
    }

    /**
     * Loads and returns a {@link Font} instance for the MDI webfont at the specified size.
     *
     * @param size the desired font size in logical pixels
     * @return the loaded {@code Font}, or {@code null} if the font resource cannot be found
     */
    public static Font getFont(double size) {
        ensureFontLoaded();
        if (loadedMdiFont != null) {
            return Font.font(loadedMdiFont.getFamily(), size);
        }
        return null;
    }

}
