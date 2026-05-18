package fan.summer.ui.setting;

import fan.summer.database.DatabaseInit;
import fan.summer.ui.sidebar.Sidebar.NavItem;
import fan.summer.database.entity.setting.email.EmailAddressBookEntity;
import fan.summer.database.entity.setting.email.EmailTagEntity;
import fan.summer.database.entity.setting.email.SwissKitSettingEmailEntity;
import fan.summer.database.mapper.AppSettingMapper;
import fan.summer.database.mapper.setting.email.EmailAddressBookMapper;
import fan.summer.database.mapper.setting.email.EmailTagMapper;
import fan.summer.database.mapper.setting.email.SwissKitSettingEmailMapper;
import fan.summer.database.entity.AppSettingEntity;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Settings UI for SwissKit.
 * Sidebar menu: General (language), Build-In Tools > Email (SMTP config + address book).
 */
public class SwissKitJSettingUi {

    private static final Logger log = LoggerFactory.getLogger(SwissKitJSettingUi.class);

    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("\\d+");

    public static Node build() {
        // ── Content pages (created once, cached) ──────────────
        Node generalPage      = buildGeneralTab();
        Node storePage        = buildPluginStoreSettings();
        Node emailPage        = buildEmailTab();

        StackPane contentStack = new StackPane(generalPage, storePage, emailPage);
        contentStack.setStyle("-fx-background-color: transparent;");
        storePage.setVisible(false);
        emailPage.setVisible(false);

        // ── Sidebar ──────────────────────────────────────────
        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(180);
        sidebar.setMinWidth(160);
        sidebar.setMaxWidth(200);

        sidebar.getChildren().add(sidebarSectionLabel("SETTINGS"));
        NavItem generalNav = sidebarNavItem("⚙", "General");
        NavItem storeNav   = sidebarNavItem("🏪", "Plugin Store");
        sidebar.getChildren().addAll(generalNav, storeNav);

        sidebar.getChildren().add(sidebarDivider());

        sidebar.getChildren().add(sidebarSectionLabel("BUILD-IN TOOLS"));
        NavItem emailNav = sidebarNavItem("✉", "Email");
        sidebar.getChildren().add(emailNav);

        generalNav.setActive(true);

        // Spacer to push items to top
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().add(spacer);

        // ── Selection wiring ─────────────────────────────────
        NavItem[] items = {generalNav, storeNav, emailNav};
        Node[]    pages = {generalPage, storePage, emailPage};

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            items[i].setOnMouseClicked(e -> {
                for (NavItem item : items) item.setActive(false);
                for (Node page : pages) page.setVisible(false);
                items[idx].setActive(true);
                pages[idx].setVisible(true);
            });
        }

        // ── Layout: sidebar + content ────────────────────────
        HBox body = new HBox(sidebar, contentStack);
        HBox.setHgrow(contentStack, Priority.ALWAYS);

        VBox container = new VBox(body);
        container.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        VBox.setVgrow(body, Priority.ALWAYS);

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
        langCombo.getStyleClass().add("glass-combo");
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
    // Plugin Store Settings
    // ═══════════════════════════════════════════════════════════════════

    private static final String STORE_URL_KEY = "plugin.store.url";
    private static final String DEFAULT_STORE_URL = "https://muskstark.github.io/SwissKitJ/plugins/store.json";

