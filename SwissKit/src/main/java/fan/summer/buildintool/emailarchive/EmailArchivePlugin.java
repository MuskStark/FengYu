package fan.summer.buildintool.emailarchive;

import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import fan.summer.api.ai.AiTool;
import fan.summer.api.component.StepWizard;
import fan.summer.api.i18n.I18n;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.buildintool.ai.EmailArchiveFetchTool;
import fan.summer.buildintool.ai.EmailArchiveQueryTool;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.setting.email.SwissKitSettingEmailEntity;
import fan.summer.database.mapper.setting.email.SwissKitSettingEmailMapper;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import org.apache.ibatis.session.SqlSession;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class EmailArchivePlugin implements SwissKitJPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger(EmailArchivePlugin.class);

    private Node view;
    private final EmailArchiveConfig config = new EmailArchiveConfig();
    private static final AtomicBoolean hasRunningTask = new AtomicBoolean(false);

    @Override public String getId()          { return "fan.summer.buildin.email-archive"; }
    @Override public String getName()        { return I18n.get("builtin.email-archive.name"); }
    @Override public String getDescription() { return I18n.get("builtin.email-archive.desc"); }
    @Override public ToolCategory getCategory()    { return ToolCategory.NET; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()     { return "email-check"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.TEAL; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    @Override public boolean hasRunningTasks() { return hasRunningTask.get(); }

    @Override public void onActivate()   { log.info("Email Archive activated"); }
    @Override public void onDeactivate() { log.info("Email Archive deactivated"); }

    @Override
    public Node createView() {
        if (view != null) return view;
        view = buildWizardView();
        return view;
    }

    public EmailArchiveConfig getConfig() { return config; }

    private Node buildWizardView() {
        StepWizard wizard = new StepWizard();

        Step1View step1 = new Step1View(config);
        Step2View step2 = new Step2View(config);
        Step3View step3 = new Step3View(config);

        wizard.addStep(I18n.get("builtin.email-archive.step.selectAccount"), step1, step1.canProceedSupplier());
        wizard.addStep(I18n.get("builtin.email-archive.step.archiveConfig"), step2, step2.canProceedSupplier());
        wizard.addStep(I18n.get("builtin.email-archive.step.execute"), step3, () -> true);

        wizard.build();

        wizard.setOnStepChanged((from, to, total) -> {
            if (from == 1 && to == 2) step3.startArchive();
        });

        VBox root = new VBox(wizard);
        VBox.setVgrow(wizard, Priority.ALWAYS);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: transparent;");
        return root;
    }

    // ════════════════════════════════════════════════════
    // Step 1: Select Account
    // ════════════════════════════════════════════════════

    static class Step1View extends VBox {
        private final EmailArchiveConfig config;
        private final ComboBox<String> accountCombo;
        private final Label warningLabel;

        Step1View(EmailArchiveConfig config) {
            this.config = config;
            setSpacing(16);
            setStyle("-fx-background-color: transparent;");

            Label title = sectionTitle(I18n.get("builtin.email-archive.selectAccountPrompt"));

            accountCombo = new ComboBox<>();
            accountCombo.setMaxWidth(Double.MAX_VALUE);
            accountCombo.setPromptText(I18n.get("builtin.email-archive.selectAccountPrompt"));
            accountCombo.setStyle(comboStyle());

            loadAccounts();

            warningLabel = new Label();
            warningLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
            warningLabel.setWrapText(true);

            accountCombo.valueProperty().addListener((o, ov, nv) -> {
                config.setAccountEmail(nv);
                validateImapConfig(nv);
            });

            getChildren().addAll(title, accountCombo, warningLabel);
        }

        private void loadAccounts() {
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                SwissKitSettingEmailMapper mapper =
                        session.getMapper(SwissKitSettingEmailMapper.class);
                SwissKitSettingEmailEntity entity = mapper.selectLatest();
                if (entity != null && entity.getEmail() != null) {
                    accountCombo.getItems().add(entity.getEmail());
                    accountCombo.setValue(entity.getEmail());
                    config.setAccountEmail(entity.getEmail());
                    validateImapConfig(entity.getEmail());
                }
            } catch (Exception e) {
                log.error("Failed to load accounts: {}", e.getMessage());
            }
        }

        private void validateImapConfig(String email) {
            if (email == null) {
                warningLabel.setText("");
                return;
            }
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                SwissKitSettingEmailEntity entity =
                        session.getMapper(SwissKitSettingEmailMapper.class).selectLatest();
                if (entity == null || !email.equals(entity.getEmail())) {
                    warningLabel.setText(I18n.get("builtin.email-archive.noAccounts"));
                } else if (entity.getImapAddress() == null || entity.getImapAddress().isBlank()) {
                    warningLabel.setText(I18n.get("builtin.email-archive.noImapConfig"));
                } else {
                    warningLabel.setText("");
                }
            } catch (Exception e) {
                warningLabel.setText(I18n.get("builtin.email-archive.noAccounts"));
            }
        }

        java.util.function.BooleanSupplier canProceedSupplier() {
            return () -> {
                if (config.getAccountEmail() == null || config.getAccountEmail().isBlank())
                    return false;
                return warningLabel.getText() == null || warningLabel.getText().isBlank();
            };
        }
    }

    // ════════════════════════════════════════════════════
    // Step 2: Archive Config
    // ════════════════════════════════════════════════════

    static class Step2View extends VBox {
        private final EmailArchiveConfig config;
        private final Spinner<Integer> daysSpinner;
        private final ComboBox<String> folderCombo;
        private final Label dirLabel;

        Step2View(EmailArchiveConfig config) {
            this.config = config;
            setSpacing(16);
            setStyle("-fx-background-color: transparent;");

            Label title = sectionTitle(I18n.get("builtin.email-archive.step.archiveConfig"));

            Label daysLabel = subLabel(I18n.get("builtin.email-archive.days"));
            daysSpinner = new Spinner<>(1, 3650, 30);
            daysSpinner.setMaxWidth(Double.MAX_VALUE);
            daysSpinner.setStyle(comboStyle());
            daysSpinner.valueProperty().addListener((o, ov, nv) -> {
                if (nv != null) config.setDays(nv);
            });

            Label folderLabel = subLabel(I18n.get("builtin.email-archive.folder"));
            folderCombo = new ComboBox<>();
            folderCombo.getItems().addAll("INBOX", "Sent", "Drafts", "Trash", "[Gmail]/All Mail");
            folderCombo.setMaxWidth(Double.MAX_VALUE);
            folderCombo.setValue("INBOX");
            folderCombo.setStyle(comboStyle());
            folderCombo.setEditable(true);
            folderCombo.valueProperty().addListener((o, ov, nv) -> {
                if (nv != null) config.setImapFolder(nv);
            });

            Label outputLabel = subLabel(I18n.get("builtin.email-archive.outputDir"));
            dirLabel = new Label(I18n.get("builtin.email-archive.defaultOutputHint"));
            dirLabel.setStyle(
                "-fx-text-fill: rgba(255,255,255,0.40); -fx-font-size: 12px;" +
                "-fx-font-family: 'SF Mono','Consolas',monospace;"
            );
            dirLabel.setWrapText(true);

            Button dirBtn = glassBtn(I18n.get("builtin.email-archive.chooseOutputDir"), false);
            dirBtn.setOnAction(e -> {
                DirectoryChooser dc = new DirectoryChooser();
                dc.setTitle(I18n.get("builtin.email-archive.chooseOutputDir"));
                File dir = dc.showDialog(getScene() != null ? getScene().getWindow() : null);
                if (dir != null) {
                    config.setOutputDir(dir.toPath());
                    dirLabel.setText(dir.getAbsolutePath());
                    dirLabel.setStyle(
                        "-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 12px;" +
                        "-fx-font-family: 'SF Mono','Consolas',monospace;"
                    );
                }
            });

            getChildren().addAll(title, daysLabel, daysSpinner, folderLabel, folderCombo, outputLabel, dirBtn, dirLabel);
        }

        java.util.function.BooleanSupplier canProceedSupplier() {
            return () -> config.getImapFolder() != null && !config.getImapFolder().isBlank();
        }
    }

    // ════════════════════════════════════════════════════
    // Step 3: Execute Archive
    // ════════════════════════════════════════════════════

    static class Step3View extends VBox {
        private final EmailArchiveConfig config;
        private final ProgressBar progressBar;
        private final Label progressLabel;
        private final VBox resultBox;
        private boolean started = false;

        Step3View(EmailArchiveConfig config) {
            this.config = config;
            setSpacing(16);
            setStyle("-fx-background-color: transparent;");

            Label title = sectionTitle(I18n.get("builtin.email-archive.step.execute"));

            progressBar = new ProgressBar(0);
            progressBar.setMaxWidth(Double.MAX_VALUE);

            progressLabel = new Label(I18n.get("builtin.email-archive.archiving"));
            progressLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

            resultBox = new VBox(8);
            resultBox.setStyle("-fx-background-color: transparent;");

            getChildren().addAll(title, progressBar, progressLabel, resultBox);
        }

        void startArchive() {
            if (started) return;
            started = true;
            resultBox.getChildren().clear();

            Task<EmailArchiveService.ArchiveResult> task = new Task<>() {
                @Override
                protected EmailArchiveService.ArchiveResult call() {
                    EmailArchiveService service = new EmailArchiveService();
                    return service.archive(config, (pct, msg) ->
                        Platform.runLater(() -> {
                            progressBar.setProgress(pct);
                            progressLabel.setText(msg);
                        })
                    );
                }
            };

            task.setOnSucceeded(e -> showSuccess(task.getValue()));
            task.setOnFailed(e -> showError(task.getException()));

            hasRunningTask.set(true);
            new Thread(task) {{ setDaemon(true); }}.start();
        }

        private void showSuccess(EmailArchiveService.ArchiveResult result) {
            hasRunningTask.set(false);
            progressBar.setProgress(1.0);
            progressBar.getStyleClass().removeAll("success", "danger");
            progressBar.getStyleClass().add("success");

            if (result.errorMessage != null) {
                showError(result);
                return;
            }

            progressLabel.setText(I18n.get("builtin.email-archive.complete",
                    result.newArchived, result.skippedDuplicates));
            progressLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");

            log.info("Email archive complete: {} archived, {} skipped, {} errors",
                    result.newArchived, result.skippedDuplicates, result.errors);

            if (result.errors > 0) {
                Label errInfo = new Label(I18n.get("builtin.email-archive.failed",
                        result.errors + " messages had errors"));
                errInfo.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");
                resultBox.getChildren().add(errInfo);
            }

            Path outputDir = config.getOutputDir() != null
                    ? config.getOutputDir() : Paths.get(".swisskit", "email-archive");

            Button openBtn = glassBtn(I18n.get("builtin.email-archive.openFolder"), false);
            openBtn.setOnAction(e -> {
                try {
                    java.awt.Desktop.getDesktop().open(outputDir.toFile());
                } catch (Exception ex) { /* skip */ }
            });

            resultBox.getChildren().add(openBtn);
        }

        private void showError(EmailArchiveService.ArchiveResult result) {
            hasRunningTask.set(false);
            progressBar.setProgress(1.0);
            progressBar.getStyleClass().removeAll("success", "danger");
            progressBar.getStyleClass().add("danger");
            progressLabel.setText(I18n.get("builtin.email-archive.failed",
                    result.errorMessage != null ? result.errorMessage : "Unknown error"));
            progressLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
        }

        private void showError(Throwable err) {
            hasRunningTask.set(false);
            progressBar.setProgress(1.0);
            progressBar.getStyleClass().removeAll("success", "danger");
            progressBar.getStyleClass().add("danger");
            progressLabel.setText(I18n.get("builtin.email-archive.failed",
                    err.getMessage() != null ? err.getMessage() : err.toString()));
            progressLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
        }
    }

    // ════════════════════════════════════════════════════
    // Shared UI helpers
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
                "-fx-background-color: #3574F0; -fx-text-fill: white; -fx-font-size: 13px;" +
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

    private static String comboStyle() {
        return "-fx-background-color: rgba(255,255,255,0.05);" +
               "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
               "-fx-border-radius: 8; -fx-background-radius: 8;" +
               "-fx-text-fill: rgba(255,255,255,0.88);";
    }

    @Override
    public List<AiTool> aiTools() {
        return List.of(
            new EmailArchiveFetchTool(this),
            new EmailArchiveQueryTool()
        );
    }
}
