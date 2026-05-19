package fan.summer.buildintool.email;

import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import fan.summer.api.theme.Themes;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.email.EmailMassSentConfigEntity;
import fan.summer.database.entity.email.EmailSentLogEntity;
import fan.summer.database.entity.setting.email.EmailTagEntity;
import fan.summer.database.mapper.email.EmailMassSentConfigMapper;
import fan.summer.database.mapper.email.EmailSentLogMapper;
import fan.summer.database.mapper.setting.email.EmailTagMapper;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Email built-in tool. Supports composing HTML email body, single-recipient send,
 * mass-by-tag send (with attachment folder + tag-based filename routing), and viewing
 * the persisted send log.
 */
public class EmailPlugin implements SwissKitJPlugin {

    private static final Logger log = LoggerFactory.getLogger(EmailPlugin.class);

    private Node view;
    private String massTaskId;

    @Override public String getId()          { return "builtin.email"; }
    @Override public String getName()        { return "邮件发送"; }
    @Override public String getDescription() { return "支持单发与按标签群发，含附件路由与发送日志"; }
    @Override public ToolCategory getCategory()    { return ToolCategory.NET; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()     { return "email"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.BLUE; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    @Override
    public Node createView() {
        if (view != null) return view;
        view = buildView();
        return view;
    }

    private Node buildView() {
        Label title = sectionTitle("撰写邮件");

        // Subject
        TextField subjectField = new TextField();
        subjectField.setPromptText("邮件主题");
        subjectField.setStyle(fieldStyle());

        // Recipients (single mode)
        TextField toField = new TextField();
        toField.setPromptText("收件人，多个用逗号或分号分隔");
        toField.setStyle(fieldStyle());

        TextField ccField = new TextField();
        ccField.setPromptText("抄送（可选）");
        ccField.setStyle(fieldStyle());

        // Body — rich text HTML editor (WebView + contenteditable + formatting toolbar)
        RichTextEditor bodyEditor = new RichTextEditor();
        VBox.setVgrow(bodyEditor, Priority.ALWAYS);

        // Mass send controls
        CheckBox massCheckBox = new CheckBox("群发模式");
        massCheckBox.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 13px;");

        Button configBtn = glassBtn("群发配置", false);
        configBtn.setDisable(true);

        Button viewConfigBtn = glassBtn("查看配置", false);
        viewConfigBtn.setDisable(true);

        massCheckBox.selectedProperty().addListener((obs, old, sel) -> {
            if (sel) {
                massTaskId = "MassTask-" + UUID.randomUUID();
                log.debug("Mass mode enabled, taskId={}", massTaskId);
                configBtn.setDisable(false);
                viewConfigBtn.setDisable(false);
                toField.setDisable(true);
                ccField.setDisable(true);
            } else {
                massTaskId = null;
                configBtn.setDisable(true);
                viewConfigBtn.setDisable(true);
                toField.setDisable(false);
                ccField.setDisable(false);
            }
        });

        configBtn.setOnAction(e -> {
            if (massTaskId != null) openMassConfigDialog(massTaskId);
        });
        viewConfigBtn.setOnAction(e -> {
            if (massTaskId != null) showCurrentConfigSummary(massTaskId);
        });

        HBox massRow = new HBox(12, massCheckBox, configBtn, viewConfigBtn);
        massRow.setAlignment(Pos.CENTER_LEFT);

        // Send / log buttons + progress
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        Label progressLabel = new Label("");
        progressLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

        Button sendBtn = glassBtn("发送", true);
        Button viewLogBtn = glassBtn("查看发送日志", false);

        sendBtn.setOnAction(e -> handleSend(
                subjectField, toField, ccField, bodyEditor,
                massCheckBox.isSelected(), progressBar, progressLabel, sendBtn
        ));
        viewLogBtn.setOnAction(e -> openSentLogDialog());

        HBox actionRow = new HBox(8, sendBtn, viewLogBtn);

        // Header rows
        VBox headerBox = new VBox(8,
                labeled("主题", subjectField),
                labeled("收件人", toField),
                labeled("抄送", ccField)
        );

        VBox bodyBox = new VBox(6, subLabel("正文"), bodyEditor);
        VBox.setVgrow(bodyBox, Priority.ALWAYS);
        VBox.setVgrow(bodyEditor, Priority.ALWAYS);

        VBox root = new VBox(14,
                title,
                headerBox,
                bodyBox,
                massRow,
                actionRow,
                progressBar,
                progressLabel
        );
        VBox.setVgrow(bodyBox, Priority.ALWAYS);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: transparent;");
        return root;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Send action
    // ═══════════════════════════════════════════════════════════════════

    private void handleSend(TextField subjectField, TextField toField, TextField ccField,
                            RichTextEditor bodyEditor, boolean massMode,
                            ProgressBar progressBar, Label progressLabel, Button sendBtn) {
        String subject = subjectField.getText();
        String body = bodyEditor.getHtml();
        String plain = bodyEditor.getPlainText();
        if (subject == null || subject.isBlank()) {
            alert(Alert.AlertType.WARNING, "提示", "请填写主题");
            return;
        }
        if (plain == null || plain.isBlank()) {
            alert(Alert.AlertType.WARNING, "提示", "请填写正文");
            return;
        }

        sendBtn.setDisable(true);
        progressBar.setProgress(0);
        progressBar.getStyleClass().removeAll("success", "danger");
        progressLabel.setText("准备发送...");
        progressLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

        Task<EmailSendService.Result> task = new Task<>() {
            @Override
            protected EmailSendService.Result call() {
                EmailSendService service = new EmailSendService();
                if (massMode) {
                    if (massTaskId == null) {
                        EmailSendService.Result r = new EmailSendService.Result();
                        r.errorMessage = "未配置群发任务";
                        return r;
                    }
                    return service.sendMass(subject, body, massTaskId,
                            (pct, msg) -> Platform.runLater(() -> {
                                progressBar.setProgress(pct);
                                progressLabel.setText(msg);
                            }));
                } else {
                    List<String> toList = splitAddresses(toField.getText());
                    if (toList.isEmpty()) {
                        EmailSendService.Result r = new EmailSendService.Result();
                        r.errorMessage = "请填写收件人";
                        return r;
                    }
                    List<String> ccList = splitAddresses(ccField.getText());
                    Platform.runLater(() -> {
                        progressBar.setProgress(-1);
                        progressLabel.setText("发送中...");
                    });
                    return service.sendSingle(subject, body, toList, ccList, null, null);
                }
            }
        };

        task.setOnSucceeded(e -> {
            sendBtn.setDisable(false);
            EmailSendService.Result r = task.getValue();
            progressBar.setProgress(1.0);
            progressBar.getStyleClass().removeAll("success", "danger");
            if (r.errorMessage != null) {
                progressBar.getStyleClass().add("danger");
                progressLabel.setText("❌ " + r.errorMessage);
                progressLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
            } else {
                progressBar.getStyleClass().add("success");
                progressLabel.setText("✓ 完成：成功 " + r.successCount + "，失败 " + r.failCount);
                progressLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
            }
        });
        task.setOnFailed(e -> {
            sendBtn.setDisable(false);
            progressBar.setProgress(0);
            progressBar.getStyleClass().removeAll("success", "danger");
            progressBar.getStyleClass().add("danger");
            Throwable ex = task.getException();
            progressLabel.setText("❌ 发送任务异常：" + (ex != null ? ex.getMessage() : "unknown"));
            progressLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
            log.error("Send task failed", ex);
        });

        new Thread(task) {{ setDaemon(true); }}.start();
    }

    private List<String> splitAddresses(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[,;\\s]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Mass config dialog
    // ═══════════════════════════════════════════════════════════════════

    private void openMassConfigDialog(String taskId) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("群发配置");

        List<EmailTagEntity> tags;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            tags = session.getMapper(EmailTagMapper.class).selectAll();
            if (tags == null) tags = new ArrayList<>();
        } catch (Exception e) {
            alert(Alert.AlertType.ERROR, "错误", "加载标签失败：" + e.getMessage());
            return;
        }

        ComboBox<EmailTagEntity> toCombo = tagComboBox(tags, "选择收件人标签");
        ComboBox<EmailTagEntity> ccCombo = tagComboBox(tags, "选择抄送标签（可选）");

        CheckBox attCheckBox = new CheckBox("根据标签附带附件");
        attCheckBox.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 13px;");

        TextField attFolderField = new TextField();
        attFolderField.setPromptText("选择附件文件夹");
        attFolderField.setEditable(false);
        attFolderField.setStyle(fieldStyle());
        HBox.setHgrow(attFolderField, Priority.ALWAYS);

        Button chooseFolderBtn = glassBtn("选择文件夹", false);
        chooseFolderBtn.setDisable(true);
        chooseFolderBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("选择附件文件夹");
            File dir = dc.showDialog(dialog);
            if (dir != null) attFolderField.setText(dir.getAbsolutePath());
        });

        attCheckBox.selectedProperty().addListener((obs, o, n) -> {
            chooseFolderBtn.setDisable(!n);
            if (!n) attFolderField.setText("");
        });

        // Pre-fill from existing config
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            EmailMassSentConfigEntity existing =
                    session.getMapper(EmailMassSentConfigMapper.class).selectByTaskId(taskId);
            if (existing != null) {
                selectTagById(toCombo, existing.getToTag());
                selectTagById(ccCombo, existing.getCcTag());
                attCheckBox.setSelected(existing.isSentAtt());
                if (existing.getAttFolderPath() != null) {
                    attFolderField.setText(existing.getAttFolderPath());
                }
            }
        } catch (Exception ignored) {}

