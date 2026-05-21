package fan.summer.api.preview;

import fan.summer.api.MdiIconUtil;
import fan.summer.api.SwissKitJPlugin;
import javafx.animation.ScaleTransition;
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
 * Simplified tool card for the preview window.
 */
class PreviewToolCard extends VBox {

    PreviewToolCard(SwissKitJPlugin plugin, Consumer<SwissKitJPlugin> onSelect) {
        getStyleClass().add("preview-tool-card");
        setSpacing(3);
        setPadding(new Insets(16, 14, 14, 14));

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
        Label tag = new Label(plugin.getType().isPlugin() ? "Plugin" : "Built-in");
        tag.getStyleClass().add("preview-tool-tag");

        getChildren().addAll(iconWrap, nameLabel, descLabel, tag);

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
