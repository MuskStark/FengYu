package fan.summer.fengyu.api;

/**
 * Predefined visual styles for tool icon backgrounds.
 *
 * <p>Each value carries a CSS class name (applied to the icon wrapper element) and an accent
 * colour as RGB components. The colour is exposed as raw components ({@link #getRed()} /
 * {@link #getGreen()} / {@link #getBlue()}) and as a CSS hex string ({@link #getColorHex()}) so
 * this enum has <strong>no JavaFX dependency</strong> and can be loaded in the headless backend
 * (it is referenced by the v2 {@code PluginDescriptor}). JavaFX callers build a {@code Color}
 * from the components as needed.</p>
 *
 * <p>The CSS class names ({@code ic-blue}, {@code ic-purple}, etc.) are defined in the frontend
 * theme and control background colour, border, and opacity of the icon tile.</p>
 */
public enum IconStyle {
    /** Blue — RGB (99, 130, 255), CSS class {@code ic-blue}. */
    BLUE("ic-blue", 99, 130, 255),

    /** Purple — RGB (160, 110, 255), CSS class {@code ic-purple}. */
    PURPLE("ic-purple", 160, 110, 255),

    /** Teal — RGB (40, 210, 140), CSS class {@code ic-teal}. */
    TEAL("ic-teal", 40, 210, 140),

    /** Amber — RGB (255, 185, 50), CSS class {@code ic-amber}. */
    AMBER("ic-amber", 255, 185, 50),

    /** Red — RGB (255, 100, 100), CSS class {@code ic-red}. */
    RED("ic-red", 255, 100, 100),

    /** Pink — RGB (245, 100, 160), CSS class {@code ic-pink}. */
    PINK("ic-pink", 245, 100, 160),

    /** Neutral gray — RGB (200, 200, 210), CSS class {@code ic-gray}. */
    GRAY("ic-gray", 200, 200, 210);

    private final String cssClass;
    private final int red;
    private final int green;
    private final int blue;

    IconStyle(String cssClass, int red, int green, int blue) {
        this.cssClass = cssClass;
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    /** Returns the CSS class applied to the icon wrapper element, e.g. {@code "ic-blue"}. */
    public String getCssClass() { return cssClass; }

    /** @return the red component (0–255). */
    public int getRed() { return red; }

    /** @return the green component (0–255). */
    public int getGreen() { return green; }

    /** @return the blue component (0–255). */
    public int getBlue() { return blue; }

    /** @return the accent colour as a CSS hex string, e.g. {@code "#6382ff"}. */
    public String getColorHex() {
        return String.format("#%02x%02x%02x", red, green, blue);
    }

    /**
     * Looks up an {@link IconStyle} by its CSS class name.
     *
     * @param cssClass the CSS class string, e.g. {@code "ic-purple"} (case-insensitive)
     * @return the matching {@code IconStyle}, or {@link #BLUE} if not found or {@code null}
     */
    public static IconStyle fromCssClass(String cssClass) {
        if (cssClass == null) return BLUE;
        for (IconStyle s : values()) {
            if (s.cssClass.equalsIgnoreCase(cssClass)) return s;
        }
        return BLUE;
    }
}
