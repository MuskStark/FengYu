package fan.summer.buildintool.excelsplitter;

import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.component.StepWizard;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Cell;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class ExcelSplitterPlugin implements SwissKitJPlugin {

    private Node      view;
    private SplitConfig config;

    // ── Meta info ────────────────────────────────────────────

    @Override public String getId()          { return "com.toolbox.excel-splitter"; }
    @Override public String getName()        { return "Excel Splitter"; }
    @Override public String getDescription() { return "Split Excel files by Sheet / Column Value / Row Count"; }
    @Override public String getCategory()    { return "other"; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getIconText()    { return "⊘"; }
    @Override public String getIconStyle()   { return "ic-teal"; }
    @Override public String getType()        { return "plugin"; }

    @Override
    public void onActivate() {
        // Reset wizard on each entry for easy re-split
        view = null;
    }

    @Override
    public Node createView() {
        if (view != null) return view;
        config = new SplitConfig();
        view   = buildWizardView();
        return view;
    }

    // ════════════════════════════════════════════════════
    // Wizard build
    // ════════════════════════════════════════════════════

    private Node buildWizardView() {
        StepWizard wizard = new StepWizard();

        // ── Step 1: Select file ─────────────────────────────
        Step1View step1 = new Step1View(config);
        wizard.addStep("Select file", step1,
            () -> config.sourceFile != null && Files.exists(config.sourceFile)
        );

        // ── Step 2: Split mode ─────────────────────────────
        Step2View step2 = new Step2View(config);
        wizard.addStep("Split mode", step2, () -> {
            if (config.mode == SplitConfig.SplitMode.BY_COLUMN
                    && (config.splitColumn == null || config.splitColumn.isBlank())) {
                return false;
            }
            if (config.mode == SplitConfig.SplitMode.BY_ROW_COUNT
                    && config.rowsPerFile <= 0) {
                return false;
            }
            return true;
        });

        // ── Step 3: Output settings ─────────────────────────────
        Step3View step3 = new Step3View(config);
        wizard.addStep("Output settings", step3,
            () -> config.outputDir != null && Files.isDirectory(config.outputDir)
        );

        // ── Step 4: Execute split ─────────────────────────────
        Step4View step4 = new Step4View(config);
        wizard.addStep("Execute split", step4, () -> true);

        // Wire step-change callbacks
        wizard.setOnStepChanged((from, to, total) -> {
            // On entering step 2: refresh the column selector (file is already selected)
            if (to == 1) step2.refresh(config);
            // On entering step 4: start the split automatically
            if (to == 3) step4.startSplit();
        });

        // Second listener overwrites the first — kept intentionally for step 4 trigger
        wizard.setOnStepChanged((from, to, total) -> {
            if (to == 1) step2.refresh(config);
            if (to == 3) step4.startSplit();
        });

        // Wrap in a VBox to add outer padding
        VBox root = new VBox(wizard);
        VBox.setVgrow(wizard, Priority.ALWAYS);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: transparent;");
        return root;
    }

    // ════════════════════════════════════════════════════
    // Step 1: Select source file
    // ════════════════════════════════════════════════════

    static class Step1View extends VBox {
        private final SplitConfig config;
        private final Label       fileLabel;
        private final Label       previewLabel;

        Step1View(SplitConfig config) {
            this.config = config;
            setSpacing(16);
            setStyle("-fx-background-color: transparent;");

            Label title = sectionTitle("Select Excel file to split");

            fileLabel = new Label("No file selected");
            fileLabel.setStyle(
                "-fx-text-fill: rgba(255,255,255,0.40); -fx-font-size: 13px;" +
                "-fx-font-family: 'SF Mono','Consolas',monospace;"
            );
            fileLabel.setWrapText(true);

            Button pickBtn = glassBtn("📂  Select file", true);
            pickBtn.setOnAction(e -> pickFile());

            // Drop zone
            VBox dropZone = new VBox(12, pickBtn, fileLabel);
            dropZone.setAlignment(Pos.CENTER);
            dropZone.setPrefHeight(140);
            dropZone.setStyle(
                "-fx-background-color: rgba(255,255,255,0.03);" +
                "-fx-border-color: rgba(255,255,255,0.12);" +
                "-fx-border-width: 1; -fx-border-style: dashed;" +
                "-fx-border-radius: 12; -fx-background-radius: 12;"
            );

            // Drag and drop support
            dropZone.setOnDragOver(e -> {
                if (e.getDragboard().hasFiles()) {
                    e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                    dropZone.setStyle(dropZone.getStyle()
                        .replace("rgba(255,255,255,0.03)", "rgba(91,140,247,0.10)")
                        .replace("rgba(255,255,255,0.12)", "rgba(91,140,247,0.40)"));
                }
                e.consume();
            });
            dropZone.setOnDragExited(e ->
                dropZone.setStyle(dropZone.getStyle()
                    .replace("rgba(91,140,247,0.10)", "rgba(255,255,255,0.03)")
                    .replace("rgba(91,140,247,0.40)", "rgba(255,255,255,0.12)"))
            );
            dropZone.setOnDragDropped(e -> {
                List<File> files = e.getDragboard().getFiles();
                if (!files.isEmpty()) loadFile(files.get(0).toPath());
                e.setDropCompleted(true);
                e.consume();
            });

            previewLabel = new Label();
            previewLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");
            previewLabel.setWrapText(true);

            getChildren().addAll(title, dropZone, previewLabel);
        }

        private void pickFile() {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Excel file");
            fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel files", "*.xlsx", "*.xls", "*.xlsm")
            );
            File f = fc.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
            if (f != null) loadFile(f.toPath());
        }

        private void loadFile(Path path) {
            config.sourceFile = path;
            fileLabel.setText(path.getFileName().toString());
            fileLabel.setStyle(fileLabel.getStyle()
                .replace("rgba(255,255,255,0.40)", "rgba(255,255,255,0.88)"));

            // Read sheet names
            try (Workbook wb = WorkbookFactory.create(path.toFile(), null, true)) {
                List<String> names = new ArrayList<>();
                for (int i = 0; i < wb.getNumberOfSheets(); i++)
                    names.add(wb.getSheetName(i));
                config.sheetNames      = names;
                config.selectedSheets  = new ArrayList<>(names);
                previewLabel.setText("✓ Contains " + names.size() + "  sheets：" +
                    names.stream().limit(5).collect(Collectors.joining(", ")) +
                    (names.size() > 5 ? " …" : ""));
            } catch (Exception ex) {
                previewLabel.setText("❌ Failed to read file：" + ex.getMessage());
                config.sourceFile = null;
            }
        }
    }

    // ════════════════════════════════════════════════════
    // Step 2: Split mode
    // ════════════════════════════════════════════════════

    static class Step2View extends VBox {
        private final SplitConfig config;
        private VBox detailPane;

        // BY_COLUMN control
        private ComboBox<String> columnCombo;
        // BY_ROW_COUNT control
        private Spinner<Integer> rowSpinner;
        // BY_SHEET control
        private ListView<String> sheetList;

        Step2View(SplitConfig config) {
            this.config = config;
            setSpacing(16);
            setStyle("-fx-background-color: transparent;");
            build();
        }

        private void build() {
            Label title = sectionTitle("Select split mode");

            // Three radio-button cards
            ToggleGroup group = new ToggleGroup();
            VBox modeCards = new VBox(8,
                modeCard(group, SplitConfig.SplitMode.BY_SHEET,
                    "⊞  Split by sheet", "Each sheet outputs one independent file"),
                modeCard(group, SplitConfig.SplitMode.BY_COLUMN,
                    "🔤  Split by column value",  "Group by different values in a column, one file per group"),
                modeCard(group, SplitConfig.SplitMode.BY_ROW_COUNT,
                    "📏  Split by row count",  "Output one file every N rows")
            );

            // Default select first
            ((RadioButton) modeCards.getChildren().get(0)
                .lookup(".radio-button") != null
                ? modeCards.getChildren().get(0)
                : modeCards.getChildren().get(0))
                .getClass(); // Trigger once to ensure initialization

            // Select first Toggle
            group.getToggles().get(0).setSelected(true);
            config.mode = SplitConfig.SplitMode.BY_SHEET;

            detailPane = new VBox();
            detailPane.setStyle("-fx-background-color: transparent;");

            group.selectedToggleProperty().addListener((obs, o, n) -> {
                if (n != null) {
                    config.mode = (SplitConfig.SplitMode) n.getUserData();
                    refreshDetail();
                }
            });

            refreshDetail();
            getChildren().addAll(title, modeCards, detailPane);
        }

        /** Refresh dynamic content (column list etc.) when the source file changes */
        void refresh(SplitConfig config) {
            refreshDetail();
        }

        private void refreshDetail() {
            detailPane.getChildren().clear();
            detailPane.setPadding(new Insets(4, 0, 0, 0));

            switch (config.mode) {
                case BY_SHEET -> {
                    if (config.sheetNames == null || config.sheetNames.isEmpty()) return;
                    sheetList = new ListView<>();
                    sheetList.getItems().addAll(config.sheetNames);
                    sheetList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
                    sheetList.getSelectionModel().selectAll();
                    sheetList.setPrefHeight(Math.min(config.sheetNames.size() * 32 + 8, 160));
                    sheetList.setStyle(
                        "-fx-background-color: rgba(255,255,255,0.04);" +
                        "-fx-border-color: rgba(255,255,255,0.10); -fx-border-radius: 8;" +
                        "-fx-background-radius: 8; -fx-text-fill: white;"
                    );
                    sheetList.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) ->
                        config.selectedSheets = new ArrayList<>(
                            sheetList.getSelectionModel().getSelectedItems())
                    );
                    detailPane.getChildren().addAll(
                        subLabel("Select sheets to export (multiple allowed)"), sheetList
                    );
                }
                case BY_COLUMN -> {
                    columnCombo = new ComboBox<>();
                    columnCombo.setPromptText("Column to split by...…");
                    columnCombo.setMaxWidth(Double.MAX_VALUE);
                    columnCombo.setStyle(comboStyle());

                    // Read first row header from file
                    if (config.sourceFile != null) {
                        try (Workbook wb = WorkbookFactory.create(
                                config.sourceFile.toFile(), null, true)) {
                            Sheet s = wb.getSheetAt(0);
                            Row header = s.getRow(s.getFirstRowNum());
                            if (header != null) {
                                for (int c = header.getFirstCellNum();
                                     c < header.getLastCellNum(); c++) {
                                    Cell cell = header.getCell(c);
                                    if (cell != null) {
                                        String val = cell.toString().trim();
                                        if (!val.isEmpty()) {
                                            columnCombo.getItems().add(val);
                                        }
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            columnCombo.getItems().add("（Read failed：" + ex.getMessage() + "）");
                        }
                    }

                    columnCombo.valueProperty().addListener((o, ov, nv) -> {
                        config.splitColumn = nv;
                        config.splitColumnIndex = columnCombo.getItems().indexOf(nv);
                    });

                    detailPane.getChildren().addAll(subLabel("Which column to group by"), columnCombo);
                }
                case BY_ROW_COUNT -> {
                    rowSpinner = new Spinner<>(1, 1_000_000, 1000, 100);
                    rowSpinner.setEditable(true);
                    rowSpinner.setMaxWidth(Double.MAX_VALUE);
                    rowSpinner.setStyle(
                        "-fx-background-color: rgba(255,255,255,0.06);" +
                        "-fx-border-color: rgba(255,255,255,0.12); -fx-border-radius: 8;"
                    );
                    rowSpinner.valueProperty().addListener((o, ov, nv) ->
                        config.rowsPerFile = nv);
                    config.rowsPerFile = 1000;

                    detailPane.getChildren().addAll(subLabel("Max rows per file"), rowSpinner);
                }
            }
        }

        private HBox modeCard(ToggleGroup group, SplitConfig.SplitMode mode,
                              String label, String desc) {
            RadioButton rb = new RadioButton();
            rb.setToggleGroup(group);
            rb.setUserData(mode);
            rb.setStyle("-fx-text-fill: transparent;");

            Label mainL = new Label(label);
            mainL.setStyle("-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 13px; -fx-font-weight: 500;");
            Label descL = new Label(desc);
            descL.setStyle("-fx-text-fill: rgba(255,255,255,0.40); -fx-font-size: 11px;");

            VBox text = new VBox(2, mainL, descL);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox card = new HBox(10, rb, text);
            card.setAlignment(Pos.CENTER_LEFT);
            card.setPadding(new Insets(12, 16, 12, 16));
            card.setStyle(
                "-fx-background-color: rgba(255,255,255,0.04);" +
                "-fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1;" +
                "-fx-border-radius: 10; -fx-background-radius: 10; -fx-cursor: hand;"
            );
            card.setOnMouseClicked(e -> rb.setSelected(true));

            rb.selectedProperty().addListener((o, ov, nv) -> {
                if (nv) {
                    card.setStyle(card.getStyle()
                        .replace("rgba(255,255,255,0.04)", "rgba(91,140,247,0.10)")
                        .replace("rgba(255,255,255,0.10)", "rgba(91,140,247,0.35)"));
                } else {
                    card.setStyle(card.getStyle()
                        .replace("rgba(91,140,247,0.10)", "rgba(255,255,255,0.04)")
                        .replace("rgba(91,140,247,0.35)", "rgba(255,255,255,0.10)"));
                }
            });

            return card;
        }
    }

    // ════════════════════════════════════════════════════
    // Step 3: Output settings
    // ════════════════════════════════════════════════════

    static class Step3View extends VBox {
        private final SplitConfig config;
        private final Label       dirLabel;

        Step3View(SplitConfig config) {
            this.config = config;
            setSpacing(16);
            setStyle("-fx-background-color: transparent;");

            Label title = sectionTitle("Output settings");

            dirLabel = new Label("No directory selected");
            dirLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.40); -fx-font-size: 12px;" +
                              "-fx-font-family: 'SF Mono','Consolas',monospace;");
            dirLabel.setWrapText(true);

            Button dirBtn = glassBtn("📁  Select output directory", true);
            dirBtn.setOnAction(e -> {
                DirectoryChooser dc = new DirectoryChooser();
                dc.setTitle("Select output directory");
                // Default to source file directory
                if (config.sourceFile != null)
                    dc.setInitialDirectory(config.sourceFile.getParent().toFile());
                File dir = dc.showDialog(getScene() != null ? getScene().getWindow() : null);
                if (dir != null) {
                    config.outputDir = dir.toPath();
                    dirLabel.setText(dir.getAbsolutePath());
                    dirLabel.setStyle(dirLabel.getStyle()
                        .replace("rgba(255,255,255,0.40)", "rgba(255,255,255,0.88)"));
                }
            });

            // File name prefix
            Label prefixLabel = subLabel("Output file name prefix (optional)");
            TextField prefixField = new TextField();
            prefixField.setPromptText("example: output → output_Sheet1.xlsx");
            prefixField.setStyle(fieldStyle());
            prefixField.textProperty().addListener((o, ov, nv) -> config.filePrefix = nv);

            // Keep header
            CheckBox keepHeader = new CheckBox("Keep header row when splitting by column or row count");
            keepHeader.setSelected(true);
            keepHeader.setStyle("-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 13px;");
            keepHeader.selectedProperty().addListener((o, ov, nv) -> config.keepHeader = nv);

            getChildren().addAll(
                title,
                dirBtn, dirLabel,
                new Separator() {{ setStyle("-fx-border-color: rgba(255,255,255,0.08);"); }},
                prefixLabel, prefixField,
                keepHeader
            );
        }
    }

    // ════════════════════════════════════════════════════
    // Step 4: Execute split and show results
    // ════════════════════════════════════════════════════

    static class Step4View extends VBox {
        private final SplitConfig config;
        private final ProgressBar  progressBar;
        private final Label        progressLabel;
        private final VBox         resultBox;
        private boolean started = false;

        Step4View(SplitConfig config) {
            this.config = config;
            setSpacing(16);
            setStyle("-fx-background-color: transparent;");

            Label title = sectionTitle("Splitting...…");

            progressBar = new ProgressBar(0);
            progressBar.setMaxWidth(Double.MAX_VALUE);
            progressBar.setPrefHeight(6);
            progressBar.setStyle("-fx-accent: #5b8cf7; -fx-background-radius: 3; -fx-background-color: rgba(255,255,255,0.08);");

            progressLabel = new Label("Preparing...…");
            progressLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

            resultBox = new VBox(8);
            resultBox.setStyle("-fx-background-color: transparent;");

            getChildren().addAll(title, progressBar, progressLabel, resultBox);
        }

        void startSplit() {
            if (started) return;
            started = true;
            resultBox.getChildren().clear();

            Task<ExcelSplitter.SplitResult> task = new Task<>() {
                @Override
                protected ExcelSplitter.SplitResult call() throws Exception {
                    ExcelSplitter splitter = new ExcelSplitter(config, (pct, msg) ->
                        Platform.runLater(() -> {
                            progressBar.setProgress(pct);
                            progressLabel.setText(msg);
                        })
                    );
                    return splitter.split();
                }
            };

            task.setOnSucceeded(e -> showSuccess(task.getValue()));
            task.setOnFailed(e  -> showError(task.getException()));

            Thread t = new Thread(task);
            t.setDaemon(true);
            t.start();
        }

        private void showSuccess(ExcelSplitter.SplitResult result) {
            progressBar.setProgress(1.0);
            progressBar.setStyle(progressBar.getStyle().replace("#5b8cf7", "#4cd97b"));
            progressLabel.setText("✓ Split complete, output " + result.fileCount() + "  files");

            resultBox.getChildren().add(subLabel("Output files"));
            for (Path p : result.outputFiles()) {
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(8, 12, 8, 12));
                row.setStyle(
                    "-fx-background-color: rgba(76,217,123,0.06);" +
                    "-fx-border-color: rgba(76,217,123,0.15);" +
                    "-fx-border-width: 1; -fx-border-radius: 8; -fx-background-radius: 8;"
                );

                Label icon = new Label("📄");
                Label name = new Label(p.getFileName().toString());
                name.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 12px;" +
                              "-fx-font-family: 'SF Mono','Consolas',monospace;");
                HBox.setHgrow(name, Priority.ALWAYS);

                Button open = new Button("Open folder");
                open.setStyle(
                    "-fx-background-color: transparent; -fx-border-color: rgba(76,217,123,0.4);" +
                    "-fx-border-width: 1; -fx-border-radius: 5; -fx-text-fill: #4cd97b;" +
                    "-fx-font-size: 11px; -fx-padding: 3 8 3 8; -fx-cursor: hand;"
                );
                open.setOnAction(e -> {
                    try {
                        java.awt.Desktop.getDesktop().open(p.getParent().toFile());
                    } catch (Exception ex) { /* Skip */ }
                });

                row.getChildren().addAll(icon, name, open);
                resultBox.getChildren().add(row);
            }
        }

        private void showError(Throwable err) {
            progressBar.setProgress(0);
            progressBar.setStyle(progressBar.getStyle().replace("#5b8cf7", "#f25c5c"));
            progressLabel.setText("❌ Split failed");

            Label errLabel = new Label(err.getMessage());
            errLabel.setStyle(
                "-fx-text-fill: #f25c5c; -fx-font-size: 12px; -fx-wrap-text: true;" +
                "-fx-background-color: rgba(242,92,92,0.08); -fx-padding: 12;" +
                "-fx-background-radius: 8;"
            );
            errLabel.setWrapText(true);
            resultBox.getChildren().add(errLabel);
        }
    }

    // ════════════════════════════════════════════════════
    // Shared UI tools
    // ════════════════════════════════════════════════════

    private static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 15px; -fx-font-weight: 500;");
        return l;
    }

    static Label subLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.setStyle("-fx-text-fill: rgba(255,255,255,0.30); -fx-font-size: 10px;" +
                   "-fx-font-weight: bold; -fx-letter-spacing: 0.08em;");
        return l;
    }

    static Button glassBtn(String text, boolean primary) {
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

    private static String fieldStyle() {
        return "-fx-background-color: rgba(255,255,255,0.05);" +
               "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
               "-fx-border-radius: 8; -fx-background-radius: 8;" +
               "-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 13px; -fx-padding: 9 12 9 12;";
    }

    private static String comboStyle() {
        return "-fx-background-color: rgba(255,255,255,0.05);" +
               "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
               "-fx-border-radius: 8; -fx-background-radius: 8; -fx-text-fill: rgba(255,255,255,0.88);";
    }
}