        Button saveBtn = glassBtn("保存", true);
        Button cancelBtn = glassBtn("取消", false);

        saveBtn.setOnAction(e -> {
            EmailTagEntity to = toCombo.getValue();
            if (to == null) {
                alert(Alert.AlertType.WARNING, "提示", "请选择收件人标签");
                return;
            }
            if (attCheckBox.isSelected() && (attFolderField.getText() == null || attFolderField.getText().isBlank())) {
                alert(Alert.AlertType.WARNING, "提示", "请选择附件文件夹");
                return;
            }
            EmailMassSentConfigEntity cfg = new EmailMassSentConfigEntity();
            cfg.setTaskId(taskId);
            cfg.setToTag(String.valueOf(to.getId()));
            EmailTagEntity cc = ccCombo.getValue();
            cfg.setCcTag(cc != null ? String.valueOf(cc.getId()) : null);
            cfg.setSentAtt(attCheckBox.isSelected());
            cfg.setAttFolderPath(attCheckBox.isSelected() ? attFolderField.getText() : null);

            try (SqlSession session = DatabaseInit.getSqlSession()) {
                session.getMapper(EmailMassSentConfigMapper.class).upsert(cfg);
                session.commit();
                dialog.close();
                alert(Alert.AlertType.INFORMATION, "成功", "配置已保存");
            } catch (Exception ex) {
                log.error("Save config failed", ex);
                alert(Alert.AlertType.ERROR, "错误", "保存失败：" + ex.getMessage());
            }
        });
        cancelBtn.setOnAction(e -> dialog.close());

