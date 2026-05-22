package fan.summer.ui.setting;

import fan.summer.api.ai.AiService;
import fan.summer.api.ai.AiServiceProvider;
import fan.summer.api.i18n.I18n;
import fan.summer.api.theme.Themes;
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
import fan.summer.api.component.GlassNotification;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
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
import javafx.stage.Window;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Settings UI for SwissKit.
 * Sidebar menu: General (language), Build-In Tools > Email (SMTP config + address book).
 */
public class SwissKitJSettingUi {

    private static final Logger log = LoggerFactory.getLogger(SwissKitJSettingUi.class);

    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("\\d+");

    /** Active nav index, preserved across locale rebuilds. */
    private static int activeNavIndex = 0;

    public static Node build() {
        StackPane wrapper = new StackPane();
        wrapper.setStyle("-fx-background-color: transparent;");

        Runnable rebuild = () -> wrapper.getChildren().setAll(buildContent());
        rebuild.run();

        I18n.addListener(() -> Platform.runLater(rebuild));
        return wrapper;
    }

    private static VBox buildContent() {
        // ── Content pages (created once, cached) ──────────────
        Node generalPage      = buildGeneralTab();
        Node storePage        = buildPluginStoreSettings();
        Node aiModelPage      = buildAiModelTab();
        Node emailPage        = buildEmailTab();

        StackPane contentStack = new StackPane(generalPage, storePage, aiModelPage, emailPage);
        contentStack.setStyle("-fx-background-color: transparent;");
        storePage.setVisible(false);
        aiModelPage.setVisible(false);
        emailPage.setVisible(false);

        // ── Sidebar ──────────────────────────────────────────
        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(180);
        sidebar.setMinWidth(160);
        sidebar.setMaxWidth(200);

        sidebar.getChildren().add(sidebarSectionLabel(I18n.get("setting.section")));
        NavItem generalNav = sidebarNavItem("⚙", I18n.get("setting.nav.general"));
        NavItem storeNav   = sidebarNavItem("🏪", I18n.get("setting.nav.pluginStore"));
        sidebar.getChildren().addAll(generalNav, storeNav);

        sidebar.getChildren().add(sidebarDivider());

        sidebar.getChildren().add(sidebarSectionLabel("AI"));
        NavItem aiModelNav = sidebarNavItem("🤖", I18n.get("setting.nav.aiModel"));
        sidebar.getChildren().add(aiModelNav);

        sidebar.getChildren().add(sidebarDivider());

        sidebar.getChildren().add(sidebarSectionLabel(I18n.get("setting.nav.buildInTools")));
        NavItem emailNav = sidebarNavItem("✉", I18n.get("setting.nav.email"));
        sidebar.getChildren().add(emailNav);

        // Spacer to push items to top
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().add(spacer);

        // ── Selection wiring ─────────────────────────────────
        NavItem[] items = {generalNav, storeNav, aiModelNav, emailNav};
        Node[]    pages = {generalPage, storePage, aiModelPage, emailPage};

        // Restore previously active tab
        int active = Math.min(activeNavIndex, items.length - 1);
        items[active].setActive(true);
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisible(i == active);
        }

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            items[i].setOnMouseClicked(e -> {
                activeNavIndex = idx;
                for (NavItem item : items) item.setActive(false);
                for (Node page : pages) page.setVisible(false);
                items[idx].setActive(true);
                pages[idx].setVisible(true);
            });
        }

        // ── Layout: sidebar + content ────────────────────────
        ScrollPane contentScroll = new ScrollPane(contentStack);
        contentScroll.setFitToWidth(true);
        contentScroll.setFitToHeight(true);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        contentScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        contentScroll.getStyleClass().add("content-scroll");

        HBox body = new HBox(sidebar, contentScroll);
        HBox.setHgrow(contentScroll, Priority.ALWAYS);

        VBox container = new VBox(body);
        container.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        VBox.setVgrow(body, Priority.ALWAYS);

        return container;
    }

    // ═══════════════════════════════════════════════════════════════════
    // General Tab
    // ═══════════════════════════════════════════════════════════════════

    private static VBox buildGeneralTab() {
        Label title = sectionTitle(I18n.get("setting.general.title"));
        Label langLabel = subLabel(I18n.get("setting.general.language"));

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
        boolean isZh = "中文".equals(label);
        String code = isZh ? "zh" : "en";
        Locale locale = isZh ? Locale.CHINESE : Locale.ENGLISH;
        new Thread(() -> {
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
                AppSettingEntity entity = mapper.selectByKey("language");
                if (entity != null) {
                    entity.setSettingValue(code);
                    mapper.update(entity);
                } else {
                    AppSettingEntity newEntity = new AppSettingEntity();
                    newEntity.setSettingKey("language");
                    newEntity.setSettingValue(code);
                    mapper.insert(newEntity);
                }
                session.commit();
                Platform.runLater(() -> {
                    I18n.setLocale(locale);
                    GlassNotification.toast((Window) null, GlassNotification.Type.INFO,
                        I18n.get("setting.general.languageChanged"));
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

        Label title = sectionTitle(I18n.get("setting.store.title"));
        Label descLabel = subLabel(I18n.get("setting.store.label"));
        Label desc = new Label(I18n.get("setting.store.desc"));
        desc.setStyle("-fx-text-fill: rgba(255,255,255,0.35); -fx-font-size: 12px;");
        desc.setWrapText(true);

        TextField urlField = textField(null, DEFAULT_STORE_URL);
        urlField.setText(getStoreUrl());

        Button saveBtn = glassBtn(I18n.get("button.save"), true);
        saveBtn.setOnAction(e -> {
            String url = urlField.getText();
            if (url == null || url.isBlank()) {
                GlassNotification.notify((Window) null, GlassNotification.Type.WARNING, I18n.get("setting.store.urlEmpty"));
                return;
            }
            saveStoreUrl(url.trim());
        });

        Button resetBtn = glassBtn(I18n.get("button.resetToDefault"), false);
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
                Platform.runLater(() ->
                    GlassNotification.toast((Window) null, GlassNotification.Type.SUCCESS,
                        I18n.get("setting.store.saved")));
            } catch (Exception ex) {
                log.error("Failed to save store URL", ex);
                Platform.runLater(() -> GlassNotification.notify((Window) null, GlassNotification.Type.ERROR, I18n.get("setting.store.saveFailed", ex.getMessage())));
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════════════════
    // AI Model Tab
    // ═══════════════════════════════════════════════════════════════════

    private static final String AI_MODEL_PATH_KEY = "ai.model.path";
    private static final String AI_TEMPERATURE_KEY = "ai.temperature";
    private static final String AI_TOP_P_KEY = "ai.top_p";
    private static final String AI_MAX_TOKENS_KEY = "ai.max_tokens";
    private static final String AI_SYSTEM_PROMPT_KEY = "ai.system_prompt";

    private static VBox buildAiModelTab() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");

        Label title = sectionTitle(I18n.get("setting.ai.title"));

        // ── Model status section ─────────────────────────────
        Label modelStatusLabel = new Label(I18n.get("setting.ai.noModelLoaded"));
        modelStatusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 13px;");

        Label modelPathLabel = new Label("—");
        modelPathLabel.setWrapText(true);
        modelPathLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.35); -fx-font-size: 12px;");

        TextField modelPathField = textField(null, I18n.get("setting.ai.selectModel"));
        loadAiSetting(AI_MODEL_PATH_KEY, val -> modelPathField.setText(val));

        Button browseBtn = glassBtn(I18n.get("setting.ai.browse"), false);
        Button loadBtn = glassBtn(I18n.get("setting.ai.loadModel"), true);
        Button unloadBtn = glassBtn(I18n.get("setting.ai.unload"), false);
        unloadBtn.setDisable(true);

        browseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get("setting.ai.selectModel"));
            chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("GGUF Model", "*.gguf")
            );
            File file = chooser.showOpenDialog(browseBtn.getScene().getWindow());
            if (file != null) {
                modelPathField.setText(file.getAbsolutePath());
                saveAiSetting(AI_MODEL_PATH_KEY, file.getAbsolutePath());
            }
        });

        loadBtn.setOnAction(e -> {
            String path = modelPathField.getText();
            if (path == null || path.isBlank()) {
                GlassNotification.notify((Window) null, GlassNotification.Type.WARNING,
                    I18n.get("setting.ai.selectModelFile"));
                return;
            }

            loadBtn.setDisable(true);
            modelStatusLabel.setText(I18n.get("setting.ai.loadingModel"));
            saveAiSetting(AI_MODEL_PATH_KEY, path.trim());

            Thread.ofVirtual().start(() -> {
                try {
                    Optional<AiService> opt = AiServiceProvider.getService();
                    if (opt.isEmpty()) {
                        Platform.runLater(() -> {
                            modelStatusLabel.setText(I18n.get("setting.ai.aiServiceError"));
                            loadBtn.setDisable(false);
                        });
                        return;
                    }
                    AiService service = opt.get();
                    service.loadModel(Path.of(path.trim()));
                    Platform.runLater(() -> {
                        modelStatusLabel.setText(I18n.get("setting.ai.modelLoaded", service.getModelName().orElse("Unknown")));
                        modelPathLabel.setText(path.trim());
                        loadBtn.setDisable(false);
                        unloadBtn.setDisable(false);
                        AiServiceProvider.notifyStateChanged();
                    });
                } catch (Exception ex) {
                    log.error("Failed to load AI model", ex);
                    Platform.runLater(() -> {
                        modelStatusLabel.setText(I18n.get("setting.ai.modelLoadFailed", ex.getMessage()));
                        loadBtn.setDisable(false);
                    });
                }
            });
        });

        unloadBtn.setOnAction(e -> {
            Optional<AiService> opt = AiServiceProvider.getService();
            if (opt.isPresent()) {
                opt.get().unloadModel();
            }
            modelStatusLabel.setText(I18n.get("setting.ai.noModelLoaded"));
            modelPathLabel.setText("—");
            unloadBtn.setDisable(true);
            AiServiceProvider.notifyStateChanged();
        });

        HBox modelBtnRow = new HBox(8, browseBtn, loadBtn, unloadBtn);
        modelBtnRow.setAlignment(Pos.CENTER_LEFT);

        // Memory usage bar
        Label memLabel = new Label(I18n.get("setting.ai.memoryUsage"));
        memLabel.getStyleClass().add("glass-field-label");
        ProgressBar memBar = new ProgressBar(0);
        memBar.setPrefWidth(300);
        Label memText = new Label("—");
        memText.setStyle("-fx-text-fill: rgba(255,255,255,0.35); -fx-font-size: 11px;");

        HBox memRow = new HBox(10, memBar, memText);
        memRow.setAlignment(Pos.CENTER_LEFT);

        // ── Generation parameters ────────────────────────────
        Label paramTitle = sectionTitle(I18n.get("setting.ai.genParams"));

        // Temperature
        Label tempLabel = subLabel(I18n.get("setting.ai.temperature"));
        Label tempValue = new Label("0.7");
        tempValue.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");
        Slider tempSlider = new Slider(0, 2, 0.7);
        tempSlider.setShowTickLabels(true);
        tempSlider.setShowTickMarks(true);
        tempSlider.setMajorTickUnit(0.5);
        tempSlider.setMinorTickCount(4);
        tempSlider.setBlockIncrement(0.1);
        tempSlider.setPrefWidth(300);
        tempSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            tempValue.setText(String.format("%.2f", newVal.doubleValue()));
            saveAiSetting(AI_TEMPERATURE_KEY, String.format("%.2f", newVal.doubleValue()));
        });
        loadAiSetting(AI_TEMPERATURE_KEY, val -> {
            try { tempSlider.setValue(Double.parseDouble(val)); } catch (NumberFormatException ignored) {}
        });
        HBox tempRow = new HBox(10, tempSlider, tempValue);
        tempRow.setAlignment(Pos.CENTER_LEFT);

        // Top P
        Label topPLabel = subLabel(I18n.get("setting.ai.topP"));
        Label topPValue = new Label("0.9");
        topPValue.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");
        Slider topPSlider = new Slider(0, 1, 0.9);
        topPSlider.setShowTickLabels(true);
        topPSlider.setShowTickMarks(true);
        topPSlider.setMajorTickUnit(0.25);
        topPSlider.setMinorTickCount(3);
        topPSlider.setBlockIncrement(0.05);
        topPSlider.setPrefWidth(300);
        topPSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            topPValue.setText(String.format("%.2f", newVal.doubleValue()));
            saveAiSetting(AI_TOP_P_KEY, String.format("%.2f", newVal.doubleValue()));
        });
        loadAiSetting(AI_TOP_P_KEY, val -> {
            try { topPSlider.setValue(Double.parseDouble(val)); } catch (NumberFormatException ignored) {}
        });
        HBox topPRow = new HBox(10, topPSlider, topPValue);
        topPRow.setAlignment(Pos.CENTER_LEFT);

        // Max tokens
        Label maxTokensLabel = subLabel(I18n.get("setting.ai.maxTokens"));
        Spinner<Integer> maxTokensSpinner = new Spinner<>();
        maxTokensSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(64, 4096, 512, 64));
        maxTokensSpinner.getStyleClass().add("glass-field");
        maxTokensSpinner.setPrefWidth(120);
        maxTokensSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            saveAiSetting(AI_MAX_TOKENS_KEY, String.valueOf(newVal));
        });
        loadAiSetting(AI_MAX_TOKENS_KEY, val -> {
            try { maxTokensSpinner.getValueFactory().setValue(Integer.parseInt(val)); } catch (NumberFormatException ignored) {}
        });

        // System prompt
        Label sysPromptLabel = subLabel(I18n.get("setting.ai.systemPrompt"));
        TextField sysPromptField = textField(null, "You are a helpful assistant.");
        HBox.setHgrow(sysPromptField, Priority.ALWAYS);
        sysPromptField.textProperty().addListener((obs, oldVal, newVal) -> {
            saveAiSetting(AI_SYSTEM_PROMPT_KEY, newVal);
        });
        loadAiSetting(AI_SYSTEM_PROMPT_KEY, val -> sysPromptField.setText(val));

        // Assemble
        root.getChildren().addAll(
            title,
            modelStatusLabel, modelPathLabel,
            labeled(I18n.get("setting.ai.modelPath"), modelPathField),
            modelBtnRow,
            memLabel, memRow,
            paramTitle,
            labeled(null, tempRow),
            labeled(null, topPRow),
            labeled(null, maxTokensSpinner),
            labeled(null, sysPromptField)
        );

        // Initial state refresh
        refreshAiModelState(modelStatusLabel, modelPathLabel, unloadBtn);

        return root;
    }

    private static void refreshAiModelState(Label statusLabel, Label pathLabel, Button unloadBtn) {
        Optional<AiService> opt = AiServiceProvider.getService();
        if (opt.isPresent() && opt.get().isReady()) {
            AiService service = opt.get();
            statusLabel.setText(I18n.get("setting.ai.modelLoaded", service.getModelName().orElse("Unknown")));
            unloadBtn.setDisable(false);
            loadAiSetting(AI_MODEL_PATH_KEY, val -> pathLabel.setText(val));
        }
    }

    private static void loadAiSetting(String key, Consumer<String> consumer) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey(key);
            if (entity != null && entity.getSettingValue() != null && !entity.getSettingValue().isBlank()) {
                consumer.accept(entity.getSettingValue());
            }
        } catch (Exception e) {
            log.debug("Could not read AI setting: {}", key, e);
        }
    }

    private static void saveAiSetting(String key, String value) {
        new Thread(() -> {
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
                AppSettingEntity entity = mapper.selectByKey(key);
                if (entity != null) {
                    entity.setSettingValue(value);
                    mapper.update(entity);
                } else {
                    AppSettingEntity newEntity = new AppSettingEntity();
                    newEntity.setSettingKey(key);
                    newEntity.setSettingValue(value);
                    mapper.insert(newEntity);
                }
                session.commit();
            } catch (Exception e) {
                log.error("Failed to save AI setting: {}", key, e);
            }
        }).start();
    }

    /** Get the saved AI temperature, or default 0.7 */
    public static float getAiTemperature() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey(AI_TEMPERATURE_KEY);
            if (entity != null) return Float.parseFloat(entity.getSettingValue());
        } catch (Exception ignored) {}
        return 0.7f;
    }

    /** Get the saved AI top_p, or default 0.9 */
    public static float getAiTopP() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey(AI_TOP_P_KEY);
            if (entity != null) return Float.parseFloat(entity.getSettingValue());
        } catch (Exception ignored) {}
        return 0.9f;
    }

    /** Get the saved AI max tokens, or default 512 */
    public static int getAiMaxTokens() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey(AI_MAX_TOKENS_KEY);
            if (entity != null) return Integer.parseInt(entity.getSettingValue());
        } catch (Exception ignored) {}
        return 512;
    }

    /** Get the saved AI system prompt, or default */
    public static String getAiSystemPrompt() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey(AI_SYSTEM_PROMPT_KEY);
            if (entity != null && !entity.getSettingValue().isBlank()) return entity.getSettingValue();
        } catch (Exception ignored) {}
        return "You are a helpful assistant.";
    }

    // ═══════════════════════════════════════════════════════════════════
    // Email Tab
    // ═══════════════════════════════════════════════════════════════════

    private static VBox buildEmailTab() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");

        TextField smtpField = textField(null, "smtp.example.com");
        TextField portField = textField(null, "587");
        TextField userField = textField(null, "user@example.com");
        PasswordField passField = passwordField();
        TextField fromField = textField(null, "noreply@example.com");

        // Use userData for stable field identification (label text changes with locale)
        VBox smtpRow = labeled(I18n.get("setting.email.smtpServer"), smtpField);
        smtpRow.setUserData("smtp");
        VBox portRow = labeled(I18n.get("setting.email.port"), portField);
        portRow.setUserData("port");
        VBox userRow = labeled(I18n.get("setting.email.username"), userField);
        userRow.setUserData("username");
        VBox passRow = labeled(I18n.get("setting.email.password"), passField);
        passRow.setUserData("password");
        VBox fromRow = labeled(I18n.get("setting.email.fromAddress"), fromField);
        fromRow.setUserData("from");

        VBox tlsSslBox = tlsSslRow();

        root.getChildren().addAll(
            sectionTitle(I18n.get("setting.email.title")),
            smtpRow, portRow, userRow, passRow, fromRow,
            tlsSslBox,
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
                            if (child instanceof HBox hb && hb.getUserData() instanceof String key) {
                                Object fieldNode = hb.getChildren().get(1);
                                switch (key) {
                                    case "smtp" -> { if (fieldNode instanceof TextField tf) tf.setText(entity.getSmtpAddress()); }
                                    case "port" -> { if (fieldNode instanceof TextField tf) tf.setText(String.valueOf(entity.getSmtpPort())); }
                                    case "username" -> { if (fieldNode instanceof TextField tf) tf.setText(entity.getEmail()); }
                                    case "password" -> { if (fieldNode instanceof PasswordField pf) pf.setText(entity.getPassword()); }
                                    case "from" -> { if (fieldNode instanceof TextField tf) tf.setText(entity.getFromAddress()); }
                                }
                            }
                        }
                        // Update TLS/SSL checkboxes
                        if (entity.getNeedTLS() != null || entity.getNeedSSL() != null) {
                            for (Node child : root.getChildren()) {
                                if (child instanceof VBox vb) {
                                    for (Node vbChild : vb.getChildren()) {
                                        if (vbChild instanceof HBox hb && hb.getChildren().size() == 2) {
                                            Object first = hb.getChildren().get(0);
                                            Object second = hb.getChildren().get(1);
                                            if (first instanceof CheckBox tlsCb && tlsCb.getUserData() == "TLS" && entity.getNeedTLS() != null) {
                                                tlsCb.setSelected(entity.getNeedTLS());
                                            }
                                            if (second instanceof CheckBox sslCb && sslCb.getUserData() == "SSL" && entity.getNeedSSL() != null) {
                                                sslCb.setSelected(entity.getNeedSSL());
                                            }
                                        }
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
        tlsCheck.setUserData("TLS");
        tlsCheck.getStyleClass().add("glass-checkbox");

        CheckBox sslCheck = new CheckBox("SSL");
        sslCheck.setUserData("SSL");
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
        Button btn = glassBtn(I18n.get("button.save"), true);
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
            if (child instanceof HBox hb && hb.getUserData() instanceof String key && hb.getChildren().size() >= 2) {
                Object field = hb.getChildren().get(1);
                switch (key) {
                    case "smtp" -> { if (field instanceof TextField tf) smtp = tf.getText(); }
                    case "port" -> { if (field instanceof TextField tf) port = tf.getText(); }
                    case "username" -> { if (field instanceof TextField tf) user = tf.getText(); }
                    case "password" -> { if (field instanceof PasswordField pf) pass = pf.getText(); }
                    case "from" -> { if (field instanceof TextField tf) from = tf.getText(); }
                }
            } else if (child instanceof VBox vb) {
                for (Node vbChild : vb.getChildren()) {
                    if (vbChild instanceof HBox hb && hb.getChildren().size() == 2) {
                        Object first = hb.getChildren().get(0);
                        Object second = hb.getChildren().get(1);
                        if (first instanceof CheckBox tlsCb && tlsCb.getUserData() == "TLS") tls = tlsCb.isSelected();
                        if (second instanceof CheckBox sslCb && sslCb.getUserData() == "SSL") ssl = sslCb.isSelected();
                    }
                }
            }
        }

        if (smtp == null || smtp.isBlank() || port == null || port.isBlank()
                || user == null || user.isBlank() || pass == null || pass.isBlank()
                || from == null || from.isBlank()) {
            GlassNotification.notify((Window) null, GlassNotification.Type.WARNING,
                I18n.get("setting.email.allFieldsRequired"));
            return;
        }

        if (tls && ssl) {
            GlassNotification.notify((Window) null, GlassNotification.Type.WARNING,
                I18n.get("setting.email.tlsSslConflict"));
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
                Platform.runLater(() ->
                    GlassNotification.toast((Window) null, GlassNotification.Type.SUCCESS,
                        I18n.get("setting.email.saved")));
            } catch (Exception ex) {
                log.error("Failed to save email settings", ex);
                Platform.runLater(() -> GlassNotification.notify((Window) null, GlassNotification.Type.ERROR,
                    I18n.get("setting.email.failedToSave", ex.getMessage())));
            }
        }).start();
    }

    private static Button openAddressBookBtn() {
        Button btn = glassBtn(I18n.get("setting.email.addressBook"), false);
        btn.setOnAction(e -> openAddressBookDialog());
        return btn;
    }

    private static void openAddressBookDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(I18n.get("setting.email.addressBookTitle"));

        List<EmailAddressBookEntity> entities;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            entities = session.getMapper(EmailAddressBookMapper.class).selectEmailAddressBook();
            if (entities == null) entities = new ArrayList<>();
        } catch (Exception e) {
            GlassNotification.notify((Window) null, GlassNotification.Type.ERROR,
                I18n.get("setting.email.failedToSave", e.getMessage()));
            return;
        }

        TableView<EmailAddressBookEntity> table = new TableView<>(FXCollections.observableArrayList(entities));
        table.getStyleClass().add("glass-table");
        table.setPlaceholder(new Label(I18n.get("setting.email.noAddresses")));

        TableColumn<EmailAddressBookEntity, Integer> idCol = new TableColumn<>(I18n.get("setting.email.colId"));
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        idCol.setPrefWidth(60);

        TableColumn<EmailAddressBookEntity, String> addrCol = new TableColumn<>(I18n.get("setting.email.address"));
        addrCol.setCellValueFactory(new PropertyValueFactory<>("emailAddress"));
        addrCol.setPrefWidth(200);

        TableColumn<EmailAddressBookEntity, String> nickCol = new TableColumn<>(I18n.get("setting.email.nickname"));
        nickCol.setCellValueFactory(new PropertyValueFactory<>("nickname"));
        nickCol.setPrefWidth(150);

        TableColumn<EmailAddressBookEntity, String> tagsCol = new TableColumn<>(I18n.get("setting.email.tags"));
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

        Button addBtn = glassBtn(I18n.get("setting.email.addAddress"), true);
        addBtn.setOnAction(e -> {
            openAddAddressDialog(null);
            dialog.close();
        });

        Button manageTagsBtn = glassBtn(I18n.get("setting.email.manageTags"), false);
        manageTagsBtn.setOnAction(e -> openTagsDialog());

        Button closeBtn = glassBtn(I18n.get("button.close"), false);
        closeBtn.setOnAction(e -> dialog.close());

        HBox btnRow = new HBox(8, addBtn, manageTagsBtn, spacer(), closeBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, table, btnRow);
        root.setPadding(new Insets(24));
        root.getStyleClass().add("glass-dialog");
        root.setPrefSize(700, 450);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.setFill(javafx.scene.paint.Color.web("#0d0e11"));
        Themes.applyTo(scene);
        dialog.setScene(scene);
        dialog.show();
    }

    private static void openAddAddressDialog(EmailAddressBookEntity editEntity) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(editEntity == null ? I18n.get("setting.email.addAddressTitle") : I18n.get("setting.email.editAddressTitle"));

        TextField addressField = textField(null, "");
        TextField nicknameField = textField(null, "");
        TextField tagsField = new TextField();
        tagsField.setEditable(false);
        tagsField.getStyleClass().add(FIELD_STYLE_CLASS);

        ComboBox<EmailTagEntity> tagCombo = new ComboBox<>();
        tagCombo.setPromptText(I18n.get("setting.email.selectTag"));
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

        Button resetBtn = glassBtn(I18n.get("button.resetToDefault"), false);
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

        Button okBtn = glassBtn(I18n.get("button.save"), true);
        okBtn.setOnAction(e -> {
            String address = addressField.getText();
            if (address == null || address.isBlank() || !address.matches(".+@.+\\..+")) {
                GlassNotification.notify((Window) null, GlassNotification.Type.WARNING,
                    I18n.get("setting.email.validEmailRequired"));
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
                GlassNotification.notify((Window) null, GlassNotification.Type.ERROR,
                    I18n.get("setting.email.failedToSave", ex.getMessage()));
            }
        });

        Button closeBtn = glassBtn(I18n.get("button.cancel"), false);
        closeBtn.setOnAction(e -> dialog.close());

        HBox tagRow = new HBox(8, tagsField, resetBtn);
        HBox.setHgrow(tagsField, Priority.ALWAYS);

        HBox btnRow = new HBox(8, spacer(), okBtn, closeBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(14,
            labeled(I18n.get("setting.email.address"), addressField),
            labeled(I18n.get("setting.email.nickname"), nicknameField),
            labeled(I18n.get("setting.email.tags"), tagRow),
            labeled(I18n.get("setting.email.selectTag"), tagCombo),
            btnRow
        );
        root.setPadding(new Insets(24));
        root.getStyleClass().add("glass-dialog");
        root.setPrefWidth(480);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.setFill(javafx.scene.paint.Color.web("#0d0e11"));
        Themes.applyTo(scene);
        dialog.setScene(scene);
        dialog.show();
    }

    private static void openTagsDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(I18n.get("setting.email.manageTagsTitle"));

        List<EmailTagEntity> tags;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            tags = session.getMapper(EmailTagMapper.class).selectAll();
            if (tags == null) tags = new ArrayList<>();
        } catch (Exception e) {
            GlassNotification.notify((Window) null, GlassNotification.Type.ERROR,
                I18n.get("setting.email.failedToSave", e.getMessage()));
            return;
        }

        TableView<EmailTagEntity> table = new TableView<>(FXCollections.observableArrayList(tags));
        table.getStyleClass().add("glass-table");

        TableColumn<EmailTagEntity, Long> idCol = new TableColumn<>(I18n.get("setting.email.colId"));
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        idCol.setPrefWidth(80);

        TableColumn<EmailTagEntity, String> tagCol = new TableColumn<>(I18n.get("setting.email.tags"));
        tagCol.setCellValueFactory(new PropertyValueFactory<>("tag"));
        tagCol.setPrefWidth(200);

        table.getColumns().addAll(idCol, tagCol);

        AtomicReference<Long> updateIdRef = new AtomicReference<>(null);
        TextField tagField = textField(null, "");
        Button addTagBtn = glassBtn(I18n.get("setting.email.addTag"), true);

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 2) {
                EmailTagEntity selected = table.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    updateIdRef.set(selected.getId());
                    tagField.setText(selected.getTag());
                    addTagBtn.setText(I18n.get("setting.email.update"));
                }
            }
        });

        addTagBtn.setOnAction(e -> {
            String tagText = tagField.getText();
            if (tagText == null || tagText.isBlank()) return;

            Long currentUpdateId = updateIdRef.get();
            if (I18n.get("setting.email.update").equals(addTagBtn.getText()) && currentUpdateId != null) {
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

        Button closeBtn = glassBtn(I18n.get("button.close"), false);
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
        Themes.applyTo(scene);
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
}
