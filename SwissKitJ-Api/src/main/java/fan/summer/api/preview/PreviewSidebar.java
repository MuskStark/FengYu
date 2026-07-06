package fan.summer.api.preview;

import fan.summer.api.MdiIconUtil;
import fan.summer.api.ToolCategory;
import fan.summer.api.i18n.I18n;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Simplified sidebar showing static category labels for visual context.
 * The preview sidebar is purely decorative — no filtering is needed
 * since only a handful of plugins are shown.
 *
 * <p>Mirrors the real Sidebar: an icon + label per category, the first
 * item rendered with the signature 3px left accent strip (handled in
 * {@code .preview-nav-item.active} CSS). All colors come from
 * {@code -sk-*} tokens so the panel re-resolves on theme switch.</p>
 */
class PreviewSidebar extends VBox {

    /** Icon per supported category, in display order. */
    private static final Object[][] CATEGORIES = {
        { ToolCategory.DEV,   "code-tags"             },
        { ToolCategory.TEXT,  "form-textbox"          },
        { ToolCategory.IMAGE, "image-outline"         },
        { ToolCategory.NET,   "web"                   },
        { ToolCategory.OTHER, "package-variant-closed"},
    };

    private Label sectionLabel;
    private final List<Label> navLabels = new ArrayList<>();

    PreviewSidebar() {
        getStyleClass().add("preview-sidebar");
        buildRows();
    }

    private void buildRows() {
        sectionLabel = new Label();
        sectionLabel.getStyleClass().add("preview-sidebar-section");
        getChildren().add(sectionLabel);

        for (int i = 0; i < CATEGORIES.length; i++) {
            ToolCategory cat = (ToolCategory) CATEGORIES[i][0];
            String mdiIcon   = (String) CATEGORIES[i][1];

            HBox row = new HBox(10);
            row.getStyleClass().add("preview-nav-item");
            // Mark the first item active so the accent strip shows (decorative).
            if (i == 0) row.getStyleClass().add("active");

            Text icon = MdiIconUtil.createIcon(mdiIcon, 16);
            icon.getStyleClass().add("preview-nav-item-icon");

            Label text = new Label();
            text.getStyleClass().add("preview-nav-item-text");
            HBox.setHgrow(text, Priority.ALWAYS);
            navLabels.add(text);

            row.getChildren().addAll(icon, text);
            getChildren().add(row);
        }
    }

    /** Re-apply all locale-dependent text. Called on locale change. */
    void refresh() {
        sectionLabel.setText(I18n.get("sidebar.section.tools").toUpperCase());
        for (int i = 0; i < navLabels.size(); i++) {
            ToolCategory cat = (ToolCategory) CATEGORIES[i][0];
            navLabels.get(i).setText(categoryDisplayName(cat));
        }
    }

    private static String categoryDisplayName(ToolCategory cat) {
        return switch (cat) {
            case DEV   -> I18n.get("detail.category.dev");
            case TEXT  -> I18n.get("detail.category.text");
            case IMAGE -> I18n.get("detail.category.image");
            case NET   -> I18n.get("detail.category.net");
            default    -> I18n.get("detail.category.other");
        };
    }
}
