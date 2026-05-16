package fan.summer.ui.titlebar;

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
 * Custom macOS-style title bar.
 * Contains: traffic light buttons / centered title / right-side action buttons.
 * Supports dragging to move window (with FX-BorderlessScene or directly bound to Stage).
 */
public class TitleBar extends HBox {

    private double dragOffsetX, dragOffsetY;

    public TitleBar(Stage stage, Runnable onSettings) {
        getStyleClass().add("titlebar");
        setAlignment(Pos.CENTER_LEFT);
        setPrefHeight(44);
        setMinHeight(44);
        setPadding(new Insets(0, 12, 0, 16));

        // ── Traffic lights ──────────────────────────────────────
        HBox lights = buildTrafficLights(stage);

        // ── Centered title (wrapped with StackPane for absolute centering) ───
        Label titleLabel = new Label("SwissKitJ");
        titleLabel.getStyleClass().add("titlebar-title");
        StackPane titleWrap = new StackPane(titleLabel);
        HBox.setHgrow(titleWrap, Priority.ALWAYS);


        getChildren().addAll(lights, titleWrap);

        // ── Window drag ────────────────────────────────────
        setOnMousePressed(e -> {
            dragOffsetX = e.getSceneX();
            dragOffsetY = e.getSceneY();
        });
        setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });
    }

    // ── Traffic lights ────────────────────────────────────────────

    private HBox buildTrafficLights(Stage stage) {
        Button close = trafficLight("traffic-light-close", "✕");   // ✕


        close.setOnAction(e -> stage.close());

        HBox box = new HBox(8, close);
        box.setAlignment(Pos.CENTER_LEFT);

        // Only show icon on hover
        Label[] icons = {
            findIcon(close)
        };
        box.setOnMouseEntered(e -> { for (Label l : icons) l.setOpacity(1); });
        box.setOnMouseExited( e -> { for (Label l : icons) l.setOpacity(0); });
        return box;
    }

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
        // Extract Label from StackPane
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
