package fan.summer.ui.content;

import fan.summer.api.MdiIconUtil;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.component.GlassNotification;
import fan.summer.api.i18n.I18n;
import fan.summer.plugin.FavoriteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.text.MessageFormat;
import java.util.function.Consumer;

/**
 * Slide-in detail panel shown on the right side of the ContentArea when a
 * tool card is selected. Displays the plugin's icon, name, version, type,
 * category, and description, with Launch, Uninstall, and Favorite buttons.
 * <p>
 * The panel is initially parked offscreen to the right and animates in
 * via {@link #slideIn()}; calling {@link #hide()} triggers the reverse
 * animation and then hides the panel.
 *
 * @see ToolCard
 * @see ContentArea
 * @since 1.0
 */
public class DetailPanel extends VBox {

    private static final Logger LOG = LoggerFactory.getLogger(DetailPanel.class);

    private static final double PANEL_WIDTH = 260;

    private Text        iconText;
    private final StackPane iconWrap   = new StackPane();
    private final Label   nameLabel   = new Label();
    private final Label   metaLabel   = new Label();
    private final Label   descLabel   = new Label();
    private final Label   versionVal  = new Label();
    private final Label   typeVal     = new Label();
    private final Label   categoryVal = new Label();
    private final Button  launchBtn  = new Button(I18n.get("detail.btn.launch"));
    private final Button  uninstallBtn = new Button(I18n.get("detail.btn.uninstall"));
    private final Button  favoriteBtn = new Button();
    private final Button  closeBtn   = new Button("✕");

    private Consumer<SwissKitJPlugin> onLaunch;
    private Consumer<SwissKitJPlugin> onUninstall;
    private Consumer<SwissKitJPlugin> onFavoriteToggle;
    private SwissKitJPlugin currentPlugin;
    private boolean    panelOpen = false;

    public DetailPanel() {
        LOG.info("DetailPanel initializing");
        getStyleClass().add("detail-panel");
        setPrefWidth(PANEL_WIDTH);
        setMinWidth(PANEL_WIDTH);
        setMaxWidth(PANEL_WIDTH);
        // Park offscreen to the right; slideIn animates translateX → 0.
        setTranslateX(PANEL_WIDTH);

        buildUI();
        setVisible(false);

        I18n.addListener(() -> {
            if (panelOpen && currentPlugin != null) {
                fillData(currentPlugin);
                launchBtn.setText(I18n.get("detail.btn.launch"));
                uninstallBtn.setText(I18n.get("detail.btn.uninstall"));
            }
        });
        LOG.info("DetailPanel initialized");
    }

    /**
     * Sets the consumer to invoke when the user clicks the Launch button.
     *
     * @param handler the consumer that receives the currently displayed plugin
     */
    public void setOnLaunch(Consumer<SwissKitJPlugin> handler) {
        LOG.debug("setOnLaunch callback set");
        this.onLaunch = handler;
    }

    /**
     * Sets the consumer to invoke when the user confirms plugin uninstall.
     *
     * @param handler the consumer that receives the plugin to uninstall
     */
    public void setOnUninstall(Consumer<SwissKitJPlugin> handler) {
        this.onUninstall = handler;
    }

    /**
     * Sets the consumer to invoke when the user toggles the favorite state.
     *
     * @param handler the consumer that receives the affected plugin
     */
    public void setOnFavoriteToggle(Consumer<SwissKitJPlugin> handler) {
        this.onFavoriteToggle = handler;
    }

    /**
     * Displays the detail panel for the given plugin, populating all fields
     * and animating the panel into view if it is currently hidden.
     *
     * @param plugin the plugin to display; must not be null
     */
    public void show(SwissKitJPlugin plugin) {
        LOG.info("Showing detail panel for plugin: name={}, id={}", plugin.getName(), plugin.getId());
        this.currentPlugin = plugin;
        fillData(plugin);
        if (!panelOpen) slideIn();
    }

    /**
     * Hides the detail panel, animating it back offscreen to the right.
     */
    public void hide() {
        LOG.info("Hiding detail panel");
        if (panelOpen) slideOut();
    }

    public boolean isPanelOpen() { return panelOpen; }

