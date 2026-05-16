package fan.summer.ui.content;

import fan.summer.api.MdiIconUtil;
import fan.summer.api.SwissKitJPlugin;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Tool detail panel, slides in from right.
 * show(plugin) fills data and expands; hide() collapses.
 */
public class DetailPanel extends VBox {

    private static final double PANEL_WIDTH = 260;

    private final Text     iconText    = new Text();
    private final StackPane iconWrap   = new StackPane(iconText);
    private final Label   nameLabel   = new Label();
    private final Label   metaLabel   = new Label();
    private final Label   descLabel   = new Label();
    private final Label   versionVal  = new Label();
    private final Label   typeVal     = new Label();
    private final Label   categoryVal = new Label();
    private final Button  launchBtn  = new Button("Launch Tool");
    private final Button  closeBtn   = new Button("✕");

    private Consumer<SwissKitJPlugin> onLaunch;
    private SwissKitJPlugin currentPlugin;
    private boolean    panelOpen = false;

    public DetailPanel() {
        getStyleClass().add("detail-panel");
        setPrefWidth(0);   // Initially collapsed
        setMinWidth(0);
        setMaxWidth(PANEL_WIDTH);

        buildUI();
        setVisible(false);
    }

    // ── Public API ──────────────────────────────────────────

    public void setOnLaunch(Consumer<SwissKitJPlugin> handler) {
        this.onLaunch = handler;
    }

    /** Show plugin details, auto-slides in on first call */
    public void show(SwissKitJPlugin plugin) {
        this.currentPlugin = plugin;
        fillData(plugin);
        if (!panelOpen) slideIn();
    }

    /** Slide out and hide */
    public void hide() {
        if (panelOpen) slideOut();
    }

    public boolean isPanelOpen() { return panelOpen; }

    // ── Build UI ───────────────────────────────────────────

    private void buildUI() {
        iconText.setStyle("-fx-font-size: 26px; -fx-fill: white;");
        iconWrap.setPrefSize(56, 56);
        iconWrap.setMinSize(56, 56);
        iconWrap.getStyleClass().add("tool-icon-wrap");
        iconWrap.getStyleClass().add("ic-blue");

        nameLabel.getStyleClass().add("tool-name");
        nameLabel.setStyle("-fx-font-size: 16px;");

        metaLabel.getStyleClass().add("status-text");
        metaLabel.setStyle("-fx-font-size: 11px;");

        descLabel.getStyleClass().add("tool-desc");
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 12.5px;");

        launchBtn.getStyleClass().add("detail-launch-btn");
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

        Separator sep = new Separator();
        sep.setStyle("-fx-border-color: rgba(255,255,255,0.10); -fx-padding: 8 0 8 0;");

        // Property rows
        VBox propsBox = new VBox(6,
            propRow("Version",   versionVal),
            propRow("Type",   typeVal),
            propRow("Category",   categoryVal)
        );

        setSpacing(10);
        setPadding(new Insets(16));
        getChildren().addAll(
            topRow, iconWrap, nameLabel, metaLabel, descLabel,
            launchBtn, sep, propsBox
        );
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

    // ── Data fill ─────────────────────────────────────────

    private void fillData(SwissKitJPlugin p) {
        iconText.setText(MdiIconUtil.getCodepoint(p.getMdiIcon()));
        iconText.setFont(MdiIconUtil.getFont(28));
        iconWrap.getStyleClass().removeIf(c -> c.startsWith("ic-"));
        iconWrap.getStyleClass().add(p.getIconStyle());

        nameLabel.setText(p.getName());
        metaLabel.setText("v" + p.getVersion() + " · " + p.getType());
        descLabel.setText(p.getDescription());

        versionVal.setText(p.getVersion());
        typeVal.setText(p.getType());
        categoryVal.setText(categoryName(p.getCategory()));
    }

    private String categoryName(String cat) {
        return switch (cat) {
            case "dev"   -> "Developer Tools";
            case "text"  -> "Text Processing";
            case "image" -> "Image Processing";
            case "net"   -> "Network Tools";
            default      -> cat;
        };
    }

    // ── Slide in / out animations ─────────────────────────

    private void slideIn() {
        panelOpen = true;
        setVisible(true);

        Timeline tl = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(prefWidthProperty(), 0),
                new KeyValue(opacityProperty(), 0)
            ),
            new KeyFrame(Duration.millis(300),
                new KeyValue(prefWidthProperty(), PANEL_WIDTH,
                    Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                new KeyValue(opacityProperty(), 1,
                    Interpolator.EASE_OUT)
            )
        );
        tl.play();
    }

    private void slideOut() {
        panelOpen = false;
        Timeline tl = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(prefWidthProperty(), PANEL_WIDTH),
                new KeyValue(opacityProperty(), 1)
            ),
            new KeyFrame(Duration.millis(250),
                new KeyValue(prefWidthProperty(), 0,
                    Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                new KeyValue(opacityProperty(), 0,
                    Interpolator.EASE_IN)
            )
        );
        tl.setOnFinished(e -> setVisible(false));
        tl.play();
    }
}
