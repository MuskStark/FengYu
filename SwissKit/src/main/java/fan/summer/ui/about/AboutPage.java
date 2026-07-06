package fan.summer.ui.about;

import fan.summer.zhiflow.api.i18n.I18n;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;

/**
 * Standard content-area "About SwissKitJ" page. Renders version, build time,
 * author, repository, documentation, and license as an inline section, so it
 * integrates with the regular content navigation (back bar) instead of opening
 * a separate modal {@code Stage}.
 *
 * <p>Built as a {@link Node} returned by {@link #build()} and displayed via
 * {@code ContentArea.showPage(...)}, mirroring {@code SwissKitJSettingUi.build()}.</p>
 *
 * @since 3.2.0
 */
public final class AboutPage {

    private static final String REPOSITORY = "https://github.com/MuskStark/SwissKitJ";
    private static final String DOCUMENTATION = "https://muskstark.github.io/SwissKitJ/";
    private static final String AUTHOR = "MuskStark";
    private static final String LICENSE = "GNU GPL v3";

    private AboutPage() {}

    /**
     * Builds the About page as a scrollable content node.
     *
     * @return the page root node, never null
     */
    public static Node build() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(20));

        Label title = new Label(I18n.get("about.title"));
        title.getStyleClass().add("sk-t1");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");
        root.getChildren().add(title);

        VBox rows = new VBox(10);
        rows.getChildren().addAll(
            row(I18n.get("about.field.version"),          BuildInfo.getVersion()),
            row(I18n.get("about.field.buildTime"),        BuildInfo.getBuildTime()),
            row(I18n.get("about.field.author"),           AUTHOR),
            linkRow(I18n.get("about.field.repository"),    REPOSITORY),
            linkRow(I18n.get("about.field.documentation"), DOCUMENTATION),
            row(I18n.get("about.field.license"),          LICENSE)
        );
        root.getChildren().add(rows);
        return root;
    }

    /** A label:value row where the value is plain text. */
    private static HBox row(String key, String value) {
        Label v = new Label(value);
        v.getStyleClass().add("sk-t1");
        v.setStyle("-fx-font-size: 13px;");
        v.setWrapText(true);
        v.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(v, Priority.ALWAYS);
        return new HBox(10, fieldKeyLabel(key), v);
    }

    /** A label:hyperlink row that opens the system browser. */
    private static HBox linkRow(String key, String url) {
        Hyperlink link = new Hyperlink(url);
        link.getStyleClass().add("sk-accent-text");
        link.setStyle("-fx-font-size: 13px; -fx-border-color: transparent; -fx-padding: 0;");
        link.setOnAction(e -> browse(url));
        link.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(link, Priority.ALWAYS);
        return new HBox(10, fieldKeyLabel(key), link);
    }

    private static Label fieldKeyLabel(String key) {
        Label l = new Label(key);
        l.getStyleClass().add("sk-t2");
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        l.setMinWidth(90);
        return l;
    }

    private static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // opening a browser is best-effort; never crash the page
        }
    }
}
