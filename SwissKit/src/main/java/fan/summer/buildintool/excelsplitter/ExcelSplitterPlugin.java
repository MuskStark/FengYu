package fan.summer.buildintool.excelsplitter;

import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import fan.summer.api.ai.AiTool;
import fan.summer.api.component.StepWizard;
import fan.summer.api.i18n.I18n;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.buildintool.ai.ExcelAnalyzeTool;
import fan.summer.buildintool.ai.ExcelCancelTool;
import fan.summer.buildintool.ai.ExcelComplexConfigTool;
import fan.summer.buildintool.ai.ExcelConfigureTool;
import fan.summer.buildintool.ai.ExcelExecuteTool;
import fan.summer.buildintool.ai.ExcelQueryTool;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.excel.ComplexSplitConfigEntity;
import fan.summer.database.mapper.excel.ComplexSplitConfigMapper;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.apache.ibatis.session.SqlSession;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Built-in Excel splitting tool implemented as a four-step wizard using {@link StepWizard}.
 *
 * <p>Step 1 — File Selection: drag-and-drop or file picker to select the source Excel file.
 * An async analysis pass reads all sheet headers and populates {@link SplitConfig#analysisResult}.
 *
 * <p>Step 2 — Split Mode: choose between BY_SHEET (one output per sheet), BY_COLUMN
 * (group rows by unique values in a selected column), or COMPLEX (DB-backed multi-config).
 *
 * <p>Step 3 — Confirm: display a summary of the chosen configuration and select the output
 * directory.
 *
 * <p>Step 4 — Execute: run the split on a background thread and display a progress bar
 * followed by the list of output files with an "open folder" button.
 *
 * @since 3.0.0
 * @see SwissKitJPlugin
 * @see SplitConfig
 * @see ExcelSplitter
 */
public class ExcelSplitterPlugin implements SwissKitJPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger(ExcelSplitterPlugin.class);

    private Node view;
    private final SplitConfig sharedConfig = new SplitConfig();
    private static final AtomicBoolean hasRunningTask = new AtomicBoolean(false);
    private static final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Signals the running split operation to abort. */
    public static void cancel() { cancelled.set(true); }
    /** Returns whether a cancellation has been requested. */
    public static boolean isCancelled() { return cancelled.get(); }

    @Override public String getId()          { return "fan.summer.buildin.excelsplitter"; }
    @Override public String getName()        { return I18n.get("builtin.excel-splitter.name"); }
    @Override public String getDescription() { return I18n.get("builtin.excel-splitter.desc"); }
    @Override public ToolCategory getCategory()    { return ToolCategory.OTHER; }
    @Override public String getVersion()     { return "3.0.0"; }
    @Override public String getMdiIcon()    { return "file-excel"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.TEAL; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    @Override
    public boolean hasRunningTasks() {
        return hasRunningTask.get();
    }

    @Override
    public void onActivate() {
        log.info("Excel Splitter plugin activated");
        // Note: view is intentionally NOT reset to null here. The wizard's
        // cached view is reused across activations, matching the documented
        // contract that createView() is called once and the result is cached.
        // If a fresh wizard is needed, the plugin's onDeactivate/onUnload
        // should clear the shared config instead.
    }

    @Override
    public void onDeactivate() {
        log.info("Excel Splitter plugin deactivated");
    }

    @Override
    public Node createView() {
        log.debug("Creating Excel Splitter view");
        if (view != null) return view;
        view = buildWizardView();
        return view;
    }

    public SplitConfig getSharedSplitConfig() {
        return sharedConfig;
    }

    /**
     * Builds and returns the wizard view, constructing all four step views and wiring
     * step-change callbacks.
     *
     * @return the root JavaFX node (a VBox containing the StepWizard)
     */
    private Node buildWizardView() {
        SplitConfig config = sharedConfig;
        StepWizard wizard = new StepWizard();

        Step1View step1 = new Step1View(config, wizard);
        Step2View step2 = new Step2View(config);
        Step3View step3 = new Step3View(config);
        Step4View step4 = new Step4View(config);

        wizard.addStep(I18n.get("builtin.excel.step.selectFile"), step1, step1.canProceedSupplier());
        wizard.addStep(I18n.get("builtin.excel.step.splitMode"), step2, step2.canProceedSupplier());
        wizard.addStep(I18n.get("builtin.excel.step.confirmConfig"), step3, step3.canProceedSupplier());
        wizard.addStep(I18n.get("builtin.excel.step.executeSplit"), step4, () -> true);

        wizard.build();

        wizard.setOnStepChanged((from, to, total) -> {
            log.debug("Wizard step changed: {} -> {} (total steps: {})", from, to, total);
            if (from == 0 && to == 1) step2.refresh(config);
            if (from == 1 && to == 2) step3.refresh(config);
            if (from == 2 && to == 3) step4.startSplit();
        });

        VBox root = new VBox(wizard);
        VBox.setVgrow(wizard, Priority.ALWAYS);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: transparent;");
        return root;
    }

    // ════════════════════════════════════════════════════
    // Step 1: Select file + async analysis
    // ════════════════════════════════════════════════════

    static class Step1View extends VBox {
        private final SplitConfig config;
        private final StepWizard wizard;
        private final Label fileLabel;
        private final Label statusLabel;
        private final VBox dropZone;
        private final VBox loadingOverlay;

        private final AtomicBoolean analysisRunning = new AtomicBoolean(false);
        private boolean analysisTriggered = false;

        Step1View(SplitConfig config, StepWizard wizard) {
            this.config = config;
            this.wizard = wizard;
            setStyle("-fx-background-color: transparent;");
            setSpacing(16);

            Label title = sectionTitle(I18n.get("builtin.excel.selectExcelFile"));

            fileLabel = new Label(I18n.get("builtin.excel.noFileSelected"));
            fileLabel.getStyleClass().add("sk-t3");
            fileLabel.setStyle(
                "-fx-font-size: 13px;" +
                "-fx-font-family: 'SF Mono','Consolas',monospace;"
            );
            fileLabel.setWrapText(true);

            Button pickBtn = glassBtn(I18n.get("builtin.excel.chooseFile"), true);
            pickBtn.setOnAction(e -> pickFile());

            dropZone = new VBox(14, pickBtn, fileLabel);
            dropZone.setAlignment(Pos.CENTER);
            dropZone.setPrefHeight(150);
            dropZone.setPadding(new Insets(20));
            dropZone.getStyleClass().addAll("sk-surface", "sk-outlined");
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
                List<File> files = e.getDragboard().getFiles();
                if (!files.isEmpty()) loadFile(files.get(0).toPath());
                dropZone.setStyle(dropNormalStyle());
                e.setDropCompleted(true);
                e.consume();
            });

            statusLabel = new Label();
            statusLabel.getStyleClass().add("sk-t2");
            statusLabel.setStyle("-fx-font-size: 12px;");
            statusLabel.setWrapText(true);

            ProgressIndicator spinner = new ProgressIndicator(-1);
            spinner.setPrefSize(32, 32);
            spinner.setStyle("-fx-accent: #3574F0;");
            Label analyzingLabel = new Label(I18n.get("builtin.excel.analyzing"));
            analyzingLabel.getStyleClass().add("sk-t2");
            analyzingLabel.setStyle("-fx-font-size: 13px;");
            loadingOverlay = new VBox(8, spinner, analyzingLabel);
            loadingOverlay.setAlignment(Pos.CENTER);
            loadingOverlay.setStyle(
                "-fx-background-color: rgba(0,0,0,0.55);" +
                "-fx-background-radius: 12;"
            );
            loadingOverlay.setVisible(false);
            loadingOverlay.setMouseTransparent(true);

            StackPane container = new StackPane(dropZone, loadingOverlay);
            StackPane.setAlignment(loadingOverlay, Pos.CENTER);
            loadingOverlay.prefWidthProperty().bind(container.widthProperty());
            loadingOverlay.prefHeightProperty().bind(container.heightProperty());

            getChildren().addAll(title, container, statusLabel);
        }

        void showLoading(boolean show) {
            loadingOverlay.setVisible(show);
            dropZone.setDisable(show);
        }

        java.util.function.BooleanSupplier canProceedSupplier() {
            return () -> {
                if (config.analysisResult != null) return true;
                if (config.sourceFile == null) return false;
                if (!analysisRunning.get() && !analysisTriggered) {
                    analysisTriggered = true;
                    analysisRunning.set(true);
                    hasRunningTask.set(true);
                    Platform.runLater(() -> showLoading(true));
                    startAnalysis();
                }
                return false;
            };
        }

        private void startAnalysis() {
            Task<Map<String, Map<Integer, String>>> task = new Task<>() {
                @Override
                protected Map<String, Map<Integer, String>> call() throws Exception {
                    return ExcelSplitter.analyze(config.sourceFile);
                }
            };

            task.setOnSucceeded(e -> {
                config.analysisResult = task.getValue();
                analysisRunning.set(false);
                hasRunningTask.set(false);
                showLoading(false);
                int sheetCount = config.analysisResult.size();
                String sheetNames = config.analysisResult.keySet().stream().limit(5).collect(Collectors.joining(", "));
                log.info("Excel analysis complete: {} sheets found in {}", sheetCount, config.sourceFile.getFileName());
                statusLabel.setText(I18n.get("builtin.excel.analysisSuccess", sheetCount, sheetNames + (sheetCount > 5 ? " …" : "")));
                statusLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
                wizard.goTo(1);
            });

            task.setOnFailed(e -> {
                analysisRunning.set(false);
                hasRunningTask.set(false);
                analysisTriggered = false;
                showLoading(false);
                log.error("Excel analysis failed: {}", task.getException().getMessage());
                statusLabel.setText(I18n.get("builtin.excel.analysisFailed", task.getException().getMessage()));
                statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
            });

            Thread t = new Thread(task);
            t.setDaemon(true);
            t.start();
        }

        private void pickFile() {
            FileChooser fc = new FileChooser();
            fc.setTitle(I18n.get("builtin.excel.fileChooserTitle"));
            fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(I18n.get("builtin.excel.fileFilter"), "*.xlsx", "*.xls", "*.xlsm")
            );
            File f = fc.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
            if (f != null) loadFile(f.toPath());
        }

        private void loadFile(Path path) {
            log.info("Loading Excel file: {}", path.getFileName());
            config.sourceFile = path;
            config.analysisResult = null;
            analysisTriggered = true;
            analysisRunning.set(true);
            fileLabel.setText(path.getFileName().toString());
            fileLabel.getStyleClass().remove("sk-t3");
            fileLabel.getStyleClass().add("sk-t1");
            fileLabel.setStyle(
                "-fx-font-size: 13px;" +
                "-fx-font-family: 'SF Mono','Consolas',monospace;"
            );
            statusLabel.setText(I18n.get("builtin.excel.analyzing"));
            statusLabel.setStyle("-fx-font-size: 12px;");
            showLoading(true);
            startAnalysis();
        }

        private static String dropNormalStyle() {
            return "-fx-border-width: 1; -fx-border-style: dashed;" +
                   "-fx-border-radius: 12; -fx-background-radius: 12;";
        }

        private static String dropHighlightStyle() {
            return "-fx-background-color: rgba(53,116,240,0.10);" +
                   "-fx-border-color: rgba(53,116,240,0.40);" +
                   "-fx-border-width: 1; -fx-border-style: dashed;" +
                   "-fx-border-radius: 12; -fx-background-radius: 12;";
        }
    }

    // ════════════════════════════════════════════════════
    // Step 2: Split mode selection
    // ════════════════════════════════════════════════════

    static class Step2View extends VBox {
        private final SplitConfig config;
        private final VBox detailPane;
        private final ToggleGroup modeGroup;

        private ComboBox<String> sheetCombo;
        private ComboBox<String> columnCombo;
        private ComboBox<String> complexSheetCombo;
        private TextField headerIndexField;
        private TextField columnIndexField;
        private Label complexCountLabel;

        Step2View(SplitConfig config) {
            this.config = config;
            setSpacing(16);
            setStyle("-fx-background-color: transparent;");

            Label title = sectionTitle(I18n.get("builtin.excel.splitMode.title"));

            modeGroup = new ToggleGroup();

            HBox bySheetCard   = modeCard(modeGroup, SplitConfig.SplitMode.BY_SHEET,
                "⊞", I18n.get("builtin.excel.splitMode.bySheet"), I18n.get("builtin.excel.splitMode.bySheetDesc"));
            HBox byColumnCard  = modeCard(modeGroup, SplitConfig.SplitMode.BY_COLUMN,
                "≡", I18n.get("builtin.excel.splitMode.byColumn"), I18n.get("builtin.excel.splitMode.byColumnDesc"));
            HBox complexCard   = modeCard(modeGroup, SplitConfig.SplitMode.COMPLEX,
                "⚙", I18n.get("builtin.excel.splitMode.complex"), I18n.get("builtin.excel.splitMode.complexDesc"));

            VBox modeCards = new VBox(8, bySheetCard, byColumnCard, complexCard);

            modeGroup.getToggles().get(0).setSelected(true);
            config.mode = SplitConfig.SplitMode.BY_SHEET;

            detailPane = new VBox(8);
            detailPane.setStyle("-fx-background-color: transparent;");
            detailPane.setPadding(new Insets(4, 0, 0, 0));

            modeGroup.selectedToggleProperty().addListener((obs, o, n) -> {
                if (n != null) {
                    config.mode = (SplitConfig.SplitMode) n.getUserData();
                    log.debug("Split mode changed to: {}", config.mode);
                    refreshDetail();
                }
            });

            getChildren().addAll(title, modeCards, detailPane);
        }

        void refresh(SplitConfig cfg) {
            refreshDetail();
        }

        java.util.function.BooleanSupplier canProceedSupplier() {
            return () -> switch (config.mode) {
                case BY_SHEET  -> true;
                case BY_COLUMN -> config.splitSheet != null && config.splitColumn != null;
                case COMPLEX   -> {
                    if (config.complexTaskId == null) yield false;
                    try (SqlSession session = DatabaseInit.getSqlSession()) {
                        ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
                        List<ComplexSplitConfigEntity> rows = mapper.selectAllByTaskId(config.complexTaskId);
                        yield rows != null && !rows.isEmpty();
                    } catch (Exception e) {
                        yield false;
                    }
                }
            };
        }

        private void refreshDetail() {
            detailPane.getChildren().clear();
            if (config.analysisResult == null) return;
            List<String> sheets = new ArrayList<>(config.analysisResult.keySet());

            switch (config.mode) {
                case BY_SHEET -> buildBySheetDetail(sheets);
                case BY_COLUMN -> buildByColumnDetail(sheets);
                case COMPLEX -> buildComplexDetail(sheets);
            }
        }

        private void buildBySheetDetail(List<String> sheets) {
            config.selectedSheets = new ArrayList<>(sheets);

            Label lbl = new Label(I18n.get("builtin.excel.bySheetSummary", sheets.size()));
            lbl.getStyleClass().add("sk-t2");
            lbl.setStyle("-fx-font-size: 13px;");
            lbl.setWrapText(true);

            VBox infoBox = new VBox(6, lbl);
            infoBox.setPadding(new Insets(12, 16, 12, 16));
            infoBox.getStyleClass().addAll("sk-surface", "sk-outlined");
            infoBox.setStyle(
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;"
            );

            detailPane.getChildren().add(infoBox);
        }

        private void buildByColumnDetail(List<String> sheets) {
            Label sheetLbl = subLabel(I18n.get("builtin.excel.selectSheet"));
            sheetCombo = new ComboBox<>();
            sheetCombo.getItems().addAll(sheets);
            sheetCombo.setMaxWidth(Double.MAX_VALUE);
            sheetCombo.setPromptText(I18n.get("builtin.excel.selectSheetPrompt"));
            sheetCombo.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t1");
            sheetCombo.setStyle(comboStyle());

            Label colLbl = subLabel(I18n.get("builtin.excel.selectColumn"));
            columnCombo = new ComboBox<>();
            columnCombo.setMaxWidth(Double.MAX_VALUE);
            columnCombo.setPromptText(I18n.get("builtin.excel.selectColumnPrompt"));
            columnCombo.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t1");
            columnCombo.setStyle(comboStyle());
            columnCombo.setDisable(true);

            sheetCombo.valueProperty().addListener((o, ov, nv) -> {
                config.splitSheet = nv;
                config.splitColumn = null;
                config.splitColumnIndex = -1;
                columnCombo.getItems().clear();
                columnCombo.setDisable(true);
                if (nv != null) {
                    Map<Integer, String> headers = config.analysisResult.get(nv);
                    if (headers != null) {
                        new TreeMap<>(headers).forEach((idx, name) -> columnCombo.getItems().add(name));
                    }
                    columnCombo.setDisable(false);
                    columnCombo.setPromptText(I18n.get("builtin.excel.selectColumnPrompt2"));
                }
            });

            columnCombo.valueProperty().addListener((o, ov, nv) -> {
                config.splitColumn = nv;
                if (nv != null && config.splitSheet != null) {
                    Map<Integer, String> headers = config.analysisResult.get(config.splitSheet);
                    if (headers != null) {
                        for (Map.Entry<Integer, String> entry : headers.entrySet()) {
                            if (nv.equals(entry.getValue())) {
                                config.splitColumnIndex = entry.getKey();
                                break;
                            }
                        }
                    }
                }
            });

            detailPane.getChildren().addAll(sheetLbl, sheetCombo, colLbl, columnCombo);
        }

        private void buildComplexDetail(List<String> sheets) {
            if (config.complexTaskId == null) {
                config.complexTaskId = UUID.randomUUID().toString();
            }

            Label sheetLbl      = subLabel(I18n.get("builtin.excel.sheetName"));
            complexSheetCombo   = new ComboBox<>();
            complexSheetCombo.getItems().addAll(sheets);
            complexSheetCombo.setMaxWidth(Double.MAX_VALUE);
            complexSheetCombo.setPromptText(I18n.get("builtin.excel.sheetNamePrompt"));
            complexSheetCombo.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t1");
            complexSheetCombo.setStyle(comboStyle());

            Label headerLbl   = subLabel(I18n.get("builtin.excel.headerRow"));
            headerIndexField  = new TextField();
            headerIndexField.setPromptText(I18n.get("builtin.excel.headerRowPrompt"));
            headerIndexField.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t1");
            headerIndexField.setStyle(fieldStyle());

            Label colIdxLbl    = subLabel(I18n.get("builtin.excel.splitColumn"));
            columnIndexField   = new TextField();
            columnIndexField.setPromptText(I18n.get("builtin.excel.splitColumnPrompt"));
            columnIndexField.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t1");
            columnIndexField.setStyle(fieldStyle());

            Button addBtn = glassBtn(I18n.get("builtin.excel.addConfig"), true);

            complexCountLabel = new Label();
            refreshComplexCount();
            complexCountLabel.getStyleClass().add("sk-t2");
            complexCountLabel.setStyle("-fx-font-size: 12px;");

            Button clearBtn = glassBtn(I18n.get("builtin.excel.clearAll"), false);
            clearBtn.setOnAction(e -> {
                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
                    mapper.deleteAllByTaskId(config.complexTaskId);
                    session.commit();
                } catch (Exception ex) {
                    log.warn("Failed to clear complex split configs: {}", ex.getMessage());
                }
                refreshComplexCount();
            });

            addBtn.setOnAction(e -> {
                String sheet = complexSheetCombo.getValue();
                String headerText = headerIndexField.getText().trim();
                String colText    = columnIndexField.getText().trim();
                if (sheet == null || headerText.isEmpty() || colText.isEmpty()) return;

                int headerIdx, colIdx;
                try {
                    headerIdx = Integer.parseInt(headerText);
                    colIdx    = Integer.parseInt(colText);
                } catch (NumberFormatException ex) {
                    return;
                }

                ComplexSplitConfigEntity entity = new ComplexSplitConfigEntity();
                entity.setTaskId(config.complexTaskId);
                entity.setFieldName(config.sourceFile != null ? config.sourceFile.getFileName().toString() : "");
                entity.setSheetName(sheet);
                entity.setHeaderIndex(headerIdx);
                entity.setColumnIndex(colIdx);

                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
                    mapper.insert(entity);
                    session.commit();
                } catch (Exception ex) {
                    log.warn("Failed to insert complex split config: {}", ex.getMessage());
                }

                headerIndexField.clear();
                columnIndexField.clear();
                refreshComplexCount();
            });

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox footer = new HBox(8, complexCountLabel, spacer, clearBtn);
            footer.setAlignment(Pos.CENTER_LEFT);

            detailPane.getChildren().addAll(
                sheetLbl, complexSheetCombo,
                headerLbl, headerIndexField,
                colIdxLbl, columnIndexField,
                addBtn, footer
            );
        }

        private void refreshComplexCount() {
            if (config.complexTaskId == null || complexCountLabel == null) return;
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
                int count = mapper.selectAllByTaskId(config.complexTaskId).size();
                complexCountLabel.setText(I18n.get("builtin.excel.configCount", count));
            } catch (Exception e) {
                complexCountLabel.setText(I18n.get("builtin.excel.configCount", 0));
            }
        }

        private HBox modeCard(ToggleGroup group, SplitConfig.SplitMode mode,
                              String icon, String title, String desc) {
            RadioButton rb = new RadioButton();
            rb.setToggleGroup(group);
            rb.setUserData(mode);
            rb.setStyle("-fx-text-fill: transparent;");

            Label iconLabel = new Label(icon);
            iconLabel.getStyleClass().add("sk-t2");
            iconLabel.setStyle(
                "-fx-font-size: 16px;" +
                "-fx-min-width: 24; -fx-alignment: center;"
            );
            Label titleLabel = new Label(title);
            titleLabel.getStyleClass().add("sk-t1");
            titleLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 500;");
            Label descLabel = new Label(desc);
            descLabel.getStyleClass().add("sk-t3");
            descLabel.setStyle("-fx-font-size: 11px;");

            VBox textBox = new VBox(2, titleLabel, descLabel);
            HBox.setHgrow(textBox, Priority.ALWAYS);

            HBox card = new HBox(12, rb, iconLabel, textBox);
            card.setAlignment(Pos.CENTER_LEFT);
            card.setPadding(new Insets(12, 16, 12, 16));
            card.getStyleClass().addAll("sk-surface", "sk-outlined");
            card.setStyle(cardNormalStyle());
            card.setOnMouseClicked(e -> rb.setSelected(true));

            rb.selectedProperty().addListener((o, ov, nv) -> {
                if (nv) {
                    card.getStyleClass().removeAll("sk-surface", "sk-outlined");
                    card.setStyle(cardSelectedStyle());
                } else {
                    card.getStyleClass().addAll("sk-surface", "sk-outlined");
                    card.setStyle(cardNormalStyle());
                }
            });

            return card;
        }

        private static String cardNormalStyle() {
            return "-fx-border-width: 1;" +
                   "-fx-border-radius: 10; -fx-background-radius: 10; -fx-cursor: hand;";
        }

        private static String cardSelectedStyle() {
            return "-fx-background-color: rgba(53,116,240,0.10);" +
                   "-fx-border-color: rgba(53,116,240,0.35); -fx-border-width: 1;" +
                   "-fx-border-radius: 10; -fx-background-radius: 10; -fx-cursor: hand;";
        }
    }

    // ════════════════════════════════════════════════════
    // Step 3: Confirm config + select output directory
    // ════════════════════════════════════════════════════

    static class Step3View extends VBox {
        private final SplitConfig config;
        private final VBox        summaryContent;
        private final Label       dirLabel;

        Step3View(SplitConfig config) {
            this.config = config;
            setSpacing(16);
            setStyle("-fx-background-color: transparent;");

            Label configTitle = sectionTitle(I18n.get("builtin.excel.confirmConfig"));

            summaryContent = new VBox(8);
            summaryContent.setStyle("-fx-background-color: transparent;");

            VBox summaryCard = new VBox(summaryContent);
            summaryCard.setPadding(new Insets(14, 16, 14, 16));
            summaryCard.getStyleClass().addAll("sk-surface", "sk-outlined");
            summaryCard.setStyle(
                "-fx-border-width: 1;" +
                "-fx-border-radius: 10; -fx-background-radius: 10;"
            );

            Separator sep = new Separator();
            // Themed via the global .separator .line rule in swisskit-common.css.

            Label outputTitle = sectionTitle(I18n.get("builtin.excel.outputDir"));

            dirLabel = new Label(I18n.get("builtin.excel.notSelected"));
            dirLabel.getStyleClass().add("sk-t3");
            dirLabel.setStyle(
                "-fx-font-size: 12px;" +
                "-fx-font-family: 'SF Mono','Consolas',monospace;"
            );
            dirLabel.setWrapText(true);

            Button dirBtn = glassBtn(I18n.get("builtin.excel.chooseOutputDir"), false);
            dirBtn.setOnAction(e -> {
                DirectoryChooser dc = new DirectoryChooser();
                dc.setTitle(I18n.get("builtin.excel.chooseOutputDirTitle"));
                if (config.sourceFile != null)
                    dc.setInitialDirectory(config.sourceFile.getParent().toFile());
                File dir = dc.showDialog(getScene() != null ? getScene().getWindow() : null);
                if (dir != null) {
                    config.outputDir = dir.toPath();
                    dirLabel.setText(dir.getAbsolutePath());
                    dirLabel.getStyleClass().remove("sk-t3");
                    dirLabel.getStyleClass().add("sk-t1");
                    dirLabel.setStyle(
                        "-fx-font-size: 12px;" +
                        "-fx-font-family: 'SF Mono','Consolas',monospace;"
                    );
                }
            });

            getChildren().addAll(configTitle, summaryCard, sep, outputTitle, dirBtn, dirLabel);
        }

        void refresh(SplitConfig cfg) {
            summaryContent.getChildren().clear();
            if (cfg.analysisResult == null) return;

            addRow(I18n.get("builtin.excel.sourceFile"), cfg.sourceFile != null ? cfg.sourceFile.getFileName().toString() : "—");
            addRow(I18n.get("builtin.excel.totalSheets"), String.valueOf(cfg.analysisResult.size()));

            switch (cfg.mode) {
                case BY_SHEET -> {
                    List<String> sel = cfg.selectedSheets != null ? cfg.selectedSheets : List.of();
                    addRow(I18n.get("builtin.excel.splitModeLabel"), I18n.get("builtin.excel.splitModeBySheet"));
                    addRow(I18n.get("builtin.excel.sheetsToExport"), String.valueOf(sel.size()));
                    addRow(I18n.get("builtin.excel.expectedFiles"), String.valueOf(sel.size()));
                    if (!sel.isEmpty()) {
                        addRow(I18n.get("builtin.excel.sheetsToExport"), String.join(", ", sel));
                    }
                }
                case BY_COLUMN -> {
                    Map<Integer, String> headers = cfg.analysisResult.get(cfg.splitSheet);
                    int totalCols = headers != null ? headers.size() : 0;
                    int colPos = cfg.splitColumnIndex + 1;
                    addRow(I18n.get("builtin.excel.splitModeLabel"), I18n.get("builtin.excel.splitModeByColumn"));
                    addRow(I18n.get("builtin.excel.targetSheet"), cfg.splitSheet != null ? cfg.splitSheet : "—");
                    addRow(I18n.get("builtin.excel.splitColumnLabel"),
                        I18n.get("builtin.excel.columnInfo",
                            cfg.splitColumn != null ? cfg.splitColumn : "—", colPos, totalCols));
                }
                case COMPLEX -> {
                    addRow(I18n.get("builtin.excel.splitModeLabel"), I18n.get("builtin.excel.splitModeComplex"));
                    if (cfg.complexTaskId != null) {
                        List<ComplexSplitConfigEntity> rows = List.of();
                        try (SqlSession session = DatabaseInit.getSqlSession()) {
                            rows = session.getMapper(ComplexSplitConfigMapper.class)
                                         .selectAllByTaskId(cfg.complexTaskId);
                        } catch (Exception ignored) {}
                        addRow(I18n.get("builtin.excel.configCountLabel"), String.valueOf(rows.size()));
                        for (ComplexSplitConfigEntity r : rows) {
                            boolean isCopyAll = Integer.valueOf(-1).equals(r.getHeaderIndex())
                                             && Integer.valueOf(-1).equals(r.getColumnIndex());
                            String detail = isCopyAll
                                ? I18n.get("builtin.excel.fullSheetCopy")
                                : I18n.get("builtin.excel.headerRowLabel", r.getHeaderIndex(), r.getColumnIndex());
                            addDetailRow("• " + r.getSheetName(), detail);
                        }
                    }
                }
            }
        }

        private void addRow(String key, String value) {
            Label keyL = new Label(key + "：");
            keyL.getStyleClass().add("sk-t2");
            keyL.setStyle("-fx-font-size: 12px; -fx-min-width: 130;");
            Label valL = new Label(value);
            valL.getStyleClass().add("sk-t1");
            valL.setStyle("-fx-font-size: 12px;");
            valL.setWrapText(true);
            HBox row = new HBox(4, keyL, valL);
            row.setAlignment(Pos.TOP_LEFT);
            summaryContent.getChildren().add(row);
        }

        private void addDetailRow(String key, String value) {
            Label keyL = new Label(key);
            keyL.getStyleClass().add("sk-t2");
            keyL.setStyle("-fx-font-size: 12px; -fx-min-width: 130;" +
                          "-fx-font-family: 'SF Mono','Consolas',monospace;");
            Label valL = new Label(value);
            valL.getStyleClass().add("sk-t2");
            valL.setStyle("-fx-font-size: 11px;");
            valL.setWrapText(true);
            HBox row = new HBox(8, keyL, valL);
            row.setAlignment(Pos.TOP_LEFT);
            row.setPadding(new Insets(0, 0, 0, 12));
            summaryContent.getChildren().add(row);
        }

        java.util.function.BooleanSupplier canProceedSupplier() {
            return () -> config.outputDir != null && Files.isDirectory(config.outputDir);
        }
    }

    // ════════════════════════════════════════════════════
    // Step 4: Execute split + show results
    // ════════════════════════════════════════════════════

    static class Step4View extends VBox {
        private final SplitConfig config;
        private final ProgressBar progressBar;
        private final Label progressLabel;
        private final VBox resultBox;
        private boolean started = false;

        Step4View(SplitConfig config) {
            this.config = config;
            setSpacing(16);
            setStyle("-fx-background-color: transparent;");

            Label title = sectionTitle(I18n.get("builtin.excel.executeSplit"));

            progressBar = new ProgressBar(0);
            progressBar.setMaxWidth(Double.MAX_VALUE);

            progressLabel = new Label(I18n.get("builtin.excel.preparing"));
            progressLabel.getStyleClass().add("sk-t2");
            progressLabel.setStyle("-fx-font-size: 12px;");

            resultBox = new VBox(8);
            resultBox.setStyle("-fx-background-color: transparent;");

            getChildren().addAll(title, progressBar, progressLabel, resultBox);
        }

        void startSplit() {
            if (started) return;
            log.info("Starting Excel split operation");
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
            task.setOnFailed(e   -> showError(task.getException()));

            hasRunningTask.set(true);
            Thread t = new Thread(task);
            t.setDaemon(true);
            t.start();
        }

        private void showSuccess(ExcelSplitter.SplitResult result) {
            hasRunningTask.set(false);
            log.info("Excel split complete: {} output files created", result.fileCount());
            progressBar.setProgress(1.0);
            progressBar.getStyleClass().removeAll("success", "danger");
            progressBar.getStyleClass().add("success");
            progressLabel.setText(I18n.get("builtin.excel.splitComplete", result.fileCount()));
            progressLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");

            resultBox.getChildren().add(subLabel(I18n.get("builtin.excel.outputFileList")));

            ScrollPane scroll = new ScrollPane();
            scroll.setFitToWidth(true);
            scroll.setPrefHeight(Math.min(result.outputFiles().size() * 46 + 10, 220));
            scroll.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");

            VBox fileList = new VBox(6);
            fileList.setStyle("-fx-background-color: transparent;");

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
                name.getStyleClass().add("sk-t1");
                name.setStyle(
                    "-fx-font-size: 12px;" +
                    "-fx-font-family: 'SF Mono','Consolas',monospace;"
                );
                HBox.setHgrow(name, Priority.ALWAYS);

                Button openBtn = new Button(I18n.get("builtin.excel.openFolder"));
                openBtn.setStyle(
                    "-fx-background-color: transparent;" +
                    "-fx-border-color: rgba(76,217,123,0.4); -fx-border-width: 1;" +
                    "-fx-border-radius: 5; -fx-text-fill: #4cd97b;" +
                    "-fx-font-size: 11px; -fx-padding: 3 8 3 8; -fx-cursor: hand;"
                );
                openBtn.setOnAction(e -> {
                    try {
                        java.awt.Desktop.getDesktop().open(p.getParent().toFile());
                    } catch (Exception ex) { /* skip */ }
                });

                row.getChildren().addAll(icon, name, openBtn);
                fileList.getChildren().add(row);
            }

            scroll.setContent(fileList);
            resultBox.getChildren().add(scroll);
        }

        private void showError(Throwable err) {
            hasRunningTask.set(false);
            log.error("Excel split failed: " + err.getMessage(), err);
            progressBar.setProgress(1.0);
            progressBar.getStyleClass().removeAll("success", "danger");
            progressBar.getStyleClass().add("danger");
            progressLabel.setText(I18n.get("builtin.excel.splitFailed"));
            progressLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");

            Label errLabel = new Label(err.getMessage() != null ? err.getMessage() : err.toString());
            errLabel.setStyle(
                "-fx-text-fill: #f25c5c; -fx-font-size: 12px;" +
                "-fx-background-color: rgba(242,92,92,0.08);" +
                "-fx-padding: 12; -fx-background-radius: 8;"
            );
            errLabel.setWrapText(true);
            resultBox.getChildren().add(errLabel);
        }
    }

    // ════════════════════════════════════════════════════
    // Shared UI helpers (package-accessible for inner classes)
    // ════════════════════════════════════════════════════

    static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sk-t1");
        l.setStyle("-fx-font-size: 15px; -fx-font-weight: 500;");
        return l;
    }

    static Label subLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.getStyleClass().add("sk-t3");
        l.setStyle(
            "-fx-font-size: 10px;" +
            "-fx-font-weight: bold;"
        );
        return l;
    }

    static Button glassBtn(String text, boolean primary) {
        Button btn = new Button(text);
        if (primary) {
            btn.setStyle(
                "-fx-background-color: #3574F0; -fx-text-fill: white; -fx-font-size: 13px;" +
                "-fx-font-weight: 500; -fx-background-radius: 8; -fx-border-width: 0;" +
                "-fx-padding: 10 20 10 20; -fx-cursor: hand;"
            );
        } else {
            btn.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t2");
            btn.setStyle(
                "-fx-border-width: 1;" +
                "-fx-font-size: 13px;" +
                "-fx-background-radius: 8; -fx-border-radius: 8;" +
                "-fx-padding: 10 20 10 20; -fx-cursor: hand;"
            );
        }
        return btn;
    }

    private static String fieldStyle() {
        return "-fx-border-width: 1;" +
               "-fx-border-radius: 8; -fx-background-radius: 8;" +
               "-fx-font-size: 13px;" +
               "-fx-padding: 9 12 9 12;";
    }

    private static String comboStyle() {
        return "-fx-border-width: 1;" +
               "-fx-border-radius: 8; -fx-background-radius: 8;";
    }

    @Override
    public List<AiTool> aiTools() {
        return List.of(
            new ExcelAnalyzeTool(this),
            new ExcelConfigureTool(this),
            new ExcelComplexConfigTool(this),
            new ExcelExecuteTool(this),
            new ExcelQueryTool(this),
            new ExcelCancelTool()
        );
    }
}