    private void buildUI() {
        iconWrap.setPrefSize(56, 56);
        iconWrap.setMinSize(56, 56);
        iconWrap.getStyleClass().add("tool-icon-wrap");

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

        uninstallBtn.setMaxWidth(Double.MAX_VALUE);
        uninstallBtn.setStyle(
            "-fx-background-color: rgba(242,92,92,0.12);" +
            "-fx-border-color: rgba(242,92,92,0.30); -fx-border-width: 1;" +
            "-fx-text-fill: #f25c5c; -fx-font-size: 13px;" +
            "-fx-background-radius: 8; -fx-border-radius: 8;" +
            "-fx-padding: 8 20 8 20; -fx-cursor: hand;"
        );
        uninstallBtn.setOnAction(e -> showUninstallConfirm());

        // Favorite toggle button
        favoriteBtn.setMaxWidth(Double.MAX_VALUE);
        favoriteBtn.setStyle(
            "-fx-background-color: rgba(245,200,66,0.10);" +
            "-fx-border-color: rgba(245,200,66,0.30); -fx-border-width: 1;" +
            "-fx-text-fill: #f5c842; -fx-font-size: 13px;" +
            "-fx-background-radius: 8; -fx-border-radius: 8;" +
            "-fx-padding: 8 20 8 20; -fx-cursor: hand;"
        );
        favoriteBtn.setOnAction(e -> {
            if (currentPlugin == null) return;
            FavoriteService svc = FavoriteService.getInstance();
            if (svc == null) return;
            svc.toggle(currentPlugin.getId());
            updateFavoriteBtnStyle();
            if (onFavoriteToggle != null) onFavoriteToggle.accept(currentPlugin);
        });

        VBox buttonBox = new VBox(8, launchBtn, uninstallBtn, favoriteBtn);

        closeBtn.getStyleClass().add("sk-t3");
        closeBtn.setStyle(
            "-fx-background-color: transparent; -fx-border-width: 0;" +
            "-fx-cursor: hand; -fx-font-size: 14px;"
        );
        closeBtn.setOnAction(e -> hide());
        final String closeBtnNormal = "-fx-background-color: transparent; -fx-border-width: 0;" +
            "-fx-cursor: hand; -fx-font-size: 14px;";
        final String closeBtnHover = "-fx-background-color: transparent; -fx-border-width: 0;" +
            "-fx-cursor: hand; -fx-font-size: 14px;";
        closeBtn.setOnMouseEntered(e -> {
            closeBtn.getStyleClass().remove("sk-t3");
            if (!closeBtn.getStyleClass().contains("sk-t1")) closeBtn.getStyleClass().add("sk-t1");
            closeBtn.setStyle(closeBtnHover);
        });
        closeBtn.setOnMouseExited(e -> {
            closeBtn.getStyleClass().remove("sk-t1");
            if (!closeBtn.getStyleClass().contains("sk-t3")) closeBtn.getStyleClass().add("sk-t3");
            closeBtn.setStyle(closeBtnNormal);
        });

        HBox topRow = new HBox(closeBtn);
        topRow.setAlignment(Pos.CENTER_RIGHT);

        VBox propsBox = new VBox(6,
            propRow(I18n.get("detail.prop.version"),   versionVal),
            propRow(I18n.get("detail.prop.type"),      typeVal),
            propRow(I18n.get("detail.prop.category"),  categoryVal)
        );
        VBox.setMargin(propsBox, new Insets(12, 0, 0, 0));

        setSpacing(10);
        setPadding(new Insets(16));
        getChildren().addAll(
            topRow, iconWrap, nameLabel, metaLabel, descLabel,
            buttonBox, propsBox
        );
    }

    private void updateFavoriteBtnStyle() {
        if (currentPlugin == null) return;
        FavoriteService svc = FavoriteService.getInstance();
        boolean isFav = svc != null && svc.isFavorite(currentPlugin.getId());
        if (isFav) {
            favoriteBtn.setText(I18n.get("detail.btn.removeFavorite"));
            favoriteBtn.setStyle(
                "-fx-background-color: rgba(245,200,66,0.18);" +
                "-fx-border-color: rgba(245,200,66,0.40); -fx-border-width: 1;" +
                "-fx-text-fill: #f5c842; -fx-font-size: 13px;" +
                "-fx-background-radius: 8; -fx-border-radius: 8;" +
                "-fx-padding: 8 20 8 20; -fx-cursor: hand;"
            );
        } else {
            favoriteBtn.setText(I18n.get("detail.btn.addFavorite"));
            favoriteBtn.setStyle(
                "-fx-background-color: rgba(245,200,66,0.10);" +
                "-fx-border-color: rgba(245,200,66,0.30); -fx-border-width: 1;" +
                "-fx-text-fill: #f5c842; -fx-font-size: 13px;" +
                "-fx-background-radius: 8; -fx-border-radius: 8;" +
                "-fx-padding: 8 20 8 20; -fx-cursor: hand;"
            );
        }
    }

