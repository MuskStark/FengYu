package fan.summer.ui.setting;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.setting.email.EmailAddressBookEntity;
import fan.summer.database.entity.setting.email.EmailTagEntity;
import fan.summer.database.entity.setting.email.SwissKitSettingEmailEntity;
import fan.summer.database.mapper.AppSettingMapper;
import fan.summer.database.mapper.setting.email.EmailAddressBookMapper;
import fan.summer.database.mapper.setting.email.EmailTagMapper;
import fan.summer.database.mapper.setting.email.SwissKitSettingEmailMapper;
import fan.summer.database.mapper.plugin.PluginManagerMapper;
import fan.summer.database.entity.plugin.PluginManagerEntity;
import fan.summer.database.entity.AppSettingEntity;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Settings UI for SwissKit.
 * Displays three tabs: General (language), Email (SMTP config + address book), Plugin (JAR management).
 * Styled with glassmorphism consistent with the main application.
 */
public class SwissKitJSettingUi {

    private static final Logger log = LoggerFactory.getLogger(SwissKitJSettingUi.class);

    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("\\d+");

    // Static fields needed by inner methods
    private static TextField pluginPathField;
    private static TableView<PluginJarInfo> pluginTable;

    public static Node build() {
        VBox container = new VBox();
        container.setStyle(
            "-fx-background-color: rgba(255,255,255,0.022);" +
            "-fx-border-color: rgba(255,255,255,0.10);" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 12px;" +
            "-fx-background-radius: 12px;" +
            "-fx-padding: 0;"
        );

        TabPane tabs = new TabPane();
        tabs.setStyle("-fx-background-color: transparent;");

        Tab generalTab = new Tab("General");
        generalTab.setContent(buildGeneralTab());
        generalTab.setStyle("-fx-background-color: transparent; -fx-padding: 10 24 10 24;");

        Tab emailTab = new Tab("Email");
        emailTab.setContent(buildEmailTab());
        emailTab.setStyle("-fx-background-color: transparent; -fx-padding: 10 24 10 24;");

        Tab pluginTab = new Tab("Plugin");
        pluginTab.setContent(buildPluginTab());
        pluginTab.setStyle("-fx-background-color: transparent; -fx-padding: 10 24 10 24;");

        tabs.getTabs().addAll(generalTab, emailTab, pluginTab);

        // Style tabs header with glass appearance
        VBox tabHeader = new VBox();
        tabHeader.setStyle(
            "-fx-background-color: rgba(255,255,255,0.025);" +
            "-fx-border-color: rgba(255,255,255,0.08);" +
            "-fx-border-width: 0 0 1 0;"
        );
        tabHeader.getChildren().add(tabs);

        // Tab button styling
        for (int i = 0; i < tabs.getTabs().size(); i++) {
            Tab t = tabs.getTabs().get(i);
            int index = i;
            String baseStyle =
                "-fx-background-color: transparent;" +
                "-fx-border-color: transparent;" +
                "-fx-border-width: 0 0 2 0;" +
                "-fx-text-fill: rgba(255,255,255,0.50);" +
                "-fx-font-size: 13px;" +
                "-fx-font-weight: 500;" +
                "-fx-padding: 10 24 10 24;" +
                "-fx-cursor: hand;";

            t.setStyle(baseStyle);

            t.selectedProperty().addListener((obs, old, selected) -> {
                if (selected) {
                    t.setStyle(
                        "-fx-background-color: rgba(91,140,247,0.15);" +
                        "-fx-border-color: #5b8cf7;" +
                        "-fx-border-width: 0 0 2 0;" +
                        "-fx-text-fill: #5b8cf7;" +
                        "-fx-font-size: 13px;" +
                        "-fx-font-weight: 500;" +
                        "-fx-padding: 10 24 10 24;" +
                        "-fx-cursor: hand;"
                    );
                } else {
                    t.setStyle(baseStyle);
                }
            });
        }

        container.getChildren().add(tabHeader);

        return container;
    }

    // ═══════════════════════════════════════════════════════════════════
    // General Tab
    // ═══════════════════════════════════════════════════════════════════

