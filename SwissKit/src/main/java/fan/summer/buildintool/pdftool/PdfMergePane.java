package fan.summer.buildintool.pdftool;

import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.buildintool.pdftool.worker.PdfMergeWorker;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JavaFX pane for the "Merge" tab of the PDF tool.
 *
 * <p>Provides a drag-and-drop zone for multiple PDF files, displays a file list
 * with page counts, and merges them into a single PDF using {@link PdfMergeWorker}.</p>
 *
 * @since 3.0.0
 */
class PdfMergePane extends VBox {

    private static final PluginLogger log = LoggerFactory.getLogger(PdfMergePane.class);

    /** Tracks added files and their associated UI rows. */
    private final List<MergeFileEntry> entries = new ArrayList<>();

    // ── UI components ──────────────────────────────────────────

    private final VBox fileListContainer = new VBox();
    private final Label totalLabel = new Label();
    private final TextField outputNameField = new TextField(
            I18n.get("builtin.pdf.merge.output.default"));
    private final TextField outputDirField = new TextField();
    private final ProgressBar progressBar = new ProgressBar();
    private final Label resultLabel = new Label();
    private final Button startButton = new Button();

    PdfMergePane() {
        setSpacing(16);
        setPadding(new Insets(20));
        setStyle("-fx-background-color: transparent;");

        // 1. Drop zone
        VBox dropZone = buildDropZone();

        // 2. File list container
        fileListContainer.setSpacing(0);
        fileListContainer.getStyleClass().add("sk-surface");
        fileListContainer.setStyle("-fx-background-radius: 8;");
        fileListContainer.setPadding(new Insets(0));

        // 3. Total summary
        updateTotal();
        totalLabel.getStyleClass().add("sk-t2");
        totalLabel.setStyle("-fx-font-size: 12px;");

        // 4. Output filename
        Label outputNameLabel = new Label(I18n.get("builtin.pdf.merge.output.name"));
        outputNameLabel.getStyleClass().add("sk-t2");
        outputNameLabel.setStyle("-fx-font-size: 12px;");
        outputNameField.setStyle(textFieldStyle());
        outputNameField.setMaxWidth(Double.MAX_VALUE);

        // 5. Output directory
        Label outputDirLabel = new Label(I18n.get("builtin.pdf.split.output.dir"));
        outputDirLabel.getStyleClass().add("sk-t2");
        outputDirLabel.setStyle("-fx-font-size: 12px;");
        outputDirField.setStyle(textFieldStyle());
        outputDirField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(outputDirField, Priority.ALWAYS);

        Button browseDirBtn = new Button(I18n.get("button.browse"));
        browseDirBtn.getStyleClass().addAll("sk-surface-soft", "sk-outlined", "sk-t1");
        browseDirBtn.setStyle(secondaryBtnStyle());
        browseDirBtn.setOnAction(e -> browseOutputDir());

        HBox dirRow = new HBox(8, outputDirField, browseDirBtn);
        dirRow.setAlignment(Pos.CENTER_LEFT);

        // 6. Progress bar (hidden until operation)
        progressBar.setProgress(0);
        progressBar.setVisible(false);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(6);
        progressBar.getStyleClass().add("sk-surface-soft");
        progressBar.setStyle(
                "-fx-accent: #4a9eff;" +
                "-fx-background-radius: 3;"
        );

        // 7. Result label
        resultLabel.getStyleClass().add("sk-t2");
        resultLabel.setStyle("-fx-font-size: 12px;");
        resultLabel.setWrapText(true);
        resultLabel.setMaxWidth(Double.MAX_VALUE);

        // 8. Start button
        I18n.bind(startButton.textProperty(), "builtin.pdf.merge.start");
        startButton.setStyle(gradientBtnStyle());
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(e -> startMerge());

        getChildren().addAll(
                dropZone,
                fileListContainer,
                totalLabel,
                outputNameLabel, outputNameField,
                outputDirLabel, dirRow,
                progressBar,
                resultLabel,
                startButton
        );
    }

    // ── Drop zone ──────────────────────────────────────────────

