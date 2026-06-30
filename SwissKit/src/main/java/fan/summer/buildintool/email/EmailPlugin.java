package fan.summer.buildintool.email;

import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import fan.summer.api.i18n.I18n;
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
import fan.summer.api.component.GlassNotification;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
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
 * Built-in email composition and sending tool.
 *
 * <p>Supports three modes of operation:
 * <ul>
 *   <li><b>Single send</b> — directly send to one or more recipients with optional CC/BCC
 *       and a file picker for attachments.</li>
 *   <li><b>Mass send by tag</b> — route a single email + attachment set to all contacts
 *       whose address-book record carries one of the selected tags; to-tag and cc-tag
 *       selectors accept multiple tags for fine-grained recipient filtering.</li>
 *   <li><b>Mass send by filename</b> — parse attachment filenames to extract a tag suffix
 *       (text between the last underscore and the extension dot); match each suffix against
 *       address-book tag names and send one email per matched group.</li>
 * </ul>
 *
 * <p>The rich text body is composed using {@link RichTextEditor}, a WebView-backed
 * contenteditable editor with a formatting toolbar. The resulting HTML is sent as the
 * email body.
 *
 * <p>All sent emails are logged to the H2 database via {@link EmailSentLogMapper},
 * and mass configurations are persisted via {@link EmailMassSentConfigMapper}.
 *
 * @since 1.0.0
 * @see SwissKitJPlugin
 * @see RichTextEditor
 * @see EmailSendService
 */
public class EmailPlugin implements SwissKitJPlugin {

    private static final Logger log = LoggerFactory.getLogger(EmailPlugin.class);

    private Node view;
    private String massTaskId;