    /** Returns the stored plugin store URL, or the default if none is set. */
    public static String getStoreUrl() {
        String override = System.getProperty("store.url");
        if (override != null && !override.isBlank()) {
            return override;
        }
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey(STORE_URL_KEY);
            if (entity != null && entity.getSettingValue() != null && !entity.getSettingValue().isBlank()) {
                return entity.getSettingValue();
            }
        } catch (Exception e) {
            log.debug("Could not read store URL setting", e);
        }
        return DEFAULT_STORE_URL;
    }

    private static VBox buildPluginStoreSettings() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");

        Label title = sectionTitle("Plugin Store Settings");
        Label descLabel = subLabel("Plugin Store URL");
        Label desc = new Label("Configure the endpoint for fetching remote plugin lists.");
        desc.setStyle("-fx-text-fill: rgba(255,255,255,0.35); -fx-font-size: 12px;");
        desc.setWrapText(true);

        TextField urlField = textField(null, DEFAULT_STORE_URL);
        urlField.setText(getStoreUrl());

        Button saveBtn = glassBtn("Save", true);
        saveBtn.setOnAction(e -> {
            String url = urlField.getText();
            if (url == null || url.isBlank()) {
                alert(Alert.AlertType.WARNING, "Validation Error", "URL cannot be empty.");
                return;
            }
            saveStoreUrl(url.trim());
        });

        Button resetBtn = glassBtn("Reset to Default", false);
        resetBtn.setOnAction(e -> {
            urlField.setText(DEFAULT_STORE_URL);
            saveStoreUrl(DEFAULT_STORE_URL);
        });

        HBox btnRow = new HBox(8, saveBtn, resetBtn);

        root.getChildren().addAll(title, descLabel, desc, urlField, btnRow);
        return root;
    }

    private static void saveStoreUrl(String url) {
        new Thread(() -> {
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
                AppSettingEntity entity = mapper.selectByKey(STORE_URL_KEY);
                if (entity != null) {
                    entity.setSettingValue(url);
                    mapper.update(entity);
                } else {
                    AppSettingEntity newEntity = new AppSettingEntity();
                    newEntity.setSettingKey(STORE_URL_KEY);
                    newEntity.setSettingValue(url);
                    mapper.insert(newEntity);
                }
                session.commit();
                Platform.runLater(() -> {
                    Alert a = new Alert(Alert.AlertType.INFORMATION);
                    a.setTitle("Saved");
                    a.setHeaderText(null);
                    a.setContentText("Plugin store URL saved.");
                    a.showAndWait();
                });
            } catch (Exception ex) {
                log.error("Failed to save store URL", ex);
                Platform.runLater(() -> alert(Alert.AlertType.ERROR, "Error", "Failed to save: " + ex.getMessage()));
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
        tlsCheck.getStyleClass().add("glass-checkbox");

        CheckBox sslCheck = new CheckBox("SSL");
        sslCheck.getStyleClass().add("glass-checkbox");

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
        table.getStyleClass().add("glass-table");
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
        root.getStyleClass().add("glass-dialog");
        root.setPrefSize(700, 450);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.setFill(javafx.scene.paint.Color.web("#0d0e11"));
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
        tagsField.getStyleClass().add(FIELD_STYLE_CLASS);

        ComboBox<EmailTagEntity> tagCombo = new ComboBox<>();
        tagCombo.setPromptText("Select Tag");
        tagCombo.getStyleClass().add("glass-combo");
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
        root.getStyleClass().add("glass-dialog");
        root.setPrefWidth(480);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.setFill(javafx.scene.paint.Color.web("#0d0e11"));
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
        table.getStyleClass().add("glass-table");

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
        root.getStyleClass().add("glass-dialog");
        root.setPrefSize(400, 400);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.setFill(javafx.scene.paint.Color.web("#0d0e11"));
        try {
            scene.getStylesheets().add(SwissKitJSettingUi.class.getResource("/css/glass.css").toExternalForm());
        } catch (Exception ignored) {}
        dialog.setScene(scene);
        dialog.show();
    }

    // ═══════════════════════════════════════════════════════════════════
    // UI Helpers
    // ═══════════════════════════════════════════════════════════════════

    private static VBox labeled(String labelText, Node field) {
        VBox box = new VBox(4);
        if (labelText != null && !labelText.isEmpty()) {
            Label lbl = new Label(labelText);
            lbl.getStyleClass().add("glass-field-label");
            box.getChildren().add(lbl);
        }
        box.getChildren().add(field);
        return box;
    }

    private static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-header");
        return l;
    }

    private static Label subLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("glass-field-label");
        return l;
    }

    private static TextField textField(String style, String prompt) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        if (style != null) {
            tf.setStyle(style);
        } else {
            tf.getStyleClass().add(FIELD_STYLE_CLASS);
        }
        return tf;
    }

    private static PasswordField passwordField() {
        PasswordField pf = new PasswordField();
        pf.getStyleClass().add(FIELD_STYLE_CLASS);
        return pf;
    }

    private static final String FIELD_STYLE_CLASS = "glass-field";

    private static Button glassBtn(String text, boolean primary) {
        Button btn = new Button(text);
        if (primary) {
            btn.getStyleClass().add("glass-btn-primary");
        } else {
            btn.getStyleClass().add("glass-btn-secondary");
        }
        return btn;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static Label sidebarSectionLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.getStyleClass().add("sidebar-section-label");
        return l;
    }

    private static NavItem sidebarNavItem(String icon, String label) {
        return new NavItem(label.toLowerCase(), icon, label, 0, false);
    }

    private static Region sidebarDivider() {
        Region d = new Region();
        d.getStyleClass().add("sidebar-divider");
        d.setPrefHeight(1);
        VBox.setMargin(d, new Insets(6, 4, 6, 4));
        return d;
    }

    private static void alert(Alert.AlertType type, String title, String message) {
        Alert a = new Alert(type);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(message);
        a.showAndWait();
    }
}