    private VBox buildDropZone() {
        VBox zone = new VBox();
        zone.setAlignment(Pos.CENTER);
        zone.setPrefHeight(120);
        zone.setSpacing(10);
        zone.getStyleClass().addAll("sk-surface-soft", "sk-outlined");
        zone.setStyle(dropZoneStyle(false));

        Label icon = new Label("📂"); // 📂
        icon.setStyle("-fx-font-size: 28px;");

        Label hint = new Label(I18n.get("builtin.pdf.drop.hint"));
        hint.getStyleClass().add("sk-t2");
        hint.setStyle("-fx-font-size: 13px;");
        hint.setWrapText(true);

        Button addBtn = new Button(I18n.get("builtin.pdf.merge.add"));
        addBtn.getStyleClass().addAll("sk-surface-soft", "sk-outlined", "sk-t1");
        addBtn.setStyle(secondaryBtnStyle());
        addBtn.setOnAction(e -> browseFiles());

        zone.getChildren().addAll(icon, hint, addBtn);

        // Drag-and-drop
        zone.setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                zone.setStyle(dropZoneStyle(true));
            }
            e.consume();
        });
        zone.setOnDragExited(e -> zone.setStyle(dropZoneStyle(false)));
        zone.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) {
                for (File file : db.getFiles()) {
                    addFile(file.toPath());
                }
            }
            zone.setStyle(dropZoneStyle(false));
            e.setDropCompleted(true);
            e.consume();
        });

        return zone;
    }

    // ── File handling ──────────────────────────────────────────

    /**
     * Adds a PDF file to the merge list. Reads the page count via PDFBox,
     * skips non-PDF files and duplicates.
     *
     * @param path the file to add
     */
    void addFile(Path path) {
        // Skip non-PDF
        String name = path.getFileName().toString().toLowerCase();
        if (!name.endsWith(".pdf")) {
            log.debug("Skipped non-PDF file: {}", path);
            return;
        }

        // Skip duplicates
        for (MergeFileEntry entry : entries) {
            if (entry.path().toAbsolutePath().equals(path.toAbsolutePath())) {
                log.debug("Skipped duplicate file: {}", path);
                return;
            }
        }

        // Read page count
        int pages;
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            pages = doc.getNumberOfPages();
        } catch (Exception ex) {
            log.error("Failed to read PDF: {}", path, ex);
            resultLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
            resultLabel.setText("Failed to read: " + path.getFileName());
            return;
        }

        // Set default output dir to first file's parent
        if (entries.isEmpty()) {
            outputDirField.setText(path.getParent().toAbsolutePath().toString());
        }

        // Create row
        HBox row = buildFileRow(path, pages);
        fileListContainer.getChildren().add(row);

        // Track entry
        Label pageLabel = (Label) ((HBox) row.getChildren().get(1)).getChildren().get(0);
        entries.add(new MergeFileEntry(path, pages, pageLabel));

        updateTotal();
        resultLabel.setText("");
    }

    private HBox buildFileRow(Path path, int pages) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setStyle("-fx-border-width: 0 0 1 0;");

        // File name
        Label nameLabel = new Label(path.getFileName().toString());
        nameLabel.getStyleClass().add("sk-t1");
        nameLabel.setStyle("-fx-font-size: 14px;");
        nameLabel.setWrapText(false);
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        // Pages label
        Label pageLabel = new Label(pages + " pages");
        pageLabel.getStyleClass().add("sk-t3");
        pageLabel.setStyle("-fx-font-size: 12px;");
        pageLabel.setMinWidth(60);
        pageLabel.setAlignment(Pos.CENTER_RIGHT);

        // Info section
        HBox infoBox = new HBox(pageLabel);
        infoBox.setAlignment(Pos.CENTER_RIGHT);
        infoBox.setMinWidth(70);

        // Remove button
        Button removeBtn = new Button("✕"); // ✕
        removeBtn.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-text-fill: rgba(255,80,80,0.80);" +
                "-fx-font-size: 13px;" +
                "-fx-cursor: hand; -fx-padding: 4 8 4 8;"
        );
        removeBtn.setOnAction(e -> {
            entries.removeIf(entry -> entry.path().equals(path));
            fileListContainer.getChildren().remove(row);
            updateTotal();
        });

        row.getChildren().addAll(nameLabel, infoBox, removeBtn);
        return row;
    }

    // ── Summary ────────────────────────────────────────────────

    private void updateTotal() {
        int fileCount = entries.size();
        int totalPages = entries.stream().mapToInt(MergeFileEntry::pages).sum();
        totalLabel.setText(I18n.get("builtin.pdf.merge.total", fileCount, totalPages));
    }

    // ── File browsing ──────────────────────────────────────────

    private void browseFiles() {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle(I18n.get("builtin.pdf.select.files"));
        fc.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("PDF Files", "*.pdf")
        );
        List<File> files = fc.showOpenMultipleDialog(getScene().getWindow());
        if (files != null) {
            for (File file : files) {
                addFile(file.toPath());
            }
        }
    }

    private void browseOutputDir() {
        javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
        dc.setTitle(I18n.get("builtin.pdf.split.output.dir"));
        File dir = dc.showDialog(getScene().getWindow());
        if (dir != null) {
            outputDirField.setText(dir.getAbsolutePath());
        }
    }

    // ── Merge execution ────────────────────────────────────────

    private void startMerge() {
        if (entries.size() < 2) {
            resultLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
            resultLabel.setText("At least 2 files are required to merge.");
            return;
        }

        String dirText = outputDirField.getText().trim();
        String nameText = outputNameField.getText().trim();
        if (dirText.isEmpty() || nameText.isEmpty()) {
            resultLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
            resultLabel.setText("Output directory and filename are required.");
            return;
        }

        Path outputPath = Paths.get(dirText, nameText);
        List<Path> paths = entries.stream()
                .map(MergeFileEntry::path)
                .collect(Collectors.toList());

        PdfMergeWorker worker = new PdfMergeWorker(paths, outputPath);

        progressBar.setVisible(true);
        progressBar.progressProperty().unbind();
        progressBar.progressProperty().bind(worker.progressProperty());
        startButton.setDisable(true);
        resultLabel.setText("");
        resultLabel.setStyle("-fx-font-size: 12px;");

        worker.setOnSucceeded(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            startButton.setDisable(false);
            Path result = worker.getValue();
            resultLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
            resultLabel.setText(I18n.get("builtin.pdf.complete") + " — " + result.getFileName());
            log.info("Merge completed: {}", result);
        }));

        worker.setOnFailed(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            startButton.setDisable(false);
            Throwable ex = worker.getException();
            resultLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
            resultLabel.setText(ex.getMessage() != null ? ex.getMessage() : "Merge failed");
            log.error("Merge failed", ex);
        }));

        Thread thread = new Thread(worker, "pdf-merge");
        thread.setDaemon(true);
        thread.start();
    }

    // ── Inner record ───────────────────────────────────────────

    /**
     * Tracks a single file entry in the merge list.
     *
     * @param path  the file path
     * @param pages the page count
     * @param label the page-count label in the UI row (for future updates)
     */
    record MergeFileEntry(Path path, int pages, Label label) {}

    // ── Style helpers ──────────────────────────────────────────

    private static String dropZoneStyle(boolean highlight) {
        if (highlight) {
            return "-fx-background-color: rgba(53,116,240,0.08);" +
                   "-fx-border-color: rgba(53,116,240,0.35);" +
                   "-fx-border-width: 1; -fx-border-style: dashed;" +
                   "-fx-border-radius: 10; -fx-background-radius: 10;";
        }
        return "-fx-border-width: 1; -fx-border-style: dashed;" +
               "-fx-border-radius: 10; -fx-background-radius: 10;";
    }

    private static String textFieldStyle() {
        return "-fx-background-color: rgba(0,0,0,0.3);" +
               "-fx-background-radius: 6;" +
               "-fx-text-fill: white;" +
               "-fx-font-size: 13px;" +
               "-fx-padding: 8 12 8 12;" +
               "-fx-border-width: 1; -fx-border-radius: 6;";
    }

    private static String secondaryBtnStyle() {
        return "-fx-border-width: 1;" +
               "-fx-font-size: 13px;" +
               "-fx-background-radius: 6; -fx-border-radius: 6;" +
               "-fx-padding: 8 16 8 16; -fx-cursor: hand;";
    }

    private static String gradientBtnStyle() {
        return "-fx-background-color: linear-gradient(to bottom right, #4a9eff, #6c5ce7);" +
               "-fx-text-fill: white; -fx-font-size: 14px;" +
               "-fx-font-weight: 700; -fx-background-radius: 8;" +
               "-fx-border-width: 0; -fx-padding: 12 20 12 20; -fx-cursor: hand;";
    }
}
