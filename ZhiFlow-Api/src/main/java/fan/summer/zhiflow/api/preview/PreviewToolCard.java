package fan.summer.zhiflow.api.preview;

import fan.summer.zhiflow.api.MdiIconUtil;
import fan.summer.zhiflow.api.ZhiFlowPlugin;
import fan.summer.zhiflow.api.i18n.I18n;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
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
 * Simplified tool card for the preview window.
 * Shows a green pulse indicator when the plugin is running in the background.
 */
class PreviewToolCard extends StackPane {

    PreviewToolCard(ZhiFlowPlugin plugin, Consumer<ZhiFlowPlugin> onSelect, boolean isBackground) {
        VBox card = new VBox();
        card.getStyleClass().add("preview-tool-card");
        card.setSpacing(3);
        card.setPadding(new Insets(16, 14, 14, 14));

        // Icon
        Color iconColor = plugin.getIconStyle().getColor();
        String fillStyle = String.format("-fx-fill: rgba(%d,%d,%d,1.0);",
                (int) (iconColor.getRed() * 255),
                (int) (iconColor.getGreen() * 255),
                (int) (iconColor.getBlue() * 255));

        Text iconText = MdiIconUtil.createIcon(plugin.getMdiIcon(), 45);
        iconText.setStyle(fillStyle);

        DropShadow glow = new DropShadow();
        glow.setColor(iconColor.deriveColor(0, 1, 1, 0.75));
        glow.setRadius(12);
        glow.setSpread(0.15);
        iconText.setEffect(glow);

        StackPane iconWrap = new StackPane(iconText);
        iconWrap.setPrefSize(48, 48);
        iconWrap.setMinSize(48, 48);

        // Name
        Label nameLabel = new Label(plugin.getName());
        nameLabel.getStyleClass().add("preview-tool-name");
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        // Description
        Label descLabel = new Label(plugin.getDescription());
        descLabel.getStyleClass().add("preview-tool-desc");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(descLabel, Priority.ALWAYS);

        // Tag
        boolean isPlugin = plugin.getType().isPlugin();
        Label tag = new Label(isPlugin ? I18n.get("detail.tag.plugin") : I18n.get("detail.tag.builtin"));
        tag.getStyleClass().add("preview-tool-tag");
        if (isPlugin) tag.getStyleClass().add("preview-tool-tag-plugin");

        card.getChildren().addAll(iconWrap, nameLabel, descLabel, tag);

        getChildren().add(card);

        // Background running indicator (theme-safe success via .preview-running-dot)
        if (isBackground) {
            Circle dot = new Circle(4);
            dot.getStyleClass().add("preview-running-dot");
            dot.setEffect(new Glow(0.8));
            dot.setMouseTransparent(true);
            StackPane.setAlignment(dot, Pos.TOP_RIGHT);
            StackPane.setMargin(dot, new Insets(8));
            FadeTransition pulse = new FadeTransition(Duration.millis(2500), dot);
            pulse.setFromValue(1.0);
            pulse.setToValue(0.4);
            pulse.setAutoReverse(true);
            pulse.setCycleCount(Animation.INDEFINITE);
            pulse.play();
            getChildren().add(dot);
        }

        // Hover scale
        ScaleTransition hoverIn = new ScaleTransition(Duration.millis(150), this);
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

        // Click → onSelect callback
        setOnMouseClicked(e -> onSelect.accept(plugin));
        setCursor(javafx.scene.Cursor.HAND);
    }
}