        HBox attRow = new HBox(8, attFolderField, chooseFolderBtn);

        HBox buttons = new HBox(8, spacer(), saveBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12,
                sectionTitle("群发配置"),
                labeled("收件标签", toCombo),
                labeled("抄送标签", ccCombo),
                attCheckBox,
                attRow,
                buttons
        );
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #1f2937;");
        root.setPrefWidth(520);

        Scene scene = new Scene(root);
        Themes.applyTo(scene);
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private ComboBox<EmailTagEntity> tagComboBox(List<EmailTagEntity> tags, String prompt) {
        ComboBox<EmailTagEntity> cb = new ComboBox<>(FXCollections.observableArrayList(tags));
        cb.setMaxWidth(Double.MAX_VALUE);
        cb.setPromptText(prompt);
        cb.setStyle(comboStyle());
        cb.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(EmailTagEntity item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getTag());
            }
        });
        cb.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(EmailTagEntity item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getTag());
            }
        });
        return cb;
    }

    private void selectTagById(ComboBox<EmailTagEntity> combo, String idStr) {
        if (idStr == null) return;
        try {
            long id = Long.parseLong(idStr);
            for (EmailTagEntity t : combo.getItems()) {
                if (t.getId() != null && t.getId() == id) {
                    combo.setValue(t);
                    return;
                }
            }
        } catch (NumberFormatException ignored) {}
    }

    private void showCurrentConfigSummary(String taskId) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            EmailMassSentConfigEntity cfg =
                    session.getMapper(EmailMassSentConfigMapper.class).selectByTaskId(taskId);
            if (cfg == null) {
                alert(Alert.AlertType.INFORMATION, "提示", "当前任务暂无配置");
                return;
            }
            List<EmailTagEntity> tags = session.getMapper(EmailTagMapper.class).selectAll();
            String toName = resolveTagName(tags, cfg.getToTag());
            String ccName = resolveTagName(tags, cfg.getCcTag());
            String text = "Task ID：" + cfg.getTaskId() + "\n" +
                    "收件标签：" + (toName != null ? toName : "—") + "\n" +
                    "抄送标签：" + (ccName != null ? ccName : "—") + "\n" +
                    "附件发送：" + (cfg.isSentAtt() ? "是" : "否") + "\n" +
                    "附件目录：" + (cfg.getAttFolderPath() != null ? cfg.getAttFolderPath() : "—");
            alert(Alert.AlertType.INFORMATION, "群发配置", text);
        } catch (Exception e) {
            alert(Alert.AlertType.ERROR, "错误", "加载配置失败：" + e.getMessage());
        }
    }

    private String resolveTagName(List<EmailTagEntity> tags, String idStr) {
        if (idStr == null || tags == null) return null;
        try {
            long id = Long.parseLong(idStr);
            return tags.stream()
                    .filter(t -> t.getId() != null && t.getId() == id)
                    .findFirst()
                    .map(EmailTagEntity::getTag)
                    .orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Sent log dialog
    // ═══════════════════════════════════════════════════════════════════

    private void openSentLogDialog() {
        List<EmailSentLogEntity> logs;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            logs = session.getMapper(EmailSentLogMapper.class).selectAll();
        } catch (Exception e) {
            alert(Alert.AlertType.ERROR, "错误", "加载日志失败：" + e.getMessage());
            return;
        }
        if (logs == null || logs.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "提示", "暂无发送日志");
            return;
        }

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("发送日志");

        TableView<EmailSentLogEntity> table = new TableView<>(FXCollections.observableArrayList(logs));
        table.setStyle("-fx-background-color: transparent;");
        table.setPlaceholder(new Label("无数据"));

        table.getColumns().add(column("ID", "id", 60));
        table.getColumns().add(column("主题", "subject", 160));
        table.getColumns().add(column("收件人", "to", 200));
        table.getColumns().add(column("抄送", "cc", 160));
        table.getColumns().add(column("附件", "attachment", 200));
        table.getColumns().add(column("发送时间", "sendTime", 160));
        TableColumn<EmailSentLogEntity, Boolean> successCol = new TableColumn<>("成功");
        successCol.setCellValueFactory(new PropertyValueFactory<>("success"));
        successCol.setPrefWidth(60);
        table.getColumns().add(successCol);

        Button closeBtn = glassBtn("关闭", false);
        closeBtn.setOnAction(e -> dialog.close());

        HBox footer = new HBox(spacer(), closeBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, sectionTitle("发送日志"), table, footer);
        VBox.setVgrow(table, Priority.ALWAYS);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #1f2937;");
        root.setPrefSize(960, 520);

        Scene scene = new Scene(root);
        Themes.applyTo(scene);
        dialog.setScene(scene);
        dialog.show();
    }

    private <T> TableColumn<EmailSentLogEntity, T> column(String header, String property, double width) {
        TableColumn<EmailSentLogEntity, T> col = new TableColumn<>(header);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        col.setPrefWidth(width);
        return col;
    }

    // ═══════════════════════════════════════════════════════════════════
    // UI helpers
    // ═══════════════════════════════════════════════════════════════════

    private static VBox labeled(String label, Node node) {
        VBox box = new VBox(4, subLabel(label), node);
        box.setStyle("-fx-background-color: transparent;");
        return box;
    }

    private static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 15px; -fx-font-weight: 500;");
        return l;
    }

    private static Label subLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: rgba(255,255,255,0.50); -fx-font-size: 11px; -fx-font-weight: bold;");
        return l;
    }

    private static Button glassBtn(String text, boolean primary) {
        Button btn = new Button(text);
        if (primary) {
            btn.setStyle(
                    "-fx-background-color: #5b8cf7; -fx-text-fill: white; -fx-font-size: 13px;" +
                    "-fx-font-weight: 500; -fx-background-radius: 8; -fx-border-width: 0;" +
                    "-fx-padding: 9 18 9 18; -fx-cursor: hand;"
            );
        } else {
            btn.setStyle(
                    "-fx-background-color: rgba(255,255,255,0.07);" +
                    "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
                    "-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 13px;" +
                    "-fx-background-radius: 8; -fx-border-radius: 8;" +
                    "-fx-padding: 9 18 9 18; -fx-cursor: hand;"
            );
        }
        return btn;
    }

    private static String fieldStyle() {
        return "-fx-background-color: rgba(255,255,255,0.05);" +
                "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
                "-fx-border-radius: 8; -fx-background-radius: 8;" +
                "-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 13px;" +
                "-fx-padding: 8 12 8 12;";
    }

    private static String comboStyle() {
        return "-fx-background-color: rgba(255,255,255,0.05);" +
                "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
                "-fx-border-radius: 8; -fx-background-radius: 8;" +
                "-fx-text-fill: rgba(255,255,255,0.88);";
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static void alert(Alert.AlertType type, String title, String message) {
        Alert a = new Alert(type);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(message);
        a.showAndWait();
    }
}
