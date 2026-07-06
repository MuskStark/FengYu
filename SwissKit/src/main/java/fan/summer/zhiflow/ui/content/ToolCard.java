package fan.summer.zhiflow.ui.content;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.MdiIconUtil;
import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.zhiflow.plugin.FavoriteService;
import fan.summer.zhiflow.plugin.PluginRegistry;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Glow;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * A single card in the tool grid display. Each card shows the plugin's
 * icon, name, description, a tag, a favorite star toggle, and optionally
 * a green pulse indicator when the plugin has background tasks running.
 *
 * @since 1.0
 */
public class ToolCard extends StackPane {

    private static final PluginLogger LOG = LoggerFactory.getLogger(ToolCard.class);

    private final SwissKitJPlugin plugin;

    /**
     * Constructs a ToolCard for the given plugin with a selection callback.
     *
     * @param plugin         the plugin to display; must not be null
     * @param onSelect       called when the user clicks this card; receives the plugin
     * @param registry       the plugin registry for background state queries; may be null
     * @param favoriteService the favorite service for star toggle; may be null
     */
    public ToolCard(SwissKitJPlugin plugin, Consumer<SwissKitJPlugin> onSelect,
                    PluginRegistry registry, FavoriteService favoriteService) {
        LOG.info("Creating ToolCard for plugin: name={}, id={}", plugin.getName(), plugin.getId());
        this.plugin = plugin;

        // ── Inner card VBox (actual visual card) ────────────────────
        VBox card = new VBox();
        card.getStyleClass().add("tool-card");
        card.setSpacing(3);
        card.setPadding(new Insets(16, 14, 14, 14));

        // ── Icon ────────────────────────────────────────
        Color iconColor = plugin.getIconStyle().getColor();
        String fillStyle = String.format("-fx-fill: rgba(%d,%d,%d,1.0);",
                (int)(iconColor.getRed()*255),
                (int)(iconColor.getGreen()*255),
                (int)(iconColor.getBlue()*255));

        Text iconText = MdiIconUtil.createIcon(plugin.getMdiIcon(), 45);
        iconText.setStyle(fillStyle);

        DropShadow glow = new DropShadow();
        glow.setColor(iconColor.deriveColor(0, 1, 1, 0.75));
        glow.setRadius(12);
        glow.setSpread(0.15);
        iconText.setEffect(glow);

        StackPane iconWrap = new StackPane(iconText);
        iconWrap.getStyleClass().addAll("tool-icon-wrap", plugin.getIconStyle().getCssClass());
        iconWrap.setPrefSize(48, 48);
        iconWrap.setMinSize(48, 48);

        // ── Text ────────────────────────────────────────
        Label nameLabel = new Label(plugin.getName());
        nameLabel.getStyleClass().add("tool-name");
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        Label descLabel = new Label(plugin.getDescription());
        descLabel.getStyleClass().add("tool-desc");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(descLabel, Priority.ALWAYS);

        // ── Tag ────────────────────────────────────────
        boolean isPlugin = plugin.getType().isPlugin();
        Label tag = new Label(isPlugin ? I18n.get("detail.tag.plugin") : I18n.get("detail.tag.builtin"));
        tag.getStyleClass().addAll("tool-tag", isPlugin ? "tool-tag-plugin" : "");

        card.getChildren().addAll(iconWrap, nameLabel, descLabel, tag);

        // ── Background running indicator (green pulse dot, top-right) ──
        getChildren().add(card);
        if (registry != null && registry.isBackground(plugin)) {
            Circle dot = new Circle(4, Color.web("#4cd97b"));
            dot.setEffect(new Glow(0.8));
            dot.setMouseTransparent(true);
            StackPane.setAlignment(dot, Pos.TOP_RIGHT);
            StackPane.setMargin(dot, new Insets(8, 28, 0, 0));
            FadeTransition pulse = new FadeTransition(Duration.millis(2500), dot);
            pulse.setFromValue(1.0);
            pulse.setToValue(0.4);
            pulse.setAutoReverse(true);
            pulse.setCycleCount(Animation.INDEFINITE);
            pulse.play();
            getChildren().add(dot);
        }

        // ── Favorite star button (top-right corner) ──────────────
        Label starBtn = new Label();
        starBtn.setStyle("-fx-font-size: 13px; -fx-cursor: hand;");
        StackPane.setAlignment(starBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(starBtn, new Insets(6, 6, 0, 0));
        updateStarStyle(starBtn, favoriteService != null && favoriteService.isFavorite(plugin.getId()));

        starBtn.setOnMouseClicked(e -> {
            e.consume(); // prevent card click
            if (favoriteService == null) return;
            boolean nowFav = favoriteService.toggle(plugin.getId());
            updateStarStyle(starBtn, nowFav);
            // Brief scale pop on toggle
            ScaleTransition pop = new ScaleTransition(Duration.millis(150), starBtn);
            pop.setFromX(0.7); pop.setFromY(0.7);
            pop.setToX(1.0); pop.setToY(1.0);
            pop.setInterpolator(Interpolator.EASE_OUT);
            pop.play();
        });

        getChildren().add(starBtn);

        // ── Hover: intensify glow ────────────────────────────────
        ScaleTransition hoverIn  = new ScaleTransition(Duration.millis(150), this);
        ScaleTransition hoverOut = new ScaleTransition(Duration.millis(150), this);
        hoverIn.setToX(1.03); hoverIn.setToY(1.03);
        hoverOut.setToX(1.0); hoverOut.setToY(1.0);

        setOnMouseEntered(e -> {
            hoverOut.stop(); hoverIn.play();
            glow.setRadius(20);
            glow.setSpread(0.25);
        });
        setOnMouseExited(e -> {
            hoverIn.stop(); hoverOut.play();
            glow.setRadius(12);
            glow.setSpread(0.15);
        });

        // ── Click animation + callback ───────────────────────────────
        setOnMouseClicked(e -> {
            ScaleTransition click = new ScaleTransition(Duration.millis(100), this);
            click.setToX(0.97); click.setToY(0.97);
            click.setAutoReverse(true); click.setCycleCount(2);
            click.setOnFinished(ev -> onSelect.accept(plugin));
            click.play();
        });

        // ── Entry animation ─────────────────────────────────────
        setOpacity(0);
        setScaleX(0.94); setScaleY(0.94);
        setTranslateY(8);

        FadeTransition ft = new FadeTransition(Duration.millis(280), this);
        ft.setToValue(1);
        TranslateTransition tt = new TranslateTransition(Duration.millis(280), this);
        tt.setToY(0);
        ScaleTransition st = new ScaleTransition(Duration.millis(280), this);
        st.setToX(1); st.setToY(1);

        ParallelTransition entry = new ParallelTransition(ft, tt, st);
        entry.setInterpolator(new Interpolator() {
            @Override protected double curve(double t) {
                return 1 - Math.pow(1 - t, 3) * Math.cos(t * Math.PI * 2);
            }
        });
        entry.play();

        setCursor(javafx.scene.Cursor.HAND);
    }

    /**
     * Returns the plugin displayed by this card.
     *
     * @return the plugin instance passed at construction time
     */
    public SwissKitJPlugin getPlugin() { return plugin; }

    private void updateStarStyle(Label starBtn, boolean isFavorite) {
        if (isFavorite) {
            starBtn.setText("★");
            starBtn.getStyleClass().remove("sk-t3");
            starBtn.setStyle("-fx-font-size: 13px; -fx-cursor: hand; -fx-text-fill: #f5c842;");
        } else {
            starBtn.setText("☆");
            if (!starBtn.getStyleClass().contains("sk-t3")) starBtn.getStyleClass().add("sk-t3");
            starBtn.setStyle("-fx-font-size: 13px; -fx-cursor: hand;");
        }
    }
}
