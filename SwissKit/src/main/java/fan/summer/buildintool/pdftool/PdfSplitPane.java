package fan.summer.buildintool.pdftool;

import fan.summer.api.i18n.I18n;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.buildintool.pdftool.worker.PdfSplitWorker;
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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX pane providing the "Split" tab content for the PDF tool.
 *
 * <p>Users drop or select a single PDF file, enter page ranges (e.g.
 * "1-3, 5, 8-10"), choose an output directory, and start the split.
 * The actual work is delegated to {@link PdfSplitWorker}.</p>
 *
 * @since 3.0.0
 */
class PdfSplitPane extends VBox {

    private static final PluginLogger log = LoggerFactory.getLogger(PdfSplitPane.class);

    private Path pdfFile;
    private int totalPages;
    private Path outputDir;

    private final VBox dropZone;
    private final Label fileInfoLabel;
    private final TextField rangeField;
    private final TextField outputDirField;
    private final ProgressBar progressBar;
    private final Label resultLabel;
    private final Button startButton;

    PdfSplitPane() {
        setSpacing(16);
        setPadding(new Insets(20, 24, 20, 24));
        setStyle("-fx-background-color: transparent;");

        // ── Drop zone ───────────────────────────────────────
        Label dropIcon = new Label("📄");
        dropIcon.setStyle("-fx-font-size: 28px;");

        Label dropHint = new Label(I18n.get("builtin.pdf.drop.hint"));
        dropHint.setStyle("-fx-text-fill: rgba(255,255,255,0.5); -fx-font-size: 13px;");

        Button selectBtn = new Button(I18n.get("builtin.pdf.select.file"));
        selectBtn.setStyle(
            "-fx-background-color: rgba(53,116,240,0.15);" +
            "-fx-text-fill: #3574F0; -fx-font-size: 13px;" +
            "-fx-background-radius: 6; -fx-border-width: 0;" +
            "-fx-padding: 8 18 8 18; -fx-cursor: hand;"
        );
        selectBtn.setOnAction(e -> pickFile());

        dropZone = new VBox(10, dropIcon, dropHint, selectBtn);
        dropZone.setAlignment(Pos.CENTER);
        dropZone.setPrefHeight(140);
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
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                handleFile(db.getFiles().get(0).toPath());
            }
            dropZone.setStyle(dropNormalStyle());
            e.setDropCompleted(true);
            e.consume();
        });

        // ── File info ───────────────────────────────────────
        fileInfoLabel = new Label();
        fileInfoLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.6); -fx-font-size: 12px;");
        fileInfoLabel.setWrapText(true);
        fileInfoLabel.setMaxWidth(Double.MAX_VALUE);
        fileInfoLabel.setVisible(false);
        fileInfoLabel.setManaged(false);

        // ── Range input ─────────────────────────────────────
        Label rangeLabel = new Label(I18n.get("builtin.pdf.split.ranges"));
        rangeLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.5); -fx-font-size: 13px;");

        rangeField = new TextField();
        rangeField.setPromptText(I18n.get("builtin.pdf.split.ranges.hint"));
        rangeField.setStyle(textFieldStyle());
        rangeField.setMaxWidth(Double.MAX_VALUE);

        // ── Output directory ────────────────────────────────
        Label dirLabel = new Label(I18n.get("builtin.pdf.split.output.dir"));
        dirLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.5); -fx-font-size: 13px;");

        outputDirField = new TextField();
        outputDirField.setStyle(textFieldStyle());
        outputDirField.setPromptText(I18n.get("builtin.pdf.split.output.dir"));
        HBox.setHgrow(outputDirField, Priority.ALWAYS);

        Button browseBtn = new Button("...");
        browseBtn.setStyle(
            "-fx-background-color: rgba(255,255,255,0.08);" +
            "-fx-text-fill: rgba(255,255,255,0.6); -fx-font-size: 13px;" +
            "-fx-background-radius: 6; -fx-border-width: 0;" +
            "-fx-padding: 8 14 8 14; -fx-cursor: hand;"
        );
        browseBtn.setOnAction(e -> pickOutputDir());

        HBox dirRow = new HBox(8, outputDirField, browseBtn);
        dirRow.setAlignment(Pos.CENTER_LEFT);

        // ── Progress bar ────────────────────────────────────
        progressBar = new ProgressBar(0);
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

        startButton = new Button(I18n.get("builtin.pdf.split.start"));
        startButton.setStyle(
            "-fx-background-color: linear-gradient(to bottom right, #4a9eff, #6c5ce7);" +
            "-fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: 600;" +
            "-fx-background-radius: 8; -fx-border-width: 0;" +
            "-fx-padding: 10 24 10 24; -fx-cursor: hand;"
        );
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(e -> startSplit());

        getChildren().addAll(
            dropZone, fileInfoLabel,
            rangeLabel, rangeField,
            dirLabel, dirRow,
            progressBar, resultLabel,
            spacer, startButton
        );
    }

    // ── File handling ───────────────────────────────────────

    private void handleFile(Path path) {
        if (!path.toString().toLowerCase().endsWith(".pdf")) {
            log.warn("Rejected non-PDF file: {}", path.getFileName());
            return;
        }
        log.info("Loading PDF file: {}", path.getFileName());

        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            totalPages = doc.getNumberOfPages();
            pdfFile = path;
        } catch (Exception e) {
            log.error("Failed to open PDF: {}", e.getMessage());
            fileInfoLabel.setText(e.getMessage());
            fileInfoLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
            fileInfoLabel.setVisible(true);
            fileInfoLabel.setManaged(true);
            return;
        }

        long sizeBytes;
        try {
            sizeBytes = Files.size(path);
        } catch (Exception e) {
            sizeBytes = 0;
        }

        fileInfoLabel.setText(I18n.get("builtin.pdf.file.info", totalPages, formatSize(sizeBytes))
                + " — " + path.getFileName().toString());
        fileInfoLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.6); -fx-font-size: 12px;");
        fileInfoLabel.setVisible(true);
        fileInfoLabel.setManaged(true);

        outputDir = path.getParent();
        outputDirField.setText(outputDir.toAbsolutePath().toString());

        log.debug("PDF loaded: {} pages, {}", totalPages, formatSize(sizeBytes));
    }

    private void pickFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle(I18n.get("builtin.pdf.select.file"));
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("PDF", "*.pdf")
        );
        File f = fc.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (f != null) handleFile(f.toPath());
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

    // ── Range parsing ───────────────────────────────────────

    /**
     * Parses a comma-separated page range string into a list of {@code [start, end]}
     * pairs (1-based, inclusive).
     *
     * <p>Examples: "1-3, 5, 8-10" yields {@code [[1,3], [5,5], [8,10]]}.
     * Returns {@code null} if the input is invalid.</p>
     *
     * @param input the range string
     * @return list of ranges, or null on parse error
     */
    List<int[]> parseRanges(String input) {
        if (input == null || input.isBlank()) return null;

        List<int[]> ranges = new ArrayList<>();
        String[] parts = input.split(",");

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) return null;

            if (trimmed.contains("-")) {
                String[] bounds = trimmed.split("-", 2);
                if (bounds.length != 2) return null;
                try {
                    int start = Integer.parseInt(bounds[0].trim());
                    int end = Integer.parseInt(bounds[1].trim());
                    if (start < 1 || end < start) return null;
                    ranges.add(new int[]{start, end});
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                try {
                    int page = Integer.parseInt(trimmed);
                    if (page < 1) return null;
                    ranges.add(new int[]{page, page});
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }

        return ranges.isEmpty() ? null : ranges;
    }

    // ── Split operation ─────────────────────────────────────

    private void startSplit() {
        if (pdfFile == null) {
            showResult("Please select a PDF file first", true);
            return;
        }

        List<int[]> ranges = parseRanges(rangeField.getText());
        if (ranges == null) {
            showResult(I18n.get("builtin.pdf.split.range.invalid", totalPages), true);
            return;
        }

        // Validate ranges against total pages
        for (int[] range : ranges) {
            if (range[0] > totalPages) {
                showResult(I18n.get("builtin.pdf.split.range.invalid", totalPages), true);
                return;
            }
        }

        if (outputDir == null) {
            outputDir = pdfFile.getParent();
        }

        log.info("Starting PDF split: {} ({} ranges)", pdfFile.getFileName(), ranges.size());

        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.setProgress(0);
        resultLabel.setVisible(false);
        resultLabel.setManaged(false);
        startButton.setDisable(true);

        PdfSplitWorker worker = new PdfSplitWorker(pdfFile, ranges, outputDir);
        progressBar.progressProperty().bind(worker.progressProperty());

        worker.setOnSucceeded(e -> {
            progressBar.progressProperty().unbind();
            progressBar.setProgress(1.0);
            startButton.setDisable(false);
            showResult(I18n.get("builtin.pdf.complete"), false);
            log.info("PDF split completed successfully");
        });

        worker.setOnFailed(e -> {
            progressBar.progressProperty().unbind();
            startButton.setDisable(false);
            Throwable ex = worker.getException();
            String msg = ex != null ? ex.getMessage() : "Unknown error";
            showResult(msg, true);
            log.error("PDF split failed: {}", msg);
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
        return "-fx-background-color: rgba(255,255,255,0.02);" +
               "-fx-border-color: rgba(255,255,255,0.15);" +
               "-fx-border-width: 1; -fx-border-style: dashed;" +
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
               "-fx-border-color: rgba(255,255,255,0.28);" +
               "-fx-border-width: 1; -fx-border-radius: 6; -fx-background-radius: 6;" +
               "-fx-text-fill: white; -fx-font-size: 13px;" +
               "-fx-padding: 9 12 9 12;";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
