package fan.summer.ui.titlebar;

import fan.summer.api.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Custom macOS-style title bar for the main window.
 * Contains the traffic-light window controls (close/minimize/maximize),
 * a centered application title, and an optional settings button on the right.
 * The entire bar supports window dragging to reposition the stage.
 *
 * @since 1.0
 */
public class TitleBar extends HBox {

    private static final Logger LOG = LoggerFactory.getLogger(TitleBar.class);

    private double dragOffsetX, dragOffsetY;

    /**
     * Constructs the title bar with the given stage and optional settings callback.
     *
     * @param stage      the JavaFX Stage to control with traffic lights and dragging
     * @param onSettings runnable to execute when the settings button is clicked; may be null
     */
    public TitleBar(Stage stage, Runnable onSettings) {
        LOG.info("TitleBar initializing with stage");
        getStyleClass().add("titlebar");
        setAlignment(Pos.CENTER_LEFT);
        setPrefHeight(48);
        setMinHeight(48);
        setPadding(new Insets(0, 12, 0, 16));

        // ── Traffic lights ──────────────────────────────────────
        HBox lights = buildTrafficLights(stage);

        // ── Centered title (wrapped with StackPane for absolute centering) ───
        Label titleLabel = new Label("SwissKitJ");
        titleLabel.getStyleClass().add("titlebar-title");
        titleLabel.setEllipsisString("…");
        StackPane titleWrap = new StackPane(titleLabel);
        titleWrap.setMinWidth(0);
        HBox.setHgrow(titleWrap, Priority.ALWAYS);

        // ── Right-side action buttons ───────────────────────────
        HBox actions = new HBox(4);
        actions.setAlignment(Pos.CENTER_RIGHT);
        if (onSettings != null) {
            Button settingsBtn = titlebarBtn("⚙", I18n.get("titlebar.tooltip.settings"), () -> {
                LOG.info("Settings button clicked");
                onSettings.run();
            });
            actions.getChildren().add(settingsBtn);
        }

        getChildren().addAll(lights, titleWrap, actions);

        // ── Window drag ────────────────────────────────────
        setOnMousePressed(e -> {
            dragOffsetX = e.getSceneX();
            dragOffsetY = e.getSceneY();
        });
        setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });
        LOG.info("TitleBar initialized");
    }

    // ── Traffic lights ────────────────────────────────────────────

    /**
     * Builds the macOS-style traffic light buttons (close, minimize, maximize)
     * and wires their actions to the given stage.
     *
     * @param stage the JavaFX Stage to control
     * @return an HBox containing the three styled traffic light buttons
     */
    private HBox buildTrafficLights(Stage stage) {
        Button close    = trafficLight("traffic-light-close", "✕");
        Button minimize = trafficLight("traffic-light-min", "−");
        Button maximize = trafficLight("traffic-light-max", "+");

        close.setOnAction(e -> {
            LOG.info("Close button clicked");
            stage.close();
        });
        minimize.setOnAction(e -> {
            LOG.debug("Minimize button clicked");
            stage.setIconified(true);
        });
        maximize.setOnAction(e -> {
            LOG.debug("Maximize button clicked, current state: {}", stage.isMaximized());
            stage.setMaximized(!stage.isMaximized());
        });

        HBox box = new HBox(8, close, minimize, maximize);
        box.setAlignment(Pos.CENTER_LEFT);

        // Only show icon on hover
        Label[] icons = {
            findIcon(close), findIcon(minimize), findIcon(maximize)
        };
        box.setOnMouseEntered(e -> { for (Label l : icons) l.setOpacity(1); });
        box.setOnMouseExited( e -> { for (Label l : icons) l.setOpacity(0); });
        return box;
    }

    /**
     * Creates a single traffic light button with the given CSS class and icon.
     * The icon is initially hidden and only appears on hover.
     *
     * @param colorClass  the CSS class determining the button's color ({@code "traffic-light-close"},
     *                    {@code "traffic-light-min"}, {@code "traffic-light-max"})
     * @param iconText    the unicode character to display as the icon (e.g. {@code "✕"})
     * @return a styled Button ready to be placed in the traffic light row
     */
    private Button trafficLight(String colorClass, String iconText) {
        Label icon = new Label(iconText);
        icon.setStyle(
            "-fx-font-size: 9px;" +
            "-fx-font-weight: bold;" +
            "-fx-text-fill: rgba(0,0,0,0.65);" +
            "-fx-alignment: center;"
        );
        icon.setOpacity(0);

        StackPane content = new StackPane(icon);
        content.setPrefSize(12, 12);
        content.setMouseTransparent(true);

        Button btn = new Button();
        btn.setGraphic(content);
        btn.getStyleClass().addAll("traffic-light", colorClass);
        btn.setPrefSize(12, 12);
        btn.setMinSize(12, 12);
        btn.setMaxSize(12, 12);
        btn.setPadding(Insets.EMPTY);
        btn.setStyle("-fx-background-insets: 0; -fx-padding: 0;");
        btn.setFocusTraversable(false);
        return btn;
    }

    private Label findIcon(Button btn) {
        StackPane sp = (StackPane) btn.getGraphic();
        return (Label) sp.getChildren().get(0);
    }

    // ── Title bar tool buttons ────────────────────────────────────

    private Button titlebarBtn(String icon, String tooltip, Runnable action) {
        Button btn = new Button(icon);
        btn.setTooltip(new Tooltip(tooltip));
        btn.setStyle(
            "-fx-background-color: rgba(255,255,255,0.055);" +
            "-fx-border-color: rgba(255,255,255,0.10);" +
            "-fx-border-width: 1; -fx-border-radius: 8;" +
            "-fx-background-radius: 8;" +
            "-fx-text-fill: rgba(255,255,255,0.50);" +
            "-fx-font-size: 13px; -fx-cursor: hand;" +
            "-fx-pref-width: 28px; -fx-pref-height: 28px;" +
            "-fx-min-width: 28px; -fx-min-height: 28px;"
        );
        btn.setOnMouseEntered(e -> btn.setStyle(btn.getStyle()
            .replace("rgba(255,255,255,0.055)", "rgba(255,255,255,0.09)")
            .replace("rgba(255,255,255,0.10)",  "rgba(255,255,255,0.22)")
            .replace("rgba(255,255,255,0.50)",  "rgba(255,255,255,0.92)")
        ));
        btn.setOnMouseExited(e -> btn.setStyle(btn.getStyle()
            .replace("rgba(255,255,255,0.09)", "rgba(255,255,255,0.055)")
            .replace("rgba(255,255,255,0.22)", "rgba(255,255,255,0.10)")
            .replace("rgba(255,255,255,0.92)", "rgba(255,255,255,0.50)")
        ));
        btn.setOnAction(e -> action.run());
        return btn;
    }
}