    private static VBox buildGeneralTab() {
        Label title = sectionTitle("General Settings");
        Label langLabel = subLabel("Language");

        ComboBox<String> langCombo = new ComboBox<>(FXCollections.observableArrayList("中文", "English"));
        langCombo.setValue(getCurrentLanguageLabel());
        langCombo.setStyle(comboStyle());
        langCombo.setMaxWidth(200);
        langCombo.setOnAction(e -> {
            String selected = langCombo.getValue();
            saveLanguageSetting(selected);
        });

        HBox langRow = new HBox(12, langLabel, langCombo);
        langRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(16, title, langRow);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");
        return root;
    }

    private static String getCurrentLanguageLabel() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey("language");
            if (entity != null && "en".equals(entity.getSettingValue())) {
                return "English";
            }
        } catch (Exception e) {
            log.debug("Could not read language setting", e);
        }
        return "中文";
    }

    private static void saveLanguageSetting(String label) {
        new Thread(() -> {
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
                AppSettingEntity entity = mapper.selectByKey("language");
                if (entity != null) {
                    entity.setSettingValue("中文".equals(label) ? "zh" : "en");
                    mapper.update(entity);
                } else {
                    AppSettingEntity newEntity = new AppSettingEntity();
                    newEntity.setSettingKey("language");
                    newEntity.setSettingValue("中文".equals(label) ? "zh" : "en");
                    mapper.insert(newEntity);
                }
                session.commit();
                Platform.runLater(() -> {
                    Alert a = new Alert(Alert.AlertType.INFORMATION);
                    a.setTitle("Settings");
                    a.setHeaderText(null);
                    a.setContentText("Language changed. Restart may be required for full effect.");
                    a.showAndWait();
                });
            } catch (Exception e) {
                log.error("Failed to save language setting", e);
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Email Tab
    // ═══════════════════════════════════════════════════════════════════

    private static VBox buildEmailTab() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");

        root.getChildren().addAll(
            sectionTitle("Email Server Settings"),
            labeled("SMTP Server", textField(null, "smtp.example.com")),
            labeled("Port", textField(null, "587")),
            labeled("Username", textField(null, "user@example.com")),
            labeled("Password", passwordField()),
            labeled("From Address", textField(null, "noreply@example.com")),
            tlsSslRow(),
            saveEmailBtn(),
            openAddressBookBtn()
        );

        // Load existing email settings
        loadEmailSettings(root);

        return root;
    }

    private static void loadEmailSettings(VBox root) {
        new Thread(() -> {
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                SwissKitSettingEmailMapper mapper = session.getMapper(SwissKitSettingEmailMapper.class);
                SwissKitSettingEmailEntity entity = mapper.selectLatest();
                if (entity != null) {
                    Platform.runLater(() -> {
                        for (Node child : root.getChildren()) {
                            if (child instanceof HBox hb) {
                                Object labelNode = hb.getChildren().get(0);
                                if (labelNode instanceof Label lbl) {
                                    String text = lbl.getText();
                                    if (text != null) {
                                        Object fieldNode = hb.getChildren().get(1);
                                        if ("SMTP Server".equals(text) && fieldNode instanceof TextField tf) {
                                            tf.setText(entity.getSmtpAddress());
                                        } else if ("Port".equals(text) && fieldNode instanceof TextField tf2) {
                                            tf2.setText(String.valueOf(entity.getSmtpPort()));
                                        } else if ("Username".equals(text) && fieldNode instanceof TextField tf3) {
                                            tf3.setText(entity.getEmail());
                                        } else if ("Password".equals(text) && fieldNode instanceof PasswordField pf) {
                                            pf.setText(entity.getPassword());
                                        } else if ("From Address".equals(text) && fieldNode instanceof TextField tf4) {
                                            tf4.setText(entity.getFromAddress());
                                        }
                                    }
                                }
                            }
                        }
                        // Update TLS/SSL checkboxes
                        for (Node child : root.getChildren()) {
                            if (child instanceof VBox vb && vb.getChildren().size() == 2) {
                                Node inner = vb.getChildren().get(1);
                                if (inner instanceof HBox hb && hb.getChildren().size() == 2) {
                                    Object first = hb.getChildren().get(0);
                                    Object second = hb.getChildren().get(1);
                                    if (first instanceof CheckBox tlsCb && "TLS".equals(tlsCb.getText()) && entity.getNeedTLS() != null) {
                                        tlsCb.setSelected(entity.getNeedTLS());
                                    } else if (second instanceof CheckBox sslCb && "SSL".equals(sslCb.getText()) && entity.getNeedSSL() != null) {
                                        sslCb.setSelected(entity.getNeedSSL());
                                    }
                                }
                            }
                        }
                    });
                }
            } catch (Exception e) {
                log.debug("No existing email settings found", e);
            }
        }).start();
    }

    private static VBox tlsSslRow() {
        CheckBox tlsCheck = new CheckBox("TLS");
        tlsCheck.setStyle("-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 13px;");

        CheckBox sslCheck = new CheckBox("SSL");
        sslCheck.setStyle("-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 13px;");

        HBox checkRow = new HBox(16, tlsCheck, sslCheck);
        checkRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(4);
        Label lbl = new Label("");
        lbl.setStyle("-fx-text-fill: rgba(255,255,255,0.50); -fx-font-size: 11px; -fx-font-weight: bold;");
        box.getChildren().add(lbl);
        box.getChildren().add(checkRow);
        return box;
    }

    private static Button saveEmailBtn() {
        Button btn = glassBtn("Save", true);
        btn.setOnAction(e -> {
            VBox parent = (VBox) btn.getParent();
            saveEmailSettings(parent);
        });
        return btn;
    }

    private static void saveEmailSettings(VBox form) {
        String smtp = null, port = null, user = null, pass = null, from = null;
        boolean tls = false, ssl = false;
        List<Node> children = form.getChildren();

        for (Node child : children) {
            if (child instanceof HBox hb && hb.getChildren().size() >= 2) {
                Object first = hb.getChildren().get(0);
                if (first instanceof Label lbl) {
                    String text = lbl.getText();
                    Object field = hb.getChildren().get(1);
                    if ("SMTP Server".equals(text) && field instanceof TextField tf) smtp = tf.getText();
                    else if ("Port".equals(text) && field instanceof TextField tf) port = tf.getText();
                    else if ("Username".equals(text) && field instanceof TextField tf) user = tf.getText();
                    else if ("Password".equals(text) && field instanceof PasswordField pf) pass = pf.getText();
                    else if ("From Address".equals(text) && field instanceof TextField tf) from = tf.getText();
                }
            } else if (child instanceof VBox vb) {
                for (Node vbChild : vb.getChildren()) {
                    if (vbChild instanceof HBox hb && hb.getChildren().size() == 2) {
                        Object first = hb.getChildren().get(0);
                        Object second = hb.getChildren().get(1);
                        if (first instanceof CheckBox tlsCb && "TLS".equals(tlsCb.getText())) tls = tlsCb.isSelected();
                        if (second instanceof CheckBox sslCb && "SSL".equals(sslCb.getText())) ssl = sslCb.isSelected();
                    }
                }
            }
        }

        if (smtp == null || smtp.isBlank() || port == null || port.isBlank()
                || user == null || user.isBlank() || pass == null || pass.isBlank()
                || from == null || from.isBlank()) {
            alert(Alert.AlertType.WARNING, "Validation Error", "All fields are required.");
            return;
        }

        if (tls && ssl) {
            alert(Alert.AlertType.WARNING, "Validation Error", "TLS and SSL cannot be both selected.");
            return;
        }

        final String fSmtp = smtp.trim();
        final String fPort = port.trim();
        final String fUser = user.trim();
        final String fPass = pass.trim();
        final String fFrom = from.trim();
        final boolean fTls = tls;
        final boolean fSsl = ssl;

        new Thread(() -> {
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                SwissKitSettingEmailMapper mapper = session.getMapper(SwissKitSettingEmailMapper.class);
                mapper.deleteAll();
                SwissKitSettingEmailEntity entity = new SwissKitSettingEmailEntity();
                entity.setSmtpAddress(fSmtp);
                entity.setSmtpPort(Integer.parseInt(fPort));
                entity.setEmail(fUser);
                entity.setPassword(fPass);
                entity.setFromAddress(fFrom);
                entity.setNeedTLS(fTls);
                entity.setNeedSSL(fSsl);
                mapper.insert(entity);
                session.commit();
                log.info("Email settings saved: smtp={}:{}", fSmtp, fPort);
                Platform.runLater(() -> {
                    Alert a = new Alert(Alert.AlertType.INFORMATION);
                    a.setTitle("Success");
                    a.setHeaderText(null);
                    a.setContentText("Email settings saved successfully.");
                    a.showAndWait();
                });
            } catch (Exception ex) {
                log.error("Failed to save email settings", ex);
                Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Error", "Failed to save: " + ex.getMessage()));
            }
        }).start();
    }

    private static Button openAddressBookBtn() {
        Button btn = glassBtn("Address Book", false);
        btn.setOnAction(e -> openAddressBookDialog());
        return btn;
    }

    private static void openAddressBookDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Email Address Book");

        List<EmailAddressBookEntity> entities;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            entities = session.getMapper(EmailAddressBookMapper.class).selectEmailAddressBook();
            if (entities == null) entities = new ArrayList<>();
        } catch (Exception e) {
            alert(Alert.AlertType.ERROR, "Error", "Failed to load address book: " + e.getMessage());
            return;
        }

        TableView<EmailAddressBookEntity> table = new TableView<>(FXCollections.observableArrayList(entities));
        table.setStyle("-fx-background-color: transparent;");
        table.setPlaceholder(new Label("No addresses saved"));

        TableColumn<EmailAddressBookEntity, Integer> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        idCol.setPrefWidth(60);

        TableColumn<EmailAddressBookEntity, String> addrCol = new TableColumn<>("Address");
        addrCol.setCellValueFactory(new PropertyValueFactory<>("emailAddress"));
        addrCol.setPrefWidth(200);

        TableColumn<EmailAddressBookEntity, String> nickCol = new TableColumn<>("NickName");
        nickCol.setCellValueFactory(new PropertyValueFactory<>("nickname"));
        nickCol.setPrefWidth(150);

        TableColumn<EmailAddressBookEntity, String> tagsCol = new TableColumn<>("Tags");
        tagsCol.setCellValueFactory(new PropertyValueFactory<>("tags"));
        tagsCol.setPrefWidth(150);

        table.getColumns().addAll(idCol, addrCol, nickCol, tagsCol);
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 2) {
                EmailAddressBookEntity entity = table.getSelectionModel().getSelectedItem();
                if (entity != null) {
                    openAddAddressDialog(entity);
                    dialog.close();
                }
            }
        });

        Button addBtn = glassBtn("Add Address", true);
        addBtn.setOnAction(e -> {
            openAddAddressDialog(null);
            dialog.close();
        });

        Button manageTagsBtn = glassBtn("Manage Tags", false);
        manageTagsBtn.setOnAction(e -> openTagsDialog());

        Button closeBtn = glassBtn("Close", false);
        closeBtn.setOnAction(e -> dialog.close());

        HBox btnRow = new HBox(8, addBtn, manageTagsBtn, spacer(), closeBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, table, btnRow);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #1c1f26;");
        root.setPrefSize(700, 450);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        try {
            scene.getStylesheets().add(SwissKitJSettingUi.class.getResource("/css/glass.css").toExternalForm());
        } catch (Exception ignored) {}
        dialog.setScene(scene);
        dialog.show();
    }

    private static void openAddAddressDialog(EmailAddressBookEntity editEntity) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(editEntity == null ? "Add Address" : "Edit Address");

        TextField addressField = textField(null, "");
        TextField nicknameField = textField(null, "");
        TextField tagsField = new TextField();
        tagsField.setEditable(false);
        tagsField.setStyle(fieldStyle());

        ComboBox<EmailTagEntity> tagCombo = new ComboBox<>();
        tagCombo.setPromptText("Select Tag");
        tagCombo.setStyle(comboStyle());
        tagCombo.setMaxWidth(Double.MAX_VALUE);

        List<EmailTagEntity> allTags = new ArrayList<>();
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            allTags = session.getMapper(EmailTagMapper.class).selectAll();
            if (allTags != null) {
                tagCombo.getItems().addAll(allTags);
            }
        } catch (Exception e) {
            log.debug("Could not load tags", e);
        }

        AtomicReference<List<String>> selectedTagsRef = new AtomicReference<>(new ArrayList<>());
        AtomicReference<List<String>> selectedTagNamesRef = new AtomicReference<>(new ArrayList<>());

        tagCombo.setOnAction(e -> {
            EmailTagEntity selected = tagCombo.getValue();
            if (selected != null) {
                selectedTagsRef.get().add(String.valueOf(selected.getId()));
                selectedTagNamesRef.get().add(selected.getTag());
                tagsField.setText(String.join(", ", selectedTagNamesRef.get()));
                tagCombo.getItems().remove(selected);
                tagCombo.setValue(null);
            }
        });

        Button resetBtn = glassBtn("Reset", false);
        final List<EmailTagEntity> tagsSnapshot = new ArrayList<>(allTags);
        resetBtn.setOnAction(e -> {
            selectedTagsRef.set(new ArrayList<>());
            selectedTagNamesRef.set(new ArrayList<>());
            tagsField.setText("");
            tagsSnapshot.forEach(t -> {
                if (!tagCombo.getItems().contains(t)) {
                    tagCombo.getItems().add(t);
                }
            });
        });

        if (editEntity != null) {
            addressField.setText(editEntity.getEmailAddress());
            nicknameField.setText(editEntity.getNickname());
            try {
                String tagsJson = editEntity.getTags();
                if (tagsJson != null && !tagsJson.isBlank()) {
                    java.util.regex.Matcher m = NUMERIC_ID_PATTERN.matcher(tagsJson);
                    while (m.find()) {
                        String idStr = m.group();
                        for (EmailTagEntity tag : allTags) {
                            if (String.valueOf(tag.getId()).equals(idStr)) {
                                selectedTagsRef.get().add(idStr);
                                selectedTagNamesRef.get().add(tag.getTag());
                                break;
                            }
                        }
                    }
                    tagsField.setText(String.join(", ", selectedTagNamesRef.get()));
                }
            } catch (Exception ex) {
                log.debug("Could not parse existing tags", ex);
            }
        }

        Button okBtn = glassBtn("Save", true);
        okBtn.setOnAction(e -> {
            String address = addressField.getText();
            if (address == null || address.isBlank() || !address.matches(".+@.+\\..+")) {
                alert(Alert.AlertType.WARNING, "Validation Error", "Valid email address is required.");
                return;
            }
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                EmailAddressBookMapper mapper = session.getMapper(EmailAddressBookMapper.class);
                EmailAddressBookEntity entity = new EmailAddressBookEntity();
                if (editEntity != null) entity.setId(editEntity.getId());
                entity.setEmailAddress(address.trim());
                entity.setNickname(nicknameField.getText() != null ? nicknameField.getText().trim() : "");
                List<String> tagsList = selectedTagsRef.get();
                entity.setTags("[" + String.join(",", tagsList.stream().map(s -> "\"" + s + "\"").toList()) + "]");
                if (editEntity != null) {
                    mapper.update(entity);
                } else {
                    mapper.insert(entity);
                }
                session.commit();
                dialog.close();
                openAddressBookDialog();
            } catch (Exception ex) {
                log.error("Failed to save address", ex);
                alert(Alert.AlertType.ERROR, "Error", "Failed to save: " + ex.getMessage());
            }
        });

        Button closeBtn = glassBtn("Cancel", false);
        closeBtn.setOnAction(e -> dialog.close());

        HBox tagRow = new HBox(8, tagsField, resetBtn);
        HBox.setHgrow(tagsField, Priority.ALWAYS);

        HBox btnRow = new HBox(8, spacer(), okBtn, closeBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(14,
            labeled("Address", addressField),
            labeled("NickName", nicknameField),
            labeled("Tags", tagRow),
            labeled("Select Tag", tagCombo),
            btnRow
        );
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #1c1f26;");
        root.setPrefWidth(480);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        try {
            scene.getStylesheets().add(SwissKitJSettingUi.class.getResource("/css/glass.css").toExternalForm());
        } catch (Exception ignored) {}
        dialog.setScene(scene);
        dialog.show();
    }

    private static void openTagsDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Manage Tags");

        List<EmailTagEntity> tags;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            tags = session.getMapper(EmailTagMapper.class).selectAll();
            if (tags == null) tags = new ArrayList<>();
        } catch (Exception e) {
            alert(Alert.AlertType.ERROR, "Error", "Failed to load tags: " + e.getMessage());
            return;
        }

        TableView<EmailTagEntity> table = new TableView<>(FXCollections.observableArrayList(tags));
        table.setStyle("-fx-background-color: transparent;");

        TableColumn<EmailTagEntity, Long> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        idCol.setPrefWidth(80);

        TableColumn<EmailTagEntity, String> tagCol = new TableColumn<>("Tag");
        tagCol.setCellValueFactory(new PropertyValueFactory<>("tag"));
        tagCol.setPrefWidth(200);

        table.getColumns().addAll(idCol, tagCol);

        AtomicReference<Long> updateIdRef = new AtomicReference<>(null);
        TextField tagField = textField(null, "");
        Button addTagBtn = glassBtn("Add Tag", true);

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 2) {
                EmailTagEntity selected = table.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    updateIdRef.set(selected.getId());
                    tagField.setText(selected.getTag());
                    addTagBtn.setText("Update");
                }
            }
        });

        addTagBtn.setOnAction(e -> {
            String tagText = tagField.getText();
            if (tagText == null || tagText.isBlank()) return;

            Long currentUpdateId = updateIdRef.get();
            if ("Update".equals(addTagBtn.getText()) && currentUpdateId != null) {
                // Update existing tag
                final Long uid = currentUpdateId;
                new Thread(() -> {
                    try (SqlSession session = DatabaseInit.getSqlSession()) {
                        EmailTagMapper mapper = session.getMapper(EmailTagMapper.class);
                        EmailTagEntity entity = new EmailTagEntity();
                        entity.setId(uid);
                        entity.setTag(tagText.trim());
                        mapper.update(entity);
                        session.commit();
                        Platform.runLater(() -> {
                            dialog.close();
                            openTagsDialog();
                        });
                    } catch (Exception ex) {
                        log.error("Failed to update tag", ex);
                    }
                }).start();
            } else {
                // Insert new tag
                new Thread(() -> {
                    try (SqlSession session = DatabaseInit.getSqlSession()) {
                        EmailTagMapper mapper = session.getMapper(EmailTagMapper.class);
                        EmailTagEntity entity = new EmailTagEntity();
                        entity.setTag(tagText.trim());
                        mapper.insert(entity);
                        session.commit();
                        Platform.runLater(() -> {
                            tagField.setText("");
                            dialog.close();
                            openTagsDialog();
                        });
                    } catch (Exception ex) {
                        log.error("Failed to insert tag", ex);
                    }
                }).start();
            }
        });

        Button closeBtn = glassBtn("Close", false);
        closeBtn.setOnAction(e -> dialog.close());

        HBox inputRow = new HBox(8, tagField, addTagBtn);
        HBox.setHgrow(tagField, Priority.ALWAYS);

        HBox btnRow = new HBox(8, spacer(), closeBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(14, table, inputRow, btnRow);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #1c1f26;");
        root.setPrefSize(400, 400);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        try {
            scene.getStylesheets().add(SwissKitJSettingUi.class.getResource("/css/glass.css").toExternalForm());
        } catch (Exception ignored) {}
        dialog.setScene(scene);
        dialog.show();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Plugin Tab
    // ═══════════════════════════════════════════════════════════════════

    private static VBox buildPluginTab() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");

        pluginTable = new TableView<>();
        pluginTable.setStyle("-fx-background-color: rgba(255,255,255,0.03);");
        TableColumn<PluginJarInfo, String> nameCol = new TableColumn<>("Plugin");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(200);
        TableColumn<PluginJarInfo, String> versionCol = new TableColumn<>("Version");
        versionCol.setCellValueFactory(new PropertyValueFactory<>("version"));
        versionCol.setPrefWidth(100);
        TableColumn<PluginJarInfo, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setPrefWidth(100);
        TableColumn<PluginJarInfo, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(new PropertyValueFactory<>("size"));
        sizeCol.setPrefWidth(100);
        pluginTable.getColumns().addAll(nameCol, versionCol, statusCol, sizeCol);
        pluginTable.setMaxHeight(200);

        pluginPathField = new TextField();
        pluginPathField.setEditable(false);
        pluginPathField.setStyle(fieldStyle());

        Button chooseBtn = glassBtn("Choose Plugin", false);
        Button deployBtn = glassBtn("Deploy", true);
        Button enableDisableBtn = glassBtn("Enable/Disable", false);
        Button reloadBtn = glassBtn("Reload", false);
        Button uninstallBtn = glassBtn("Uninstall", false);

        enableDisableBtn.setDisable(true);
        reloadBtn.setDisable(true);
        uninstallBtn.setDisable(true);

        pluginTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            boolean hasSelection = sel != null;
            enableDisableBtn.setDisable(!hasSelection);
            reloadBtn.setDisable(!hasSelection);
            uninstallBtn.setDisable(!hasSelection);
        });

        chooseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Plugin JAR");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR Files", "*.jar"));
            File file = fc.showOpenDialog(null);
            if (file != null) {
                pluginPathField.setText(file.getAbsolutePath());
            }
        });

        deployBtn.setOnAction(e -> {
            String path = pluginPathField.getText();
            if (path == null || path.isBlank()) {
                alert(Alert.AlertType.WARNING, "No Selection", "Please select a plugin JAR first.");
                return;
            }
            File source = new File(path);
            if (!source.exists() || !source.getName().toLowerCase().endsWith(".jar")) {
                alert(Alert.AlertType.ERROR, "Invalid File", "Please select a valid JAR file.");
                return;
            }
            deployPlugin(source);
        });

        enableDisableBtn.setOnAction(e -> {
            PluginJarInfo sel = pluginTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            boolean currentlyEnabled = !"Disabled".equals(sel.getStatus());
            int newDisabled = currentlyEnabled ? 1 : 0;
            new Thread(() -> {
                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    PluginManagerMapper mapper = session.getMapper(PluginManagerMapper.class);
                    mapper.updateDisabled(sel.getName(), newDisabled);
                    session.commit();
                    refreshPluginList();
                } catch (Exception ex) {
                    log.error("Failed to toggle plugin state", ex);
                    Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Error", "Failed to update plugin state."));
                }
            }).start();
        });

        reloadBtn.setOnAction(e -> {
            PluginJarInfo sel = pluginTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            alert(Alert.AlertType.INFORMATION, "Reload", "Plugin hot-reload would be triggered here.\n(Requires PluginLoader modifications — not permitted per constraint.)");
        });

        uninstallBtn.setOnAction(e -> {
            PluginJarInfo sel = pluginTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Confirm Uninstall");
            confirm.setHeaderText(null);
            confirm.setContentText("Uninstall plugin: " + sel.getName() + "?");
            confirm.showAndWait();
            if (confirm.getResult() != javafx.scene.control.ButtonType.OK) return;

            new Thread(() -> {
                try {
                    Path pluginDir = Path.of(System.getProperty("user.dir"), "plugins");
                    Path target = pluginDir.resolve(sel.getName());
                    Files.deleteIfExists(target);
                    Platform.runLater(() -> {
                        refreshPluginList();
                        alert(Alert.AlertType.INFORMATION, "Success", "Plugin uninstalled.");
                    });
                } catch (Exception ex) {
                    log.error("Failed to uninstall plugin", ex);
                    Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Error", "Failed to uninstall: " + ex.getMessage()));
                }
            }).start();
        });

        HBox pathRow = new HBox(8, chooseBtn, pluginPathField);
        HBox btnRow = new HBox(8, deployBtn, enableDisableBtn, reloadBtn, uninstallBtn);

        root.getChildren().addAll(
            sectionTitle("Plugin Management"),
            pluginTable,
            pathRow,
            btnRow
        );

        refreshPluginList();

        return root;
    }

    private static void deployPlugin(File source) {
        new Thread(() -> {
            try {
                Path pluginDir = Path.of(System.getProperty("user.dir"), "plugins");
                Files.createDirectories(pluginDir);
                Path target = pluginDir.resolve(source.getName());
                Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                log.info("Plugin deployed: {}", target);
                Platform.runLater(() -> {
                    pluginPathField.setText("");
                    refreshPluginList();
                    alert(Alert.AlertType.INFORMATION, "Success", "Plugin deployed: " + source.getName());
                });
            } catch (Exception ex) {
                log.error("Plugin deploy failed", ex);
                Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Error", "Deploy failed: " + ex.getMessage()));
            }
        }).start();
    }

    private static void refreshPluginList() {
        new Thread(() -> {
            try {
                Path pluginDir = Path.of(System.getProperty("user.dir"), "plugins");
                List<PluginJarInfo> plugins = new ArrayList<>();
                if (Files.exists(pluginDir)) {
                    try (var stream = Files.list(pluginDir)) {
                        stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                            .forEach(p -> {
                                try {
                                    long size = Files.size(p);
                                    String sizeStr;
                                    if (size < 1024) sizeStr = size + " B";
                                    else if (size < 1024 * 1024) sizeStr = String.format("%.1f KB", size / 1024.0);
                                    else sizeStr = String.format("%.1f MB", size / (1024.0 * 1024.0));

                                    String version = "N/A";
                                    String status = "Enabled";

                                    try (SqlSession session = DatabaseInit.getSqlSession()) {
                                        PluginManagerMapper mapper = session.getMapper(PluginManagerMapper.class);
                                        PluginManagerEntity state = mapper.selectByJarName(p.getFileName().toString());
                                        if (state != null) {
                                            if (state.getPluginVersion() != null) version = state.getPluginVersion();
                                            if (state.getIsDisabled() != null && state.getIsDisabled() == 1) status = "Disabled";
                                        }
                                    } catch (Exception ignored) {}

                                    plugins.add(new PluginJarInfo(p.getFileName().toString(), version, status, sizeStr));
                                } catch (Exception ignored) {}
                            });
                    }
                }
                final List<PluginJarInfo> finalPlugins = plugins;
                Platform.runLater(() -> {
                    pluginTable.getItems().setAll(finalPlugins);
                });
            } catch (Exception e) {
                log.error("Failed to refresh plugin list", e);
            }
        }).start();
    }

    public static class PluginJarInfo {
        private final String name;
        private final String version;
        private final String status;
        private final String size;

        public PluginJarInfo(String name, String version, String status, String size) {
            this.name = name;
            this.version = version;
            this.status = status;
            this.size = size;
        }

        public String getName() { return name; }
        public String getVersion() { return version; }
        public String getStatus() { return status; }
        public String getSize() { return size; }
    }

    // ═══════════════════════════════════════════════════════════════════
    // UI Helpers
    // ═══════════════════════════════════════════════════════════════════

    private static VBox labeled(String labelText, Node field) {
        VBox box = new VBox(4);
        if (labelText != null && !labelText.isEmpty()) {
            Label lbl = new Label(labelText);
            lbl.setStyle("-fx-text-fill: rgba(255,255,255,0.50); -fx-font-size: 11px; -fx-font-weight: bold;");
            box.getChildren().add(lbl);
        }
        box.getChildren().add(field);
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

    private static TextField textField(String style, String prompt) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.setStyle(style != null ? style : fieldStyle());
        return tf;
    }

    private static PasswordField passwordField() {
        PasswordField pf = new PasswordField();
        pf.setStyle(fieldStyle());
        return pf;
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