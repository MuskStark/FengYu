package fan.summer.api.preview;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * Simplified title bar for the preview window.
 * Shows a title label and a close button. Supports drag-to-move.
 */
class PreviewTitleBar extends HBox {

    private double dragX, dragY;

    PreviewTitleBar(String title) {
        getStyleClass().add("preview-titlebar");
        setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("preview-titlebar-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label closeBtn = new Label("✕");
        closeBtn.getStyleClass().add("preview-titlebar-close");

        getChildren().addAll(titleLabel, spacer, closeBtn);
        setPadding(new Insets(0, 16, 0, 16));
    }

    /** Wire the close button and drag-to-move after the Stage is available. */
    void bindStage(Stage stage, Runnable onClose) {
        Label closeBtn = (Label) getChildren().get(2);
        closeBtn.setOnMouseClicked(e -> {
            stage.close();
            if (onClose != null) onClose.run();
        });

        setOnMousePressed(e -> {
            dragX = e.getScreenX() - stage.getX();
            dragY = e.getScreenY() - stage.getY();
        });
        setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragX);
            stage.setY(e.getScreenY() - dragY);
        });
    }
}
