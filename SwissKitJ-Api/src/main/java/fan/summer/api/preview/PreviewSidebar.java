package fan.summer.api.preview;

import fan.summer.api.ToolCategory;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Simplified sidebar showing static category labels for visual context.
 * The preview sidebar is purely decorative — no filtering is needed
 * since only a handful of plugins are shown.
 */
class PreviewSidebar extends VBox {

    PreviewSidebar() {
        getStyleClass().add("preview-sidebar");

        Label sectionLabel = new Label("CATEGORIES");
        sectionLabel.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.30); -fx-font-size: 10px;" +
            "-fx-font-weight: bold; -fx-padding: 10 10 4 10;"
        );

        getChildren().add(sectionLabel);

        for (ToolCategory cat : ToolCategory.values()) {
            HBox row = new HBox(10);
            row.getStyleClass().add("preview-nav-item");
            Label text = new Label(categoryDisplayName(cat));
            text.getStyleClass().add("preview-nav-item-text");
            row.getChildren().add(text);
            getChildren().add(row);
        }
    }

    private static String categoryDisplayName(ToolCategory cat) {
        return switch (cat) {
            case DEV   -> "Developer Tools";
            case TEXT  -> "Text Processing";
            case IMAGE -> "Image Processing";
            case NET   -> "Network Tools";
            default    -> "Other Tools";
        };
    }
}