    @Override public String getId()          { return "builtin.email"; }
    @Override public String getName()        { return I18n.get("builtin.email.name"); }
    @Override public String getDescription() { return I18n.get("builtin.email.desc"); }
    @Override public ToolCategory getCategory()    { return ToolCategory.NET; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()     { return "email"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.BLUE; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    /**
     * Creates and returns the email composition UI.
     * The view is built lazily on first call and cached for the lifetime of the plugin.
     *
     * @return the root JavaFX node of the email tool view
     */
    @Override
    public Node createView() {
        if (view != null) return view;
        view = buildView();
        return view;
    }

    private Node buildView() {
        Label title = sectionTitle(I18n.get("builtin.email.compose"));

        // Subject
        TextField subjectField = new TextField();
        subjectField.setPromptText(I18n.get("builtin.email.subjectPrompt"));
        subjectField.setStyle(fieldStyle());

        // Recipients (single mode)
        TextField toField = new TextField();
        toField.setPromptText(I18n.get("builtin.email.toPrompt"));
        toField.setStyle(fieldStyle());

        TextField ccField = new TextField();
        ccField.setPromptText(I18n.get("builtin.email.ccPrompt"));
        ccField.setStyle(fieldStyle());

        // Body — rich text HTML editor (WebView + contenteditable + formatting toolbar)
        RichTextEditor bodyEditor = new RichTextEditor();
        VBox.setVgrow(bodyEditor, Priority.ALWAYS);

        // Mass send controls
        CheckBox massCheckBox = new CheckBox(I18n.get("builtin.email.massMode"));
        massCheckBox.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 13px;");

        Button configBtn = glassBtn(I18n.get("builtin.email.massConfig"), false);
        configBtn.setDisable(true);

        Button viewConfigBtn = glassBtn(I18n.get("builtin.email.viewConfig"), false);
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

        Button sendBtn = glassBtn(I18n.get("builtin.email.send"), true);
        Button viewLogBtn = glassBtn(I18n.get("builtin.email.viewSentLog"), false);
        Button addressBookBtn = glassBtn(I18n.get("builtin.email.addressBook"), false);

        sendBtn.setOnAction(e -> handleSend(
                subjectField, toField, ccField, bodyEditor,
                massCheckBox.isSelected(), progressBar, progressLabel, sendBtn
        ));
        viewLogBtn.setOnAction(e -> openSentLogDialog());

        HBox actionRow = new HBox(8, sendBtn, viewLogBtn, addressBookBtn);

        // Header rows
        VBox headerBox = new VBox(8,
                labeled(I18n.get("builtin.email.subject"), subjectField),
                labeled(I18n.get("builtin.email.to"), toField),
                labeled(I18n.get("builtin.email.cc"), ccField)
        );

        VBox bodyBox = new VBox(6, subLabel(I18n.get("builtin.email.body")), bodyEditor);
        VBox.setVgrow(bodyBox, Priority.ALWAYS);
        VBox.setVgrow(bodyEditor, Priority.ALWAYS);

        VBox composePane = new VBox(14,
                title,
                headerBox,
                bodyBox,
                massRow,
                actionRow,
                progressBar,
                progressLabel
        );
        VBox.setVgrow(bodyBox, Priority.ALWAYS);
        composePane.setPadding(new Insets(24));
        composePane.setStyle("-fx-background-color: transparent;");
        HBox.setHgrow(composePane, Priority.ALWAYS);

        // Address book side panel (toggleable)
        AddressBookPane addressBookPane = new AddressBookPane();
        addressBookPane.setVisible(false);
        addressBookPane.setManaged(false);

        addressBookBtn.setOnAction(e -> {
            boolean show = !addressBookPane.isVisible();
            addressBookPane.setVisible(show);
            addressBookPane.setManaged(show);
        });

        HBox root = new HBox(composePane, addressBookPane);
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
            GlassNotification.notify(view, GlassNotification.Type.WARNING, I18n.get("builtin.email.subjectRequired"));
            return;
        }
        if (plain == null || plain.isBlank()) {
            GlassNotification.notify(view, GlassNotification.Type.WARNING, I18n.get("builtin.email.bodyRequired"));
            return;
        }

        sendBtn.setDisable(true);
        progressBar.setProgress(0);
        progressBar.getStyleClass().removeAll("success", "danger");
        progressLabel.setText(I18n.get("builtin.email.preparingSend"));
        progressLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

        Task<EmailSendService.Result> task = new Task<>() {
            @Override
            protected EmailSendService.Result call() {
                EmailSendService service = new EmailSendService();
                if (massMode) {
                    if (massTaskId == null) {
                        EmailSendService.Result r = new EmailSendService.Result();
                        r.errorMessage = I18n.get("builtin.email.noMassConfig");
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
                        r.errorMessage = I18n.get("builtin.email.toRequired");
                        return r;
                    }
                    List<String> ccList = splitAddresses(ccField.getText());
                    Platform.runLater(() -> {
                        progressBar.setProgress(-1);
                        progressLabel.setText(I18n.get("builtin.email.sending"));
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
                progressLabel.setText(I18n.get("builtin.email.sendFailed", r.errorMessage));
                progressLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
            } else {
                progressBar.getStyleClass().add("success");
                progressLabel.setText(I18n.get("builtin.email.sendComplete", r.successCount, r.failCount));
                progressLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
            }
        });
        task.setOnFailed(e -> {
            sendBtn.setDisable(false);
            progressBar.setProgress(0);
            progressBar.getStyleClass().removeAll("success", "danger");
            progressBar.getStyleClass().add("danger");
            Throwable ex = task.getException();
            progressLabel.setText(I18n.get("builtin.email.sendTaskFailed", ex != null ? ex.getMessage() : "unknown"));
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
        dialog.setTitle(I18n.get("builtin.email.massConfigTitle"));

        List<EmailTagEntity> tags;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            tags = session.getMapper(EmailTagMapper.class).selectAll();
            if (tags == null) tags = new ArrayList<>();
        } catch (Exception e) {
            GlassNotification.notify(view, GlassNotification.Type.ERROR, I18n.get("builtin.email.loadTagsFailed", e.getMessage()));
            return;
        }

        // ── Filename-only mode toggle ───────────────────────────────
        CheckBox filenameModeCheckBox = new CheckBox(I18n.get("builtin.email.filenameMode"));
        filenameModeCheckBox.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 13px;");

        // ── Multi-tag checkboxes for to/cc ───────────────────────────
        VBox toCheckBoxes = new VBox(4);
        toCheckBoxes.setPadding(new Insets(6));
        toCheckBoxes.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 8;");
        for (EmailTagEntity tag : tags) {
            CheckBox cb = new CheckBox(tag.getTag());
            cb.setUserData(tag.getId());
            cb.getStyleClass().add("sk-checkbox");
            toCheckBoxes.getChildren().add(cb);
        }
        if (tags.isEmpty()) toCheckBoxes.getChildren().add(new Label(I18n.get("builtin.email.noTagsHint")));

        VBox ccCheckBoxes = new VBox(4);
        ccCheckBoxes.setPadding(new Insets(6));
        ccCheckBoxes.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 8;");
        for (EmailTagEntity tag : tags) {
            CheckBox cb = new CheckBox(tag.getTag());
            cb.setUserData(tag.getId());
            cb.getStyleClass().add("sk-checkbox");
            ccCheckBoxes.getChildren().add(cb);
        }
        if (tags.isEmpty()) ccCheckBoxes.getChildren().add(new Label(I18n.get("builtin.email.noTagsHint")));

        // ── Attachment folder ────────────────────────────────────────
        CheckBox attCheckBox = new CheckBox(I18n.get("builtin.email.attachByTag"));
        attCheckBox.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 13px;");

        TextField attFolderField = new TextField();
        attFolderField.setPromptText(I18n.get("builtin.email.attachmentFolderPrompt"));
        attFolderField.setEditable(false);
        attFolderField.setStyle(fieldStyle());
        HBox.setHgrow(attFolderField, Priority.ALWAYS);

        Button chooseFolderBtn = glassBtn(I18n.get("builtin.email.chooseAttachmentFolder"), false);
        chooseFolderBtn.setDisable(true);
        chooseFolderBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle(I18n.get("builtin.email.chooseAttachmentFolderTitle"));
            File dir = dc.showDialog(dialog);
            if (dir != null) attFolderField.setText(dir.getAbsolutePath());
        });

        attCheckBox.selectedProperty().addListener((obs, o, n) -> {
            chooseFolderBtn.setDisable(!n);
            if (!n) attFolderField.setText("");
        });

        HBox attRow = new HBox(8, attFolderField, chooseFolderBtn);

        // ── Filename mode toggles visibility of to/cc selectors ──────
        Label toLabel = new Label(I18n.get("builtin.email.toTag"));
        toLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.50); -fx-font-size: 11px; -fx-font-weight: bold;");
        Label ccLabel = new Label(I18n.get("builtin.email.ccTag"));
        ccLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.50); -fx-font-size: 11px; -fx-font-weight: bold;");

        VBox toSection = new VBox(4, toLabel, toCheckBoxes);
        VBox ccSection = new VBox(4, ccLabel, ccCheckBoxes);

        Runnable updateVisibility = () -> {
            boolean filenameMode = filenameModeCheckBox.isSelected();
            toSection.setVisible(!filenameMode);
            toSection.setManaged(!filenameMode);
            ccSection.setVisible(!filenameMode);
            ccSection.setManaged(!filenameMode);
        };
        filenameModeCheckBox.selectedProperty().addListener((obs, o, n) -> updateVisibility.run());
        updateVisibility.run();

        // ── Pre-fill from existing config ────────────────────────────
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            EmailMassSentConfigEntity existing =
                    session.getMapper(EmailMassSentConfigMapper.class).selectByTaskId(taskId);
            if (existing != null) {
                filenameModeCheckBox.setSelected(existing.isSendByFilename());
                selectCheckboxesByIds(toCheckBoxes, existing.getToTag());
                selectCheckboxesByIds(ccCheckBoxes, existing.getCcTag());
                attCheckBox.setSelected(existing.isSentAtt());
                if (existing.getAttFolderPath() != null) {
                    attFolderField.setText(existing.getAttFolderPath());
                }
            }
        } catch (Exception ignored) {}

