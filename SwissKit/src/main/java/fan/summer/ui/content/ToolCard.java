package fan.summer.ui.content;

import fan.summer.api.SwissKitJPlugin;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
        Label iconLabel = new Label(plugin.getIconText());
        iconLabel.setStyle("-fx-font-size: 18px;");

        StackPane iconWrap = new StackPane(iconLabel);
        iconWrap.getStyleClass().addAll("tool-icon-wrap", plugin.getIconStyle());
        iconWrap.setPrefSize(40, 40);
        iconWrap.setMinSize(40, 40);

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

        // ── Hover animation ─────────────────────────────────────
        ScaleTransition hoverIn  = new ScaleTransition(Duration.millis(150), this);
        ScaleTransition hoverOut = new ScaleTransition(Duration.millis(150), this);
        hoverIn.setToX(1.03); hoverIn.setToY(1.03);
        hoverOut.setToX(1.0); hoverOut.setToY(1.0);

        setOnMouseEntered(e -> { hoverOut.stop(); hoverIn.play(); });
        setOnMouseExited( e -> { hoverIn.stop(); hoverOut.play(); });

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
                // Simple spring curve
                return 1 - Math.pow(1 - t, 3) * Math.cos(t * Math.PI * 2);
            }
        });
        // Delay set by caller, see ToolGrid
        entry.play();

        setCursor(javafx.scene.Cursor.HAND);
    }

    public SwissKitJPlugin getPlugin() { return plugin; }
}
