package fan.summer.ui.store;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
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
 * Allows users to select a JAR file and deploy it to the plugins directory.
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

    public LocalInstallPane(Runnable onInstallComplete) {
        this.onInstallComplete = onInstallComplete;
        setSpacing(20);
        setStyle("-fx-background-color: transparent;");
        setPadding(new Insets(24));

        Label title = new Label("Install from Local File");
        title.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.90);" +
            "-fx-font-size: 18px; -fx-font-weight: 500;"
        );

        Label desc = new Label(
            "Select a SwissKitJ plugin JAR file to install. " +
            "The plugin will be automatically loaded after installation."
        );
        desc.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.45);" +
            "-fx-font-size: 12px;"
        );
        desc.setWrapText(true);

        // Drop zone
        dropZone = buildDropZone();

        // File info display
        fileNameLabel = new Label("");
        fileNameLabel.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.55);" +
            "-fx-font-size: 12px; -fx-font-family: 'SF Mono','Consolas',monospace;"
        );

        // Install button
        installBtn = glassBtn("Install Plugin", true);
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
        progress.setStyle("-fx-accent: #5b8cf7;");

        // Status
        statusLabel = new Label("");
        statusLabel.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.50);" +
            "-fx-font-size: 12px;"
        );
        statusLabel.setWrapText(true);

        Label hint = new Label(
            "Plugin JAR files must contain META-INF/services/fan.summer.api.SwissKitJPlugin " +
            "to be recognized."
        );
        hint.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.28);" +
            "-fx-font-size: 11px;"
        );

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
        zone.setStyle(dropZoneStyle(false));

        Label iconLabel = new Label("📦");
        iconLabel.setStyle("-fx-font-size: 32px;");

        Label dropText = new Label("Drop JAR file here or click to browse");
        dropText.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.55);" +
            "-fx-font-size: 13px;"
        );

        Button browseBtn = glassBtn("Browse Files", false);
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
        fc.setTitle("Select Plugin JAR");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JAR Files", "*.jar")
        );
        File file = fc.showOpenDialog(null);
        if (file != null) handleFile(file);
    }

    private void handleFile(File file) {
        if (!file.getName().toLowerCase().endsWith(".jar")) {
            showError("Please select a JAR file.");
            return;
        }
        selectedFile.set(file);
        fileNameLabel.setText("📄 " + file.getName());
        fileNameLabel.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.80);" +
            "-fx-font-size: 12px; -fx-font-family: 'SF Mono','Consolas',monospace;"
        );
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
        statusLabel.setText("Installing...");
        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

        new Thread(() -> {
            try {
                Path pluginDir = Path.of(System.getProperty("user.dir"), "plugins");
                Files.createDirectories(pluginDir);

                Path target = pluginDir.resolve(source.getName());
                Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);

                log.info("Plugin installed: {}", target);

                javafx.application.Platform.runLater(() -> {
                    progress.setVisible(false);
                    statusLabel.setText("✓ Successfully installed: " + source.getName());
                    statusLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
                    installBtn.setDisable(false);
                    if (onInstallComplete != null) onInstallComplete.run();
                });
            } catch (Exception ex) {
                log.error("Plugin install failed", ex);
                javafx.application.Platform.runLater(() -> {
                    progress.setVisible(false);
                    showError("Install failed: " + ex.getMessage());
                    installBtn.setDisable(false);
                });
            }
        }).start();
    }

    private void showError(String msg) {
        statusLabel.setVisible(true);
        statusLabel.setText("❌ " + msg);
        statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
    }

    private static String dropZoneStyle(boolean highlight) {
        if (highlight) {
            return "-fx-background-color: rgba(91,140,247,0.10);" +
                   "-fx-border-color: rgba(91,140,247,0.40);" +
                   "-fx-border-width: 1; -fx-border-style: dashed;" +
                   "-fx-border-radius: 12; -fx-background-radius: 12;";
        }
        return "-fx-background-color: rgba(255,255,255,0.03);" +
               "-fx-border-color: rgba(255,255,255,0.12);" +
               "-fx-border-width: 1; -fx-border-style: dashed;" +
               "-fx-border-radius: 12; -fx-background-radius: 12;";
    }

    private static Button glassBtn(String text, boolean primary) {
        Button btn = new Button(text);
        if (primary) {
            btn.setStyle(
                "-fx-background-color: #5b8cf7; -fx-text-fill: white; -fx-font-size: 13px;" +
                "-fx-font-weight: 500; -fx-background-radius: 8; -fx-border-width: 0;" +
                "-fx-padding: 10 20 10 20; -fx-cursor: hand;"
            );
        } else {
            btn.setStyle(
                "-fx-background-color: rgba(255,255,255,0.07);" +
                "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
                "-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 13px;" +
                "-fx-background-radius: 8; -fx-border-radius: 8;" +
                "-fx-padding: 10 20 10 20; -fx-cursor: hand;"
            );
        }
        return btn;
    }
}