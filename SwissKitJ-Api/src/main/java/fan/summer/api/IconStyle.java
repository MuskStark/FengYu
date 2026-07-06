package fan.summer.api;

import javafx.scene.paint.Color;

/**
 * Predefined visual styles for tool icon backgrounds.
 *
 * <p>Each value carries a CSS class name (applied to the icon wrapper element) and
 * an associated {@link Color} used for the icon glyph fill and the {@code DropShadow}
 * glow rendered by the host.</p>
 *
 * <p>The CSS class names ({@code ic-blue}, {@code ic-purple}, etc.) are defined in
 * {@code shell.css} and control background colour, border, and opacity of the icon tile.</p>
 *
 * @see SwissKitJPlugin#getIconStyle()
 */
public enum IconStyle {
    /** Blue — RGB (99, 130, 255), CSS class {@code ic-blue}. */
    BLUE("ic-blue",   Color.rgb(99, 130, 255)),

    /** Purple — RGB (160, 110, 255), CSS class {@code ic-purple}. */
    PURPLE("ic-purple", Color.rgb(160, 110, 255)),

    /** Teal — RGB (40, 210, 140), CSS class {@code ic-teal}. */
    TEAL("ic-teal",   Color.rgb(40, 210, 140)),

    /** Amber — RGB (255, 185, 50), CSS class {@code ic-amber}. */
    AMBER("ic-amber", Color.rgb(255, 185, 50)),

    /** Red — RGB (255, 100, 100), CSS class {@code ic-red}. */
    RED("ic-red",    Color.rgb(255, 100, 100)),

    /** Pink — RGB (245, 100, 160), CSS class {@code ic-pink}. */
    PINK("ic-pink",   Color.rgb(245, 100, 160)),

    /** Neutral gray — RGB (200, 200, 210), CSS class {@code ic-gray}. */
    GRAY("ic-gray",   Color.rgb(200, 200, 210));

    private final String cssClass;
    private final Color color;

    IconStyle(String cssClass, Color color) {
        this.cssClass = cssClass;
        this.color = color;
    }

    /**
     * Returns the CSS class applied to the icon wrapper element, e.g. {@code "ic-blue"}.
     *
     * @return the CSS class name
     */
    public String getCssClass() { return cssClass; }

    /**
     * Returns the {@link Color} used for the icon glyph {@code Text} fill and the
     * surrounding {@code DropShadow} glow effect.
     *
     * @return the accent colour
     */
    public Color getColor() { return color; }

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