        // ── Save / Cancel ────────────────────────────────────────────
        Button saveBtn = glassBtn(I18n.get("builtin.email.save"), true);
        Button cancelBtn = glassBtn(I18n.get("builtin.email.cancel"), false);

        saveBtn.setOnAction(e -> {
            boolean filenameMode = filenameModeCheckBox.isSelected();

            // Validate: non-filename mode needs at least one to-tag
            if (!filenameMode) {
                List<Long> toIds = collectCheckedIds(toCheckBoxes);
                if (toIds.isEmpty()) {
                    GlassNotification.notify(view, GlassNotification.Type.WARNING, I18n.get("builtin.email.selectToTagWarning"));
                    return;
                }
            }

            // Filename mode requires attachment folder
            if (filenameMode && (attFolderField.getText() == null || attFolderField.getText().isBlank())) {
                GlassNotification.notify(view, GlassNotification.Type.WARNING, I18n.get("builtin.email.selectFolderWarning"));
                return;
            }

            if (attCheckBox.isSelected() && (attFolderField.getText() == null || attFolderField.getText().isBlank())) {
                GlassNotification.notify(view, GlassNotification.Type.WARNING, I18n.get("builtin.email.selectFolderWarning"));
                return;
            }

            EmailMassSentConfigEntity cfg = new EmailMassSentConfigEntity();
            cfg.setTaskId(taskId);
            cfg.setSendByFilename(filenameMode);

            if (!filenameMode) {
                List<Long> toIds = collectCheckedIds(toCheckBoxes);
                cfg.setToTag(toIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null));
                List<Long> ccIds = collectCheckedIds(ccCheckBoxes);
                cfg.setCcTag(ccIds.isEmpty() ? null : ccIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null));
            } else {
                cfg.setToTag(null);
                cfg.setCcTag(null);
            }

