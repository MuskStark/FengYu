package fan.summer.buildintool.excelsplitter;

import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import fan.summer.api.component.StepWizard;
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

public class ExcelSplitterPlugin implements SwissKitJPlugin {

    private Node view;

    @Override public String getId()          { return "fan.summer.buildin.excelsplitter"; }
    @Override public String getName()        { return "Excel拆分"; }
    @Override public String getDescription() { return "按Sheet/列值/复杂配置拆分Excel文件"; }
    @Override public ToolCategory getCategory()    { return ToolCategory.OTHER; }
    @Override public String getVersion()     { return "3.0.0"; }
    @Override public String getMdiIcon()    { return "file-excel"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.TEAL; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    @Override
    public void onActivate() {
        view = null;
    }

    @Override
    public Node createView() {
        if (view != null) return view;
        view = buildWizardView();
        return view;
    }

    private Node buildWizardView() {
        SplitConfig config = new SplitConfig();
        StepWizard wizard = new StepWizard();

        Step1View step1 = new Step1View(config, wizard);
        Step2View step2 = new Step2View(config);
        Step3View step3 = new Step3View(config);
        Step4View step4 = new Step4View(config);

        wizard.addStep("选择文件", step1, step1.canProceedSupplier());
        wizard.addStep("拆分模式", step2, step2.canProceedSupplier());
        wizard.addStep("确认配置", step3, step3.canProceedSupplier());
        wizard.addStep("执行拆分", step4, () -> true);

        wizard.build();

        wizard.setOnStepChanged((from, to, total) -> {
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
    // Step 1: 选择文件 + 异步分析
    // ════════════════════════════════════════════════════

    static class Step1View extends VBox {
        private final SplitConfig config;
        private final StepWizard wizard;
        private final Label fileLabel;
        private final Label statusLabel;
        private final VBox dropZone;
        private final VBox loadingOverlay;

        // Signals that analysis is already running so canProceed doesn't restart it
        private final AtomicBoolean analysisRunning = new AtomicBoolean(false);
        // Set to true once to prevent re-triggering the first canProceed → analyze chain
        private boolean analysisTriggered = false;

        Step1View(SplitConfig config, StepWizard wizard) {
            this.config = config;
            this.wizard = wizard;
            setStyle("-fx-background-color: transparent;");
            setSpacing(16);

            Label title = sectionTitle("选择 Excel 文件");

            fileLabel = new Label("未选择文件");
            fileLabel.setStyle(
                "-fx-text-fill: rgba(255,255,255,0.40); -fx-font-size: 13px;" +
                "-fx-font-family: 'SF Mono','Consolas',monospace;"
            );
            fileLabel.setWrapText(true);

            Button pickBtn = glassBtn("📂  选择文件", true);
            pickBtn.setOnAction(e -> pickFile());

            dropZone = new VBox(14, pickBtn, fileLabel);
            dropZone.setAlignment(Pos.CENTER);
            dropZone.setPrefHeight(150);
            dropZone.setPadding(new Insets(20));
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
            statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");
            statusLabel.setWrapText(true);

            // Loading overlay placed over the drop zone
            ProgressIndicator spinner = new ProgressIndicator(-1);
            spinner.setPrefSize(32, 32);
            spinner.setStyle("-fx-accent: #5b8cf7;");
            Label analyzingLabel = new Label("正在分析...");
            analyzingLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 13px;");
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
            // Make loading overlay fill the entire drop zone area
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
                // Analysis hasn't started yet — trigger it now (first Next click with file selected)
                if (!analysisRunning.get() && !analysisTriggered) {
                    analysisTriggered = true;
                    analysisRunning.set(true);
                    Platform.runLater(() -> showLoading(true));
                    startAnalysis();
                }
                // While running, return false silently (no shake — wizard sees false and stays)
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
                showLoading(false);
                int sheetCount = config.analysisResult.size();
                statusLabel.setText("✓ 共 " + sheetCount + " 个Sheet：" +
                    config.analysisResult.keySet().stream().limit(5).collect(Collectors.joining(", ")) +
                    (sheetCount > 5 ? " …" : ""));
                statusLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
                // Automatically advance to step 2 now that analysis is done
                wizard.goTo(1);
            });

            task.setOnFailed(e -> {
                analysisRunning.set(false);
                analysisTriggered = false;
                showLoading(false);
                statusLabel.setText("❌ 分析失败：" + task.getException().getMessage());
                statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
            });

            new Thread(task) {{ setDaemon(true); }}.start();
        }

        private void pickFile() {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择 Excel 文件");
            fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel 文件", "*.xlsx", "*.xls", "*.xlsm")
            );
            File f = fc.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
            if (f != null) loadFile(f.toPath());
        }

        private void loadFile(Path path) {
            config.sourceFile = path;
            config.analysisResult = null;
            analysisTriggered = false;
            analysisRunning.set(false);
            fileLabel.setText(path.getFileName().toString());
            fileLabel.setStyle(
                "-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 13px;" +
                "-fx-font-family: 'SF Mono','Consolas',monospace;"
            );
            statusLabel.setText("已选择文件，点击「下一步」开始分析");
            statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");
        }

        private static String dropNormalStyle() {
            return "-fx-background-color: rgba(255,255,255,0.03);" +
                   "-fx-border-color: rgba(255,255,255,0.12);" +
                   "-fx-border-width: 1; -fx-border-style: dashed;" +
                   "-fx-border-radius: 12; -fx-background-radius: 12;";
        }

        private static String dropHighlightStyle() {
            return "-fx-background-color: rgba(91,140,247,0.10);" +
                   "-fx-border-color: rgba(91,140,247,0.40);" +
                   "-fx-border-width: 1; -fx-border-style: dashed;" +
                   "-fx-border-radius: 12; -fx-background-radius: 12;";
        }
    }

    // ════════════════════════════════════════════════════
    // Step 2: 拆分模式选择
    // ════════════════════════════════════════════════════

    static class Step2View extends VBox {
        private final SplitConfig config;
        private final VBox detailPane;
        private final ToggleGroup modeGroup;

        // BY_SHEET controls
        private VBox sheetCheckBoxes;
        // BY_COLUMN controls
        private ComboBox<String> sheetCombo;
        private ComboBox<String> columnCombo;
        // COMPLEX controls
        private ComboBox<String> complexSheetCombo;
        private TextField headerIndexField;
        private TextField columnIndexField;
        private Label complexCountLabel;

        Step2View(SplitConfig config) {
            this.config = config;
            setSpacing(16);
            setStyle("-fx-background-color: transparent;");

            Label title = sectionTitle("拆分模式");

            modeGroup = new ToggleGroup();

            HBox bySheetCard   = modeCard(modeGroup, SplitConfig.SplitMode.BY_SHEET,
                "⊞", "按Sheet拆分",   "每个Sheet输出一个独立文件");
            HBox byColumnCard  = modeCard(modeGroup, SplitConfig.SplitMode.BY_COLUMN,
                "≡", "按列值拆分",    "按某列的不同取值分组，每组输出一个文件");
            HBox complexCard   = modeCard(modeGroup, SplitConfig.SplitMode.COMPLEX,
                "⚙", "复杂拆分",      "多配置规则，支持列值拆分+整Sheet复制");

            VBox modeCards = new VBox(8, bySheetCard, byColumnCard, complexCard);

            modeGroup.getToggles().get(0).setSelected(true);
            config.mode = SplitConfig.SplitMode.BY_SHEET;

            detailPane = new VBox(8);
            detailPane.setStyle("-fx-background-color: transparent;");
            detailPane.setPadding(new Insets(4, 0, 0, 0));

            modeGroup.selectedToggleProperty().addListener((obs, o, n) -> {
                if (n != null) {
                    config.mode = (SplitConfig.SplitMode) n.getUserData();
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
                case BY_SHEET  -> config.selectedSheets != null && !config.selectedSheets.isEmpty();
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
            Label lbl = subLabel("选择要导出的Sheet（可多选）");

            sheetCheckBoxes = new VBox(4);
            sheetCheckBoxes.setStyle(
                "-fx-background-color: rgba(255,255,255,0.04);" +
                "-fx-border-color: rgba(255,255,255,0.10); -fx-border-radius: 8;" +
                "-fx-background-radius: 8; -fx-padding: 10;"
            );

            config.selectedSheets = new ArrayList<>(sheets);

            for (String sheet : sheets) {
                CheckBox cb = new CheckBox(sheet);
                cb.setSelected(true);
                cb.setStyle("-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 13px;");
                cb.selectedProperty().addListener((o, ov, nv) -> {
                    if (nv) {
                        if (!config.selectedSheets.contains(sheet)) config.selectedSheets.add(sheet);
                    } else {
                        config.selectedSheets.remove(sheet);
                    }
                });
                sheetCheckBoxes.getChildren().add(cb);
            }

            ScrollPane scroll = new ScrollPane(sheetCheckBoxes);
            scroll.setFitToWidth(true);
            scroll.setPrefHeight(Math.min(sheets.size() * 30 + 20, 180));
            scroll.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");

            Button selectAll = glassBtn("全选", false);
            Button clearAll  = glassBtn("清空", false);
            selectAll.setOnAction(e -> {
                sheetCheckBoxes.getChildren().forEach(node -> ((CheckBox) node).setSelected(true));
            });
            clearAll.setOnAction(e -> {
                sheetCheckBoxes.getChildren().forEach(node -> ((CheckBox) node).setSelected(false));
            });

            HBox btns = new HBox(8, selectAll, clearAll);
            detailPane.getChildren().addAll(lbl, scroll, btns);
        }

        private void buildByColumnDetail(List<String> sheets) {
            Label sheetLbl = subLabel("选择Sheet");
            sheetCombo = new ComboBox<>();
            sheetCombo.getItems().addAll(sheets);
            sheetCombo.setMaxWidth(Double.MAX_VALUE);
            sheetCombo.setPromptText("请选择Sheet...");
            sheetCombo.setStyle(comboStyle());

            Label colLbl = subLabel("选择拆分列");
            columnCombo = new ComboBox<>();
            columnCombo.setMaxWidth(Double.MAX_VALUE);
            columnCombo.setPromptText("请先选择Sheet...");
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
                        // Preserve column order
                        new TreeMap<>(headers).forEach((idx, name) -> columnCombo.getItems().add(name));
                    }
                    columnCombo.setDisable(false);
                    columnCombo.setPromptText("请选择列...");
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
            // Generate a stable task ID for this complex config session
            if (config.complexTaskId == null) {
                config.complexTaskId = UUID.randomUUID().toString();
            }

            Label sheetLbl      = subLabel("Sheet名称");
            complexSheetCombo   = new ComboBox<>();
            complexSheetCombo.getItems().addAll(sheets);
            complexSheetCombo.setMaxWidth(Double.MAX_VALUE);
            complexSheetCombo.setPromptText("选择Sheet...");
            complexSheetCombo.setStyle(comboStyle());

            Label headerLbl   = subLabel("表头行号（1起，-1表示整Sheet复制）");
            headerIndexField  = new TextField();
            headerIndexField.setPromptText("例：1");
            headerIndexField.setStyle(fieldStyle());

            Label colIdxLbl    = subLabel("拆分列号（1起，-1表示整Sheet复制）");
            columnIndexField   = new TextField();
            columnIndexField.setPromptText("例：3  或 -1");
            columnIndexField.setStyle(fieldStyle());

            Button addBtn = glassBtn("添加配置", true);

            complexCountLabel = new Label();
            refreshComplexCount();
            complexCountLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.60); -fx-font-size: 12px;");

            Button clearBtn = glassBtn("清空全部", false);
            clearBtn.setOnAction(e -> {
                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
                    mapper.deleteAllByTaskId(config.complexTaskId);
                    session.commit();
                } catch (Exception ex) {
                    // ignore
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
                    // ignore
                }

                headerIndexField.clear();
                columnIndexField.clear();
                refreshComplexCount();
            });

            HBox footer = new HBox(8, complexCountLabel, new Region() {{
                HBox.setHgrow(this, Priority.ALWAYS);
            }}, clearBtn);
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
                complexCountLabel.setText("已添加 " + count + " 条配置");
            } catch (Exception e) {
                complexCountLabel.setText("已添加 0 条配置");
            }
        }

        private HBox modeCard(ToggleGroup group, SplitConfig.SplitMode mode,
                              String icon, String title, String desc) {
            RadioButton rb = new RadioButton();
            rb.setToggleGroup(group);
            rb.setUserData(mode);
            rb.setStyle("-fx-text-fill: transparent;");

            Label iconLabel = new Label(icon);
            iconLabel.setStyle(
                "-fx-text-fill: rgba(255,255,255,0.70); -fx-font-size: 16px;" +
                "-fx-min-width: 24; -fx-alignment: center;"
            );
            Label titleLabel = new Label(title);
            titleLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 13px; -fx-font-weight: 500;");
            Label descLabel = new Label(desc);
            descLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.40); -fx-font-size: 11px;");

            VBox textBox = new VBox(2, titleLabel, descLabel);
            HBox.setHgrow(textBox, Priority.ALWAYS);

            HBox card = new HBox(12, rb, iconLabel, textBox);
            card.setAlignment(Pos.CENTER_LEFT);
            card.setPadding(new Insets(12, 16, 12, 16));
            card.setStyle(cardNormalStyle());
            card.setOnMouseClicked(e -> rb.setSelected(true));

            rb.selectedProperty().addListener((o, ov, nv) ->
                card.setStyle(nv ? cardSelectedStyle() : cardNormalStyle())
            );

            return card;
        }

