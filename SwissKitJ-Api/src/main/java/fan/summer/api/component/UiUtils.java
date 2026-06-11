package fan.summer.api.component;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Shared UI utility factory methods for consistent glassmorphism-styled controls.
 *
 * <p>Use these methods instead of duplicating inline style strings across
 * different plugins and UI components. This ensures visual consistency and
 * makes style changes a single-point edit.</p>
 *
 * @since 3.0
 */
public final class UiUtils {

    private UiUtils() {}

    // ── Glass button styles ─────────────────────────────────────

    private static final String PRIMARY_BTN_STYLE =
        "-fx-background-color: #5b8cf7; -fx-text-fill: white;" +
        "-fx-font-size: 13px; -fx-font-weight: 500;" +
        "-fx-background-radius: 8; -fx-border-width: 0;" +
        "-fx-padding: 9 18 9 18; -fx-cursor: hand;";

    private static final String PRIMARY_BTN_HOVER =
        "-fx-background-color: #4a7bf5; -fx-text-fill: white;" +
        "-fx-font-size: 13px; -fx-font-weight: 500;" +
        "-fx-background-radius: 8; -fx-border-width: 0;" +
        "-fx-padding: 9 18 9 18; -fx-cursor: hand;";

    private static final String SECONDARY_BTN_STYLE =
        "-fx-background-color: rgba(255,255,255,0.07);" +
        "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
        "-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 13px;" +
        "-fx-background-radius: 8; -fx-border-radius: 8;" +
        "-fx-padding: 9 18 9 18; -fx-cursor: hand;";

    /**
     * Creates a glassmorphism-styled button.
     *
     * @param text    the button label
     * @param primary true for the accent (blue) style; false for the bordered ghost style
     * @return a styled Button
     */
    public static Button glassBtn(String text, boolean primary) {
        Button btn = new Button(text);
        if (primary) {
            btn.setStyle(PRIMARY_BTN_STYLE);
            btn.setOnMouseEntered(e -> btn.setStyle(PRIMARY_BTN_HOVER));
            btn.setOnMouseExited(e -> btn.setStyle(PRIMARY_BTN_STYLE));
        } else {
            btn.setStyle(SECONDARY_BTN_STYLE);
        }
        return btn;
    }

    /**
     * Creates a horizontal spacer that fills remaining space in an HBox.
     *
     * @return a Region with {@code HBox.hgrow = ALWAYS}
     */
    public static Region hSpacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /**
     * Creates a styled sub-label (small, muted text for form fields).
     *
     * @param text the label text
     * @return a muted Label
     */
    public static Label subLabel(String text) {
        Label l = new Label(text);
        l.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.50);" +
            " -fx-font-size: 11px; -fx-font-weight: bold;");
        return l;
    }

    /**
     * Creates a styled section title label.
     *
     * @param text the title text
     * @return a styled Label
     */
    public static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.88);" +
            " -fx-font-size: 15px; -fx-font-weight: 500;");
        return l;
    }

    /**
     * Returns the shared CSS style string for text field / combo-box inputs.
     *
     * @return a CSS inline style string
     */
    public static String fieldStyle() {
        return "-fx-background-color: rgba(255,255,255,0.05);" +
               "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
               "-fx-border-radius: 8; -fx-background-radius: 8;" +
               "-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 13px;" +
               "-fx-padding: 9 12 9 12;";
    }

    /**
     * Returns the shared CSS style string for combo-box controls.
     *
     * @return a CSS inline style string
     */
    public static String comboStyle() {
        return "-fx-background-color: rgba(255,255,255,0.05);" +
               "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
               "-fx-border-radius: 8; -fx-background-radius: 8;" +
               "-fx-text-fill: rgba(255,255,255,0.88);";
    }
}
