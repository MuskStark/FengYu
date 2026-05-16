package fan.summer.ui.content;

import fan.summer.api.MdiIconUtil;
import fan.summer.api.SwissKitJPlugin;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Single card in the tool grid.
 * After clicking, callbacks onSelect, parent component decides to open detail panel or launch tool.
 */
public class ToolCard extends VBox {

    private final SwissKitJPlugin plugin;

    public ToolCard(SwissKitJPlugin plugin, Consumer<SwissKitJPlugin> onSelect) {
        this.plugin = plugin;
        getStyleClass().add("tool-card");
        setSpacing(3);
        setPadding(new Insets(16, 14, 14, 14));

        // ── Icon ────────────────────────────────────────
        Color iconColor = resolveColor(plugin.getIconStyle());
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
        iconWrap.getStyleClass().addAll("tool-icon-wrap", plugin.getIconStyle());
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
        boolean isPlugin = !"builtin".equals(plugin.getType());
        Label tag = new Label(isPlugin ? "Plugin" : "Built-in");
        tag.getStyleClass().addAll("tool-tag", isPlugin ? "tool-tag-plugin" : "");

        getChildren().addAll(iconWrap, nameLabel, descLabel, tag);

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

    private static Color resolveColor(String iconStyle) {
        return switch (iconStyle) {
            case "ic-blue"   -> Color.rgb(99, 130, 255);
            case "ic-purple" -> Color.rgb(160, 110, 255);
            case "ic-teal"   -> Color.rgb(40, 210, 140);
            case "ic-amber"  -> Color.rgb(255, 185, 50);
            case "ic-red"    -> Color.rgb(255, 100, 100);
            case "ic-pink"   -> Color.rgb(245, 100, 160);
            default          -> Color.rgb(200, 200, 210);
        };
    }

    public SwissKitJPlugin getPlugin() { return plugin; }
}