    private HBox propRow(String key, Label valLabel) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("sk-t3");
        keyLabel.setStyle("-fx-font-size: 12px;");
        valLabel.getStyleClass().add("sk-t2");
        valLabel.setStyle("-fx-font-size: 12px; " +
                          "-fx-font-family: 'SF Mono','Consolas',monospace;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return new HBox(keyLabel, spacer, valLabel);
    }

    private void fillData(SwissKitJPlugin p) {
        // Rebuild icon the same way as ToolCard
        Color color = p.getIconStyle().getColor();
        String fillStyle = String.format("-fx-fill: rgba(%d,%d,%d,1.0);",
                (int)(color.getRed()*255),
                (int)(color.getGreen()*255),
                (int)(color.getBlue()*255));

        Text newIcon = MdiIconUtil.createIcon(p.getMdiIcon(), 50);
        newIcon.setStyle(fillStyle);

        DropShadow glow = new DropShadow();
        glow.setColor(color.deriveColor(0, 1, 1, 0.75));
        glow.setRadius(14);
        glow.setSpread(0.18);
        newIcon.setEffect(glow);

        iconWrap.getChildren().setAll(newIcon);
        this.iconText = newIcon;

        nameLabel.setText(p.getName());
        metaLabel.setText("v" + p.getVersion() + " · " + p.getType().getId());
        descLabel.setText(p.getDescription());

        versionVal.setText(p.getVersion());
        typeVal.setText(p.getType().getId());
        categoryVal.setText(categoryName(p.getCategory()));

        // Show uninstall button only for external plugins
        uninstallBtn.setVisible(p.getType().isPlugin());
        uninstallBtn.setManaged(p.getType().isPlugin());

        // Update favorite button state
        updateFavoriteBtnStyle();
    }

    private void showUninstallConfirm() {
        if (currentPlugin == null) return;

        String title = I18n.get("detail.uninstall.confirmTitle");
        String msg = MessageFormat.format(I18n.get("detail.uninstall.confirmMsg"), currentPlugin.getName());

        boolean confirmed = GlassNotification.confirm(this, title, msg);
        if (confirmed) {
            doUninstall();
        }
    }

    private void doUninstall() {
        if (currentPlugin == null) return;
        SwissKitJPlugin plugin = currentPlugin;
        String pluginName = plugin.getName();

        try {
            if (onUninstall != null) {
                onUninstall.accept(plugin);
            }
            hide();
            LOG.info("Plugin uninstalled: {}", pluginName);
        } catch (Exception ex) {
            LOG.error("Uninstall failed for plugin {}: {}", pluginName, ex.getMessage(), ex);
        }
    }

    private String categoryName(ToolCategory cat) {
        return switch (cat) {
            case DEV   -> I18n.get("detail.category.dev");
            case TEXT  -> I18n.get("detail.category.text");
            case IMAGE -> I18n.get("detail.category.image");
            case NET   -> I18n.get("detail.category.net");
            default    -> I18n.get("detail.category.other");
        };
    }

    private void slideIn() {
        LOG.debug("Slide-in animation starting");
        panelOpen = true;
        setVisible(true);

        Timeline tl = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(translateXProperty(), PANEL_WIDTH),
                new KeyValue(opacityProperty(), 0)
            ),
            new KeyFrame(Duration.millis(300),
                new KeyValue(translateXProperty(), 0,
                    Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                new KeyValue(opacityProperty(), 1,
                    Interpolator.EASE_OUT)
            )
        );
        tl.play();
    }

    private void slideOut() {
        LOG.debug("Slide-out animation starting");
        panelOpen = false;
        Timeline tl = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(translateXProperty(), 0),
                new KeyValue(opacityProperty(), 1)
            ),
            new KeyFrame(Duration.millis(250),
                new KeyValue(translateXProperty(), PANEL_WIDTH,
                    Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                new KeyValue(opacityProperty(), 0,
                    Interpolator.EASE_IN)
            )
        );
        tl.setOnFinished(e -> setVisible(false));
        tl.play();
    }
}
