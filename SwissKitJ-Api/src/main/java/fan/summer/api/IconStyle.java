package fan.summer.api;

import javafx.scene.paint.Color;

/**
 * CSS class names and associated colours for tool icon tints.
 */
public enum IconStyle {
    BLUE("ic-blue",   Color.rgb(99, 130, 255)),
    PURPLE("ic-purple", Color.rgb(160, 110, 255)),
    TEAL("ic-teal",   Color.rgb(40, 210, 140)),
    AMBER("ic-amber", Color.rgb(255, 185, 50)),
    RED("ic-red",    Color.rgb(255, 100, 100)),
    PINK("ic-pink",   Color.rgb(245, 100, 160)),
    GRAY("ic-gray",   Color.rgb(200, 200, 210));

    private final String cssClass;
    private final Color color;

    IconStyle(String cssClass, Color color) {
        this.cssClass = cssClass;
        this.color = color;
    }

    /** CSS class applied to the icon wrapper, e.g. "ic-blue". */
    public String getCssClass() { return cssClass; }

    /** The colour used for Text fill and DropShadow glow. */
    public Color getColor() { return color; }

    /** Convert from a legacy CSS class string. Returns {@link #BLUE} for unrecognized input. */
    public static IconStyle fromCssClass(String cssClass) {
        if (cssClass == null) return BLUE;
        for (IconStyle s : values()) {
            if (s.cssClass.equalsIgnoreCase(cssClass)) return s;
        }
        return BLUE;
    }
}
