package fan.summer.ui.about;

import fan.summer.api.i18n.I18n;
import fan.summer.api.theme.Themes;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.Desktop;
import java.net.URI;

/**
 * Modal "About SwissKitJ" dialog. A separate {@link Stage} sized to its owner
 * with a dimmed transparent backdrop (click to dismiss) and a centered
 * {@code .sk-dialog} card showing version, build time, author, repository,
 * documentation, and license. Repository / Documentation rows open the system
 * browser via {@link Desktop#browse}.
 */
public final class AboutDialog {

    private static final String REPOSITORY = "https://github.com/MuskStark/SwissKitJ";
    private static final String DOCUMENTATION = "https://muskstark.github.io/SwissKitJ/";
    private static final String AUTHOR = "MuskStark";
    private static final String LICENSE = "GNU GPL v3";

    private final Stage dialog;

    public AboutDialog(Stage owner) {
        dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.TRANSPARENT);

        StackPane root = new StackPane();
        // Dimmed backdrop; a click landing on the backdrop (not the card) closes.
        root.getStyleClass().add("sk-scrim");
        root.setOnMousePressed(e -> { if (e.getTarget() == root) dialog.close(); });

        Scene scene = new Scene(root, owner.getWidth(), owner.getHeight());
        scene.setFill(Color.TRANSPARENT);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) dialog.close(); });
        Themes.applyTo(scene);
        dialog.setScene(scene);

        root.getChildren().add(buildCard());
    }

    /** Centers and shows the dialog; blocks (modal) until closed. */
    public void show() {
        dialog.centerOnScreen();
        dialog.showAndWait();
    }

    private VBox buildCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("sk-dialog");
        card.setPadding(new Insets(20, 24, 20, 24));
        card.setMaxWidth(420);
        card.setMinWidth(380);

        card.getChildren().add(buildHeader());

        VBox rows = new VBox(8);
        rows.getChildren().addAll(
            row(I18n.get("about.field.version"),          BuildInfo.getVersion()),
            row(I18n.get("about.field.buildTime"),        BuildInfo.getBuildTime()),
            row(I18n.get("about.field.author"),           AUTHOR),
            linkRow(I18n.get("about.field.repository"),    REPOSITORY),
            linkRow(I18n.get("about.field.documentation"), DOCUMENTATION),
            row(I18n.get("about.field.license"),          LICENSE)
        );
        card.getChildren().add(rows);
        return card;
    }

    private HBox buildHeader() {
        Label title = new Label(I18n.get("about.title"));
        title.getStyleClass().add("sk-t1");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);

        Button close = new Button("×");
        close.getStyleClass().add("sk-t2");
        close.setStyle("-fx-background-color: transparent; -fx-font-size: 16px; -fx-cursor: hand;");
        close.setOnAction(e -> dialog.close());

        HBox header = new HBox(0, title, close);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    /** A label:value row where the value is plain text. */
    private HBox row(String key, String value) {
        Label v = new Label(value);
        v.getStyleClass().add("sk-t1");
        v.setStyle("-fx-font-size: 13px;");
        v.setWrapText(true);
        v.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(v, Priority.ALWAYS);
        return new HBox(10, fieldKeyLabel(key), v);
    }

    /** A label:hyperlink row that opens the system browser. */
    private HBox linkRow(String key, String url) {
        Hyperlink link = new Hyperlink(url);
        link.getStyleClass().add("sk-accent-text");
        link.setStyle("-fx-font-size: 13px; -fx-border-color: transparent; -fx-padding: 0;");
        link.setOnAction(e -> browse(url));
        link.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(link, Priority.ALWAYS);
        return new HBox(10, fieldKeyLabel(key), link);
    }

    private Label fieldKeyLabel(String key) {
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
            // opening a browser is best-effort; never crash the dialog
        }
    }
}