        private static String cardNormalStyle() {
            return "-fx-background-color: rgba(255,255,255,0.04);" +
                   "-fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1;" +
                   "-fx-border-radius: 10; -fx-background-radius: 10; -fx-cursor: hand;";
        }

        private static String cardSelectedStyle() {
            return "-fx-background-color: rgba(91,140,247,0.10);" +
                   "-fx-border-color: rgba(91,140,247,0.35); -fx-border-width: 1;" +
                   "-fx-border-radius: 10; -fx-background-radius: 10; -fx-cursor: hand;";
        }
    }

    // ════════════════════════════════════════════════════
    // Step 3: 确认配置 + 选择输出目录
    // ════════════════════════════════════════════════════

    static class Step3View extends VBox {
        private final SplitConfig config;
        private final VBox        summaryContent;
        private final Label       dirLabel;

        Step3View(SplitConfig config) {
            this.config = config;
            setSpacing(16);
            setStyle("-fx-background-color: transparent;");

            Label configTitle = sectionTitle("确认配置");

            summaryContent = new VBox(8);
            summaryContent.setStyle("-fx-background-color: transparent;");

            VBox summaryCard = new VBox(summaryContent);
            summaryCard.setPadding(new Insets(14, 16, 14, 16));
            summaryCard.setStyle(
                "-fx-background-color: rgba(255,255,255,0.04);" +
                "-fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1;" +
                "-fx-border-radius: 10; -fx-background-radius: 10;"
            );

            Separator sep = new Separator();
            sep.setStyle("-fx-border-color: rgba(255,255,255,0.08);");

            Label outputTitle = sectionTitle("输出目录");

            dirLabel = new Label("未选择");
            dirLabel.setStyle(
                "-fx-text-fill: rgba(255,255,255,0.40); -fx-font-size: 12px;" +
                "-fx-font-family: 'SF Mono','Consolas',monospace;"
            );
            dirLabel.setWrapText(true);

            Button dirBtn = glassBtn("📁  选择输出目录", false);
            dirBtn.setOnAction(e -> {
                DirectoryChooser dc = new DirectoryChooser();
                dc.setTitle("选择输出目录");
                if (config.sourceFile != null)
                    dc.setInitialDirectory(config.sourceFile.getParent().toFile());
                File dir = dc.showDialog(getScene() != null ? getScene().getWindow() : null);
                if (dir != null) {
                    config.outputDir = dir.toPath();
                    dirLabel.setText(dir.getAbsolutePath());
                    dirLabel.setStyle(
                        "-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 12px;" +
                        "-fx-font-family: 'SF Mono','Consolas',monospace;"
                    );
                }
            });

            getChildren().addAll(configTitle, summaryCard, sep, outputTitle, dirBtn, dirLabel);
        }

        void refresh(SplitConfig cfg) {
            summaryContent.getChildren().clear();
            if (cfg.analysisResult == null) return;

            addRow("来源文件", cfg.sourceFile != null ? cfg.sourceFile.getFileName().toString() : "—");
            addRow("文件总 Sheet 数", String.valueOf(cfg.analysisResult.size()));

            switch (cfg.mode) {
                case BY_SHEET -> {
                    List<String> sel = cfg.selectedSheets != null ? cfg.selectedSheets : List.of();
                    addRow("拆分模式", "按 Sheet 拆分");
                    addRow("待导出 Sheet 数", String.valueOf(sel.size()));
                    addRow("预计输出文件数", String.valueOf(sel.size()));
                    if (!sel.isEmpty()) {
                        addRow("导出 Sheet", String.join("、", sel));
                    }
                }
                case BY_COLUMN -> {
                    Map<Integer, String> headers = cfg.analysisResult.get(cfg.splitSheet);
                    int totalCols = headers != null ? headers.size() : 0;
                    // Find 1-based column position
                    int colPos = cfg.splitColumnIndex + 1;
                    addRow("拆分模式", "按列值拆分");
                    addRow("目标 Sheet", cfg.splitSheet != null ? cfg.splitSheet : "—");
                    addRow("拆分列", (cfg.splitColumn != null ? cfg.splitColumn : "—")
                        + "（第 " + colPos + " 列，共 " + totalCols + " 列）");
                }
                case COMPLEX -> {
                    addRow("拆分模式", "复杂拆分");
                    if (cfg.complexTaskId != null) {
                        List<ComplexSplitConfigEntity> rows = List.of();
                        try (SqlSession session = DatabaseInit.getSqlSession()) {
                            rows = session.getMapper(ComplexSplitConfigMapper.class)
                                         .selectAllByTaskId(cfg.complexTaskId);
                        } catch (Exception ignored) {}
                        addRow("配置条数", String.valueOf(rows.size()));
                        for (ComplexSplitConfigEntity r : rows) {
                            boolean isCopyAll = Integer.valueOf(-1).equals(r.getHeaderIndex())
                                             && Integer.valueOf(-1).equals(r.getColumnIndex());
                            String detail = isCopyAll
                                ? "整Sheet复制"
                                : "表头行 " + r.getHeaderIndex() + "，拆分列 " + r.getColumnIndex();
                            addDetailRow("• " + r.getSheetName(), detail);
                        }
                    }
                }
            }
        }

        private void addRow(String key, String value) {
            Label keyL = new Label(key + "：");
            keyL.setStyle("-fx-text-fill: rgba(255,255,255,0.45); -fx-font-size: 12px; -fx-min-width: 130;");
            Label valL = new Label(value);
            valL.setStyle("-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 12px;");
            valL.setWrapText(true);
            HBox row = new HBox(4, keyL, valL);
            row.setAlignment(Pos.TOP_LEFT);
            summaryContent.getChildren().add(row);
        }

        private void addDetailRow(String key, String value) {
            Label keyL = new Label(key);
            keyL.setStyle("-fx-text-fill: rgba(255,255,255,0.60); -fx-font-size: 12px; -fx-min-width: 130;" +
                          "-fx-font-family: 'SF Mono','Consolas',monospace;");
            Label valL = new Label(value);
            valL.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 11px;");
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
    // Step 4: 执行拆分 + 展示结果
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

            Label title = sectionTitle("执行拆分");

            progressBar = new ProgressBar(0);
            progressBar.setMaxWidth(Double.MAX_VALUE);

            progressLabel = new Label("准备中...");
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
            task.setOnFailed(e   -> showError(task.getException()));

            new Thread(task) {{ setDaemon(true); }}.start();
        }

        private void showSuccess(ExcelSplitter.SplitResult result) {
            progressBar.setProgress(1.0);
            progressBar.getStyleClass().removeAll("success", "danger");
            progressBar.getStyleClass().add("success");
            progressLabel.setText("✓ 拆分完成，输出 " + result.fileCount() + " 个文件");
            progressLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");

            resultBox.getChildren().add(subLabel("输出文件列表"));

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
                name.setStyle(
                    "-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 12px;" +
                    "-fx-font-family: 'SF Mono','Consolas',monospace;"
                );
                HBox.setHgrow(name, Priority.ALWAYS);

                Button openBtn = new Button("打开文件夹");
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
            progressBar.setProgress(1.0);
            progressBar.getStyleClass().removeAll("success", "danger");
            progressBar.getStyleClass().add("danger");
            progressLabel.setText("❌ 拆分失败");
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
        l.setStyle("-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 15px; -fx-font-weight: 500;");
        return l;
    }

    static Label subLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.30); -fx-font-size: 10px;" +
            "-fx-font-weight: bold;"
        );
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
               "-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 13px;" +
               "-fx-padding: 9 12 9 12;";
    }

    private static String comboStyle() {
        return "-fx-background-color: rgba(255,255,255,0.05);" +
               "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
               "-fx-border-radius: 8; -fx-background-radius: 8;" +
               "-fx-text-fill: rgba(255,255,255,0.88);";
    }
}
