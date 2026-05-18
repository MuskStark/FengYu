package fan.summer.api.config;

import javafx.geometry.Insets;

public final class UIConfig {
    private static final UIConfig INSTANCE = new UIConfig();

    // Color palette (IDEA 2026.1 inspired)
    public static final String PRIMARY_COLOR = "#4895EF";
    public static final String TEXT_COLOR = "#212529";
    public static final String SECONDARY_TEXT = "#6C757D";
    public static final String LIGHT_GRAY = "#F8F9FA";
    public static final String DIVIDER_COLOR = "#E9ECEF";
    public static final String SUCCESS_COLOR = "#3DC269";
    public static final String WARNING_COLOR = "#FFA726";
    public static final String ERROR_COLOR = "#EF5350";
    public static final String SELECTED_BG = "#4895EF";
    public static final String SELECTED_TEXT = "#FFFFFF";
    public static final String HOVER_BG = "#E8E8E8";
    public static final String CARD_BG = "#FFFFFF";

    // Typography
    public static final double TITLE_FONT_SIZE = 18.0;
    public static final double HEADER_FONT_SIZE = 16.0;
    public static final double BODY_FONT_SIZE = 14.0;
    public static final double SMALL_FONT_SIZE = 12.0;

    // Spacing
    public static final Insets CONTENT_PADDING = new Insets(20);
    public static final Insets CARD_PADDING = new Insets(16);
    public static final Insets MENU_ITEM_PADDING = new Insets(12, 10, 12, 10);
    public static final double MENU_WIDTH = 160.0;
    public static final double MENU_ITEM_HEIGHT = 44.0;

    // Animation
    public static final int ANIMATION_DURATION_MS = 200;

    private double globalScale = 1.0;

    private UIConfig() {
    }

    public static UIConfig getInstance() {
        return INSTANCE;
    }

    public double getGlobalScale() {
        return globalScale;
    }

    public void setGlobalScale(double scale) {
        this.globalScale = Math.max(0.5, Math.min(2.0, scale));
    }

    public int scale(int value) {
        return (int) Math.round(value * globalScale);
    }

    public double scale(double value) {
        return value * globalScale;
    }
}