            cfg.setSentAtt(attCheckBox.isSelected());
            cfg.setAttFolderPath(attCheckBox.isSelected() ? attFolderField.getText() : null);

            try (SqlSession session = DatabaseInit.getSqlSession()) {
                session.getMapper(EmailMassSentConfigMapper.class).upsert(cfg);
                session.commit();
                dialog.close();
                GlassNotification.toast(view, GlassNotification.Type.SUCCESS, I18n.get("builtin.email.configSaved"));
            } catch (Exception ex) {
                log.error("Save config failed", ex);
                GlassNotification.notify(view, GlassNotification.Type.ERROR, I18n.get("builtin.email.saveFailed", ex.getMessage()));
            }
        });
        cancelBtn.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(8, spacer(), saveBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12,
                sectionTitle(I18n.get("builtin.email.massConfigTitle")),
                filenameModeCheckBox,
                toSection,
                ccSection,
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

    /** Select checkboxes whose tag ID appears in the comma-separated idStr. */
    private void selectCheckboxesByIds(VBox checkBoxContainer, String idStr) {
        if (idStr == null) return;
        List<Long> ids = new ArrayList<>();
        for (String part : idStr.split("[,\\[\\]\"]+")) {
            try { ids.add(Long.parseLong(part.trim())); } catch (NumberFormatException ignored) {}
        }
        for (Node node : checkBoxContainer.getChildren()) {
            if (node instanceof CheckBox cb && cb.getUserData() instanceof Long id) {
                cb.setSelected(ids.contains(id));
            }
        }
    }

    /** Collect tag IDs from checked checkboxes in the container. */
    private List<Long> collectCheckedIds(VBox checkBoxContainer) {
        List<Long> ids = new ArrayList<>();
        for (Node node : checkBoxContainer.getChildren()) {
            if (node instanceof CheckBox cb && cb.isSelected() && cb.getUserData() instanceof Long id) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void showCurrentConfigSummary(String taskId) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            EmailMassSentConfigEntity cfg =
                    session.getMapper(EmailMassSentConfigMapper.class).selectByTaskId(taskId);
            if (cfg == null) {
                GlassNotification.notify(view, GlassNotification.Type.INFO, I18n.get("builtin.email.noConfig"));
                return;
            }
            List<EmailTagEntity> tags = session.getMapper(EmailTagMapper.class).selectAll();
            String toNames = resolveTagNames(tags, cfg.getToTag());
            String ccNames = resolveTagNames(tags, cfg.getCcTag());
            StringBuilder text = new StringBuilder();
            text.append("Task ID：").append(cfg.getTaskId()).append("\n");
            text.append(I18n.get("builtin.email.filenameMode")).append("：").append(cfg.isSendByFilename() ? "✓" : "✗").append("\n");
            if (!cfg.isSendByFilename()) {
                text.append(I18n.get("builtin.email.toTag")).append("：").append(toNames != null ? toNames : "—").append("\n");
                text.append(I18n.get("builtin.email.ccTag")).append("：").append(ccNames != null ? ccNames : "—").append("\n");
            }
            text.append(I18n.get("builtin.email.attachByTag")).append("：").append(cfg.isSentAtt() ? "✓" : "✗").append("\n");
            text.append(I18n.get("builtin.email.chooseAttachmentFolder")).append("：").append(cfg.getAttFolderPath() != null ? cfg.getAttFolderPath() : "—");
            GlassNotification.notify(view, GlassNotification.Type.INFO, I18n.get("builtin.email.massConfigTitle"), text.toString());
        } catch (Exception e) {
            GlassNotification.notify(view, GlassNotification.Type.ERROR, I18n.get("builtin.email.loadConfigFailed", e.getMessage()));
        }
    }

    /** Resolve comma-separated tag IDs to comma-separated tag names. */
    private String resolveTagNames(List<EmailTagEntity> tags, String idStr) {
        if (idStr == null || tags == null) return null;
        List<String> names = new ArrayList<>();
        for (String part : idStr.split("[,\\[\\]\"]+")) {
            try {
                long id = Long.parseLong(part.trim());
                tags.stream()
                        .filter(t -> t.getId() != null && t.getId() == id)
                        .findFirst()
                        .map(EmailTagEntity::getTag)
                        .ifPresent(names::add);
            } catch (NumberFormatException ignored) {}
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Sent log dialog
    // ═══════════════════════════════════════════════════════════════════

    private void openSentLogDialog() {
        List<EmailSentLogEntity> logs;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            logs = session.getMapper(EmailSentLogMapper.class).selectAll();
        } catch (Exception e) {
            GlassNotification.notify(view, GlassNotification.Type.ERROR, I18n.get("builtin.email.loadLogFailed", e.getMessage()));
            return;
        }
        if (logs == null || logs.isEmpty()) {
            GlassNotification.notify(view, GlassNotification.Type.INFO, I18n.get("builtin.email.noSentLog"));
            return;
        }

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(I18n.get("builtin.email.sentLog"));

        TableView<EmailSentLogEntity> table = new TableView<>(FXCollections.observableArrayList(logs));
        table.setStyle("-fx-background-color: transparent;");
        table.setPlaceholder(new Label(I18n.get("builtin.email.noData")));

        table.getColumns().add(column(I18n.get("builtin.email.colId"), "id", 60));
        table.getColumns().add(column(I18n.get("builtin.email.colSubject"), "subject", 160));
        table.getColumns().add(column(I18n.get("builtin.email.colTo"), "to", 200));
        table.getColumns().add(column(I18n.get("builtin.email.colCc"), "cc", 160));
        table.getColumns().add(column(I18n.get("builtin.email.colAttachment"), "attachment", 200));
        table.getColumns().add(column(I18n.get("builtin.email.colSendTime"), "sendTime", 160));
        TableColumn<EmailSentLogEntity, Boolean> successCol = new TableColumn<>(I18n.get("builtin.email.colSuccess"));
        successCol.setCellValueFactory(new PropertyValueFactory<>("success"));
        successCol.setPrefWidth(60);
        table.getColumns().add(successCol);

        Button closeBtn = glassBtn(I18n.get("builtin.email.close"), false);
        closeBtn.setOnAction(e -> dialog.close());

        HBox footer = new HBox(spacer(), closeBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, sectionTitle(I18n.get("builtin.email.sentLog")), table, footer);
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

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }
}
