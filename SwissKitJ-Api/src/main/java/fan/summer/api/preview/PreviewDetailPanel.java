package fan.summer.api.preview;

import fan.summer.api.MdiIconUtil;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Simplified detail panel for the preview window.
 * Slides in from the right when a tool card is clicked.
 */
class PreviewDetailPanel extends VBox {

    private static final double PANEL_WIDTH = 260;

    private final StackPane iconWrap   = new StackPane();
    private final Label     nameLabel  = new Label();
    private final Label     metaLabel  = new Label();
    private final Label     descLabel  = new Label();
    private final Label     versionVal = new Label();
    private final Label     typeVal    = new Label();
    private final Label     categoryVal = new Label();
    private final Button    launchBtn  = new Button("Launch Tool");
    private final Button    closeBtn   = new Button("✕");

    private Consumer<SwissKitJPlugin> onLaunch;
    private SwissKitJPlugin currentPlugin;
    private boolean panelOpen;

    PreviewDetailPanel() {
        getStyleClass().add("preview-detail-panel");
        setPrefWidth(PANEL_WIDTH);
        setMinWidth(PANEL_WIDTH);
        setMaxWidth(PANEL_WIDTH);
        setTranslateX(PANEL_WIDTH);

        buildUI();
        setVisible(false);
    }

    void setOnLaunch(Consumer<SwissKitJPlugin> handler) {
        this.onLaunch = handler;
    }

    void show(SwissKitJPlugin plugin) {
        this.currentPlugin = plugin;
        fillData(plugin);
        if (!panelOpen) slideIn();
    }

    void hide() {
        if (panelOpen) slideOut();
    }

    boolean isOpen() { return panelOpen; }

    private void buildUI() {
        iconWrap.setPrefSize(56, 56);
        iconWrap.setMinSize(56, 56);

        nameLabel.setStyle("-fx-font-size: 16px; -fx-text-fill: rgba(255,255,255,0.92);");

        metaLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(255,255,255,0.28);");

        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 12.5px; -fx-text-fill: rgba(255,255,255,0.45);");

        launchBtn.getStyleClass().add("preview-launch-btn");
        launchBtn.setMaxWidth(Double.MAX_VALUE);
        launchBtn.setOnAction(e -> {
            if (onLaunch != null && currentPlugin != null)
                onLaunch.accept(currentPlugin);
        });

        closeBtn.setStyle(
            "-fx-background-color: transparent; -fx-border-width: 0;" +
            "-fx-text-fill: rgba(255,255,255,0.35); -fx-cursor: hand; -fx-font-size: 14px;"
        );
        closeBtn.setOnAction(e -> hide());
        closeBtn.setOnMouseEntered(e ->
            closeBtn.setStyle(closeBtn.getStyle() + "-fx-text-fill: rgba(255,255,255,0.85);"));
        closeBtn.setOnMouseExited(e ->
            closeBtn.setStyle(closeBtn.getStyle().replace("-fx-text-fill: rgba(255,255,255,0.85);", "")));

        HBox topRow = new HBox(closeBtn);
        topRow.setAlignment(Pos.CENTER_RIGHT);

        VBox propsBox = new VBox(6,
            propRow("Version", versionVal),
            propRow("Type", typeVal),
            propRow("Category", categoryVal)
        );
        VBox.setMargin(propsBox, new Insets(12, 0, 0, 0));

        setSpacing(10);
        setPadding(new Insets(16));
        getChildren().addAll(topRow, iconWrap, nameLabel, metaLabel, descLabel, launchBtn, propsBox);
    }

    private HBox propRow(String key, Label valLabel) {
        Label keyLabel = new Label(key);
        keyLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.30); -fx-font-size: 12px;");
        valLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px; " +
                          "-fx-font-family: 'SF Mono','Consolas',monospace;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return new HBox(keyLabel, spacer, valLabel);
    }

    private void fillData(SwissKitJPlugin p) {
        Color color = p.getIconStyle().getColor();
        String fillStyle = String.format("-fx-fill: rgba(%d,%d,%d,1.0);",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));

        Text newIcon = MdiIconUtil.createIcon(p.getMdiIcon(), 50);
        newIcon.setStyle(fillStyle);

        DropShadow glow = new DropShadow();
        glow.setColor(color.deriveColor(0, 1, 1, 0.75));
        glow.setRadius(14);
        glow.setSpread(0.18);
        newIcon.setEffect(glow);

        iconWrap.getChildren().setAll(newIcon);

        nameLabel.setText(p.getName());
        metaLabel.setText("v" + p.getVersion() + " · " + p.getType().getId());
        descLabel.setText(p.getDescription());
        versionVal.setText(p.getVersion());
        typeVal.setText(p.getType().getId());
        categoryVal.setText(categoryName(p.getCategory()));
    }

    private static String categoryName(ToolCategory cat) {
        return switch (cat) {
            case DEV   -> "Developer Tools";
            case TEXT  -> "Text Processing";
            case IMAGE -> "Image Processing";
            case NET   -> "Network Tools";
            default    -> "Other Tools";
        };
    }

    private void slideIn() {
        panelOpen = true;
        setVisible(true);
        Timeline tl = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(translateXProperty(), PANEL_WIDTH),
                new KeyValue(opacityProperty(), 0)
            ),
            new KeyFrame(Duration.millis(300),
                new KeyValue(translateXProperty(), 0, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                new KeyValue(opacityProperty(), 1, Interpolator.EASE_OUT)
            )
        );
        tl.play();
    }

    private void slideOut() {
        panelOpen = false;
        Timeline tl = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(translateXProperty(), 0),
                new KeyValue(opacityProperty(), 1)
            ),
            new KeyFrame(Duration.millis(250),
                new KeyValue(translateXProperty(), PANEL_WIDTH, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                new KeyValue(opacityProperty(), 0, Interpolator.EASE_IN)
            )
        );
        tl.setOnFinished(e -> setVisible(false));
        tl.play();
    }
}
