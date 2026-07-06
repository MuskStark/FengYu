package fan.summer.buildintool.pdftool;

import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.buildintool.pdftool.worker.PdfConvertWorker;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX pane providing the "To Word" tab content for the PDF tool.
 *
 * <p>Users drop or select PDF files, choose an output directory, and start
 * the conversion. The actual work is delegated to {@link PdfConvertWorker},
 * which uses a pure-Java converter (PDFBox + Apache POI) — no external
 * Office installation required.</p>
 *
 * @since 3.0.0
 */
class PdfConvertPane extends VBox {

    private static final PluginLogger log = LoggerFactory.getLogger(PdfConvertPane.class);

    private final List<Path> selectedFiles = new ArrayList<>();
    private Path outputDir;

    private final Label backendStatusLabel;
    private final VBox fileListBox;
    private final TextField outputDirField;
    private final ProgressBar progressBar;
    private final Label resultLabel;
    private final Button startButton;

    PdfConvertPane() {
        setSpacing(16);
        setPadding(new Insets(20, 24, 20, 24));
        setStyle("-fx-background-color: transparent;");

        // ── Converter status ────────────────────────────────
        backendStatusLabel = new Label();
        backendStatusLabel.setMaxWidth(Double.MAX_VALUE);
        backendStatusLabel.setWrapText(true);
        backendStatusLabel.setText(I18n.get("builtin.pdf.convert.ready"));
        backendStatusLabel.setStyle(
            "-fx-text-fill: #4cd97b; -fx-font-size: 13px; -fx-padding: 10 14; " +
            "-fx-background-color: rgba(76,217,123,0.08); -fx-background-radius: 6px;");

        // ── Drop zone ───────────────────────────────────────
        Label dropIcon = new Label("📄");
        dropIcon.setStyle("-fx-font-size: 28px;");

        Label dropHint = new Label(I18n.get("builtin.pdf.drop.hint"));
        dropHint.getStyleClass().add("sk-t2");
        dropHint.setStyle("-fx-font-size: 13px;");

        Button selectBtn = new Button(I18n.get("builtin.pdf.select.files"));
        selectBtn.setStyle(
            "-fx-background-color: rgba(53,116,240,0.15);" +
            "-fx-text-fill: #3574F0; -fx-font-size: 13px;" +
            "-fx-background-radius: 6; -fx-border-width: 0;" +
            "-fx-padding: 8 18 8 18; -fx-cursor: hand;"
        );
        selectBtn.setOnAction(e -> pickFiles());

        VBox dropZone = new VBox(10, dropIcon, dropHint, selectBtn);
        dropZone.setAlignment(Pos.CENTER);
        dropZone.setPrefHeight(140);
        dropZone.getStyleClass().addAll("sk-surface-soft", "sk-outlined");
        dropZone.setStyle(dropNormalStyle());

        dropZone.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                dropZone.setStyle(dropHighlightStyle());
            }
            e.consume();
        });
        dropZone.setOnDragExited(e -> dropZone.setStyle(dropNormalStyle()));
        dropZone.setOnDragDropped(e -> {
            var db = e.getDragboard();
            if (db.hasFiles()) {
                for (File f : db.getFiles()) {
                    addFile(f.toPath());
                }
            }
            dropZone.setStyle(dropNormalStyle());
            e.setDropCompleted(true);
            e.consume();
        });

        // ── File list ───────────────────────────────────────
        fileListBox = new VBox(6);
        fileListBox.setVisible(false);
        fileListBox.setManaged(false);

        // ── Output directory ────────────────────────────────
        Label dirLabel = new Label(I18n.get("builtin.pdf.split.output.dir"));
        dirLabel.getStyleClass().add("sk-t2");
        dirLabel.setStyle("-fx-font-size: 13px;");

        outputDirField = new TextField();
        outputDirField.getStyleClass().add("sk-outlined");
        outputDirField.setStyle(textFieldStyle());
        outputDirField.setPromptText(I18n.get("builtin.pdf.split.output.dir"));
        HBox.setHgrow(outputDirField, Priority.ALWAYS);

        Button browseBtn = new Button("...");
        browseBtn.getStyleClass().addAll("sk-surface-soft", "sk-t2");
        browseBtn.setStyle(
            "-fx-font-size: 13px;" +
            "-fx-background-radius: 6; -fx-border-width: 0;" +
            "-fx-padding: 8 14 8 14; -fx-cursor: hand;"
        );
        browseBtn.setOnAction(e -> pickOutputDir());

        HBox dirRow = new HBox(8, outputDirField, browseBtn);
        dirRow.setAlignment(Pos.CENTER_LEFT);

        // ── Progress bar ────────────────────────────────────
        progressBar = new ProgressBar(-1);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);

        // ── Result label ────────────────────────────────────
        resultLabel = new Label();
        resultLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
        resultLabel.setWrapText(true);
        resultLabel.setMaxWidth(Double.MAX_VALUE);
        resultLabel.setVisible(false);
        resultLabel.setManaged(false);

        // ── Start button ────────────────────────────────────
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        startButton = new Button(I18n.get("builtin.pdf.convert.start"));
        startButton.setStyle(
            "-fx-background-color: linear-gradient(to bottom right, #4a9eff, #6c5ce7);" +
            "-fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: 600;" +
            "-fx-background-radius: 8; -fx-border-width: 0;" +
            "-fx-padding: 10 24 10 24; -fx-cursor: hand;"
        );
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(e -> startConvert());

        getChildren().addAll(
            backendStatusLabel, dropZone, fileListBox,
            dirLabel, dirRow,
            progressBar, resultLabel,
            spacer, startButton
        );
    }

    // ── File handling ───────────────────────────────────────

    /**
     * Adds a PDF file to the selection list. Skips non-PDF files and duplicates.
     *
     * @param path the file to add
     */
    void addFile(Path path) {
        if (!path.toString().toLowerCase().endsWith(".pdf")) {
            log.debug("Skipped non-PDF file: {}", path.getFileName());
            return;
        }
        if (selectedFiles.contains(path)) {
            log.debug("Skipped duplicate file: {}", path.getFileName());
            return;
        }

        selectedFiles.add(path);
        log.info("Added PDF file: {}", path.getFileName());

        // Set default output dir from first file's parent
        if (outputDir == null) {
            outputDir = path.getParent();
            outputDirField.setText(outputDir.toAbsolutePath().toString());
        }

        // Create file row
        Label fileName = new Label(path.getFileName().toString());
        fileName.getStyleClass().add("sk-t2");
        fileName.setStyle("-fx-font-size: 12px;");
        fileName.setWrapText(true);
        HBox.setHgrow(fileName, Priority.ALWAYS);

        Button removeBtn = new Button("✕");
        removeBtn.getStyleClass().add("sk-t3");
        removeBtn.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-font-size: 12px;" +
            "-fx-padding: 2 6 2 6; -fx-cursor: hand;"
        );
        removeBtn.setOnAction(e -> {
            selectedFiles.remove(path);
            rebuildFileList();
        });

        HBox row = new HBox(8, fileName, removeBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("sk-surface");
        row.setStyle("-fx-background-radius: 6; -fx-padding: 6 10;");

        fileListBox.getChildren().add(row);
        fileListBox.setVisible(true);
        fileListBox.setManaged(true);
    }

    private void rebuildFileList() {
        fileListBox.getChildren().clear();
        if (selectedFiles.isEmpty()) {
            fileListBox.setVisible(false);
            fileListBox.setManaged(false);
            return;
        }
        for (Path p : selectedFiles) {
            // Recreate rows for remaining files
            Label fileName = new Label(p.getFileName().toString());
            fileName.getStyleClass().add("sk-t2");
            fileName.setStyle("-fx-font-size: 12px;");
            fileName.setWrapText(true);
            HBox.setHgrow(fileName, Priority.ALWAYS);

            Button removeBtn = new Button("✕");
            removeBtn.getStyleClass().add("sk-t3");
            removeBtn.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-font-size: 12px;" +
                "-fx-padding: 2 6 2 6; -fx-cursor: hand;"
            );
            removeBtn.setOnAction(e -> {
                selectedFiles.remove(p);
                rebuildFileList();
            });

            HBox row = new HBox(8, fileName, removeBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("sk-surface");
            row.setStyle("-fx-background-radius: 6; -fx-padding: 6 10;");

            fileListBox.getChildren().add(row);
        }
        fileListBox.setVisible(true);
        fileListBox.setManaged(true);
    }

    private void pickFiles() {
        FileChooser fc = new FileChooser();
        fc.setTitle(I18n.get("builtin.pdf.select.files"));
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("PDF", "*.pdf")
        );
        List<File> files = fc.showOpenMultipleDialog(
            getScene() != null ? getScene().getWindow() : null);
        if (files != null) {
            for (File f : files) {
                addFile(f.toPath());
            }
        }
    }

    private void pickOutputDir() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(I18n.get("builtin.pdf.split.output.dir"));
        if (outputDir != null && Files.isDirectory(outputDir)) {
            dc.setInitialDirectory(outputDir.toFile());
        }
        File dir = dc.showDialog(getScene() != null ? getScene().getWindow() : null);
        if (dir != null) {
            outputDir = dir.toPath();
            outputDirField.setText(dir.getAbsolutePath());
        }
    }

    // ── Convert operation ───────────────────────────────────

    private void startConvert() {
        if (selectedFiles.isEmpty()) {
            showResult("Please select at least one PDF file", true);
            return;
        }

        if (outputDir == null && !selectedFiles.isEmpty()) {
            outputDir = selectedFiles.get(0).getParent();
        }

        log.info("Starting PDF conversion: {} files", selectedFiles.size());

        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.setProgress(-1);
        resultLabel.setVisible(false);
        resultLabel.setManaged(false);
        startButton.setDisable(true);

        PdfConvertWorker worker = new PdfConvertWorker(new ArrayList<>(selectedFiles), outputDir);

        worker.setOnSucceeded(e -> {
            startButton.setDisable(false);
            progressBar.setProgress(1.0);
            showResult(I18n.get("builtin.pdf.complete"), false);
            log.info("PDF conversion completed successfully");
        });

        worker.setOnFailed(e -> {
            startButton.setDisable(false);
            Throwable ex = worker.getException();
            String msg = ex != null ? ex.getMessage() : "Unknown error";
            showResult(msg, true);
            log.error("PDF conversion failed: {}", msg);
        });

        Thread thread = new Thread(worker);
        thread.setDaemon(true);
        thread.start();
    }

    // ── UI helpers ──────────────────────────────────────────

    private void showResult(String message, boolean error) {
        Platform.runLater(() -> {
            resultLabel.setText(message);
            resultLabel.setStyle(error
                ? "-fx-text-fill: #f25c5c; -fx-font-size: 12px;"
                : "-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
            resultLabel.setVisible(true);
            resultLabel.setManaged(true);
        });
    }

    private static String dropNormalStyle() {
        return "-fx-border-width: 1; -fx-border-style: dashed;" +
               "-fx-border-radius: 10; -fx-background-radius: 10;";
    }

    private static String dropHighlightStyle() {
        return "-fx-background-color: rgba(53,116,240,0.08);" +
               "-fx-border-color: rgba(53,116,240,0.40);" +
               "-fx-border-width: 1; -fx-border-style: dashed;" +
               "-fx-border-radius: 10; -fx-background-radius: 10;";
    }

    private static String textFieldStyle() {
        return "-fx-background-color: rgba(0,0,0,0.3);" +
               "-fx-border-width: 1; -fx-border-radius: 6; -fx-background-radius: 6;" +
               "-fx-text-fill: white; -fx-font-size: 13px;" +
               "-fx-padding: 9 12 9 12;";
    }
}
