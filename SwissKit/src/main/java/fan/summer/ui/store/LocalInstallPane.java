package fan.summer.ui.store;

import fan.summer.api.component.GlassNotification;
import fan.summer.api.i18n.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import fan.summer.plugin.PluginLoader;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local JAR installation pane for the Plugin Store.
 * Allows users to select a JAR file via file browser or drag-and-drop,
 * then deploys it to the application's {@code plugins/} directory.
 *
 * @param onInstallComplete an optional callback invoked after a successful installation;
 *                          may be null
 * @see PluginLoader#resolvePluginsDir()
 * @since 1.0
 */
public class LocalInstallPane extends VBox {

    private static final Logger log = LoggerFactory.getLogger(LocalInstallPane.class);

    private final Runnable onInstallComplete;
    private final Label statusLabel;
    private final Label fileNameLabel;
    private final ProgressIndicator progress;
    private final Button installBtn;
    private final AtomicReference<File> selectedFile = new AtomicReference<>();
    private final VBox dropZone;

    /**
     * Constructs the local install pane.
     *
     * @param onInstallComplete callback invoked after each successful install; may be null
     */
    public LocalInstallPane(Runnable onInstallComplete) {
        this.onInstallComplete = onInstallComplete;
        setSpacing(20);
        setStyle("-fx-background-color: transparent;");
        setPadding(new Insets(24));

        Label title = new Label(I18n.get("store.local.title"));
        title.getStyleClass().add("sk-t1");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: 500;");

        Label desc = new Label(I18n.get("store.local.desc"));
        desc.getStyleClass().add("sk-t2");
        desc.setStyle("-fx-font-size: 12px;");
        desc.setWrapText(true);
        desc.setMaxWidth(Double.MAX_VALUE);

        // Drop zone
        dropZone = buildDropZone();

        // File info display
        fileNameLabel = new Label("");
        fileNameLabel.getStyleClass().add("sk-t2");
        fileNameLabel.setStyle("-fx-font-size: 12px; -fx-font-family: 'SF Mono','Consolas',monospace;");

        // Install button
        installBtn = glassBtn(I18n.get("store.local.installPlugin"), true);
        installBtn.setDisable(true);
        installBtn.setOnAction(e -> {
            File file = selectedFile.get();
            if (file == null) return;
            installPlugin(file);
        });

        // Progress
        progress = new ProgressIndicator(-1);
        progress.setPrefSize(24, 24);
        progress.setVisible(false);
        progress.setStyle("-fx-accent: #3574F0;");

        // Status
        statusLabel = new Label("");
        statusLabel.getStyleClass().add("sk-t2");
        statusLabel.setStyle("-fx-font-size: 12px;");
        statusLabel.setWrapText(true);

        Label hint = new Label(I18n.get("store.local.hint"));
        hint.getStyleClass().add("sk-t3");
        hint.setStyle("-fx-font-size: 11px;");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        HBox progressRow = new HBox(10, progress, statusLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        progressRow.setVisible(false);

        getChildren().addAll(
            title, desc, dropZone, fileNameLabel,
            installBtn, progressRow, hint
        );
    }

    private VBox buildDropZone() {
        VBox zone = new VBox();
        zone.setAlignment(Pos.CENTER);
        zone.setPrefHeight(140);
        zone.setSpacing(14);
        zone.getStyleClass().addAll("sk-surface-soft", "sk-outlined");
        zone.setStyle(dropZoneStyle(false));

        Label iconLabel = new Label("📦");
        iconLabel.setStyle("-fx-font-size: 32px;");

        Label dropText = new Label(I18n.get("store.local.dropHint"));
        dropText.getStyleClass().add("sk-t2");
        dropText.setStyle("-fx-font-size: 13px;");
        dropText.setWrapText(true);

        Button browseBtn = glassBtn(I18n.get("store.local.browseFiles"), false);
        browseBtn.setOnAction(e -> browseAndSelect());

        zone.getChildren().addAll(iconLabel, dropText, browseBtn);

        zone.setOnMouseClicked(e -> browseAndSelect());
        zone.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                zone.setStyle(dropZoneStyle(true));
            }
            e.consume();
        });
        zone.setOnDragExited(e -> zone.setStyle(dropZoneStyle(false)));
        zone.setOnDragDropped(e -> {
            var files = e.getDragboard().getFiles();
            if (!files.isEmpty()) handleFile(files.get(0));
            zone.setStyle(dropZoneStyle(false));
            e.setDropCompleted(true);
            e.consume();
        });

        return zone;
    }

    private void browseAndSelect() {
        FileChooser fc = new FileChooser();
        fc.setTitle(I18n.get("store.local.installPlugin"));
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JAR Files", "*.jar")
        );
        File file = fc.showOpenDialog(null);
        if (file != null) handleFile(file);
    }

    private void handleFile(File file) {
        if (!file.getName().toLowerCase().endsWith(".jar")) {
            showError(I18n.get("store.local.selectJar"));
            return;
        }
        selectedFile.set(file);
        fileNameLabel.setText("📄 " + file.getName());
        fileNameLabel.getStyleClass().removeAll("sk-t2", "sk-t3");
        fileNameLabel.getStyleClass().add("sk-t1");
        fileNameLabel.setStyle("-fx-font-size: 12px; -fx-font-family: 'SF Mono','Consolas',monospace;");
        statusLabel.setText("");
        statusLabel.setVisible(false);
        installBtn.setDisable(false);

        // Reset drop zone style
        dropZone.setStyle(dropZoneStyle(false));
    }

    private void installPlugin(File source) {
        installBtn.setDisable(true);
        progress.setVisible(true);
        statusLabel.setVisible(true);
        statusLabel.setText(I18n.get("store.local.installing"));
        statusLabel.getStyleClass().removeAll("sk-t1", "sk-t3");
        statusLabel.getStyleClass().add("sk-t2");
        statusLabel.setStyle("-fx-font-size: 12px;");

        Thread installThread = new Thread(() -> {
            try {
                Path pluginDir = PluginLoader.resolvePluginsDir();
                Files.createDirectories(pluginDir);

                Path target = pluginDir.resolve(source.getName());
                Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);

                log.info("Plugin installed: {}", target);

                javafx.application.Platform.runLater(() -> {
                    progress.setVisible(false);
                    statusLabel.setText(I18n.get("store.local.installed", source.getName()));
                    statusLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
                    installBtn.setDisable(false);
                    GlassNotification.toast(LocalInstallPane.this, GlassNotification.Type.SUCCESS,
                            I18n.get("store.local.installed", source.getName()));
                    if (onInstallComplete != null) onInstallComplete.run();
                });
            } catch (Exception ex) {
                log.error("Plugin install failed", ex);
                javafx.application.Platform.runLater(() -> {
                    progress.setVisible(false);
                    showError(I18n.get("store.online.installFailed", ex.getMessage()));
                    installBtn.setDisable(false);
                });
            }
        });
        installThread.setName("local-install");
        installThread.setDaemon(true);
        installThread.start();
    }

    private void showError(String msg) {
        statusLabel.setVisible(true);
        statusLabel.setText("❌ " + msg);
        statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
    }

    private static String dropZoneStyle(boolean highlight) {
        if (highlight) {
            return "-fx-background-color: rgba(53,116,240,0.10);" +
                   "-fx-border-color: rgba(53,116,240,0.40);" +
                   "-fx-border-width: 1; -fx-border-style: dashed;" +
                   "-fx-border-radius: 12; -fx-background-radius: 12;";
        }
        // Background + border colors come from .sk-surface-soft / .sk-outlined
        // applied on the drop zone node; only layout props here.
        return "-fx-border-width: 1; -fx-border-style: dashed;" +
               "-fx-border-radius: 12; -fx-background-radius: 12;";
    }

    private static Button glassBtn(String text, boolean primary) {
        Button btn = new Button(text);
        if (primary) {
            btn.setStyle(
                "-fx-background-color: #3574F0; -fx-text-fill: white; -fx-font-size: 13px;" +
                "-fx-font-weight: 500; -fx-background-radius: 8; -fx-border-width: 0;" +
                "-fx-padding: 10 20 10 20; -fx-cursor: hand;"
            );
        } else {
            btn.getStyleClass().addAll("sk-surface-soft", "sk-outlined", "sk-t1");
            btn.setStyle(
                "-fx-border-width: 1; -fx-font-size: 13px;" +
                "-fx-background-radius: 8; -fx-border-radius: 8;" +
                "-fx-padding: 10 20 10 20; -fx-cursor: hand;"
            );
        }
        return btn;
    }
}
