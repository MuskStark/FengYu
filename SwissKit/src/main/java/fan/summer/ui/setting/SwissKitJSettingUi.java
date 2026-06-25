package fan.summer.ui.setting;

import fan.summer.api.ai.AiServiceProvider;
import fan.summer.api.ai.ChatBackend;
import fan.summer.api.i18n.I18n;
import fan.summer.api.theme.Themes;
import fan.summer.ai.service.CloudChatBackend;
import fan.summer.ai.service.LocalChatBackend;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Settings UI for SwissKitJ, displayed as a multi-tab page with a sidebar.
 * Tabs include: General (language selection), Plugin Store (store URL configuration),
 * AI Model (LLM configuration and generation parameters), and Email
 * (SMTP configuration, address book management, and tag management).
 * <p>
 * All settings are persisted to the H2 database via MyBatis. The UI rebuilds
 * itself when the locale changes, preserving the active tab selection.
 *
 * @since 1.0
 */
public class SwissKitJSettingUi {

    private static final Logger log = LoggerFactory.getLogger(SwissKitJSettingUi.class);

    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("\\d+");

    /** Active nav index, preserved across locale rebuilds. */
    private static int activeNavIndex = 0;

    // ── Settings cache: avoids opening a SqlSession for every getter call ──────────
    private static final ConcurrentHashMap<String, String> settingsCache = new ConcurrentHashMap<>();
    private static volatile boolean cacheLoaded = false;

    /** Debounce executor for saveAiSetting — coalesces rapid-fire text changes. */
    private static final ScheduledExecutorService debounceExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "settings-debounce");
            t.setDaemon(true);
            return t;
        });
    private static final ConcurrentHashMap<String, ScheduledFuture<?>> pendingSaves = new ConcurrentHashMap<>();

    /**
     * Builds (or returns the cached) settings UI with a sidebar navigation.
     * The view automatically rebuilds when the locale changes, preserving
     * the last active tab.
     *
     * @return the root Node of the settings UI
     */
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
        storePage.setManaged(false);
        aiModelPage.setVisible(false);
        aiModelPage.setManaged(false);
        emailPage.setVisible(false);
        emailPage.setManaged(false);

        // ── Sidebar ──────────────────────────────────────────
        VBox sidebar = new VBox();
        sidebar.setStyle(
            "-fx-background-color: rgba(255,255,255,0.022);" +
            "-fx-border-color: rgba(255,255,255,0.10);" +
            "-fx-border-width: 0 1 0 0;" +
            "-fx-min-width: 160px; -fx-pref-width: 180px; -fx-max-width: 200px;" +
            "-fx-padding: 12 8;"
        );

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
            pages[i].setManaged(i == active);
        }

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            items[i].setOnMouseClicked(e -> {
                activeNavIndex = idx;
                for (NavItem item : items) item.setActive(false);
                for (Node page : pages) {
                    page.setVisible(false);
                    page.setManaged(false);
                }
                items[idx].setActive(true);
                pages[idx].setVisible(true);
                pages[idx].setManaged(true);
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
        saveSettingAsync("language", code, () -> Platform.runLater(() -> {
            I18n.setLocale(locale);
            GlassNotification.toast((Window) null, GlassNotification.Type.INFO,
                I18n.get("setting.general.languageChanged"));
        }));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Plugin Store Settings
    // ═══════════════════════════════════════════════════════════════════

    private static final String STORE_URL_KEY = "plugin.store.url";
    private static final String DEFAULT_STORE_URL = "https://muskstark.github.io/SwissKiJ-Plugin/store.json";

    /**
     * Returns the configured plugin store URL, checking in order:
     * &lt;ol&gt;
     *   &lt;li&gt;System property {@code store.url}&lt;/li&gt;
     *   &lt;li&gt;Database setting for {@code plugin.store.url}&lt;/li&gt;
     *   &lt;li&gt;Hard-coded default URL&lt;/li&gt;
     * &lt;/ol&gt;
     *
     * @return the active store URL string, never null
     */
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

        TextField urlField = textField( DEFAULT_STORE_URL);
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
        saveSettingAsync(STORE_URL_KEY, url, () -> Platform.runLater(() ->
            GlassNotification.toast((Window) null, GlassNotification.Type.SUCCESS,
                I18n.get("setting.store.saved"))));
    }

    // ═══════════════════════════════════════════════════════════════════
    // AI Model Tab
    // ═══════════════════════════════════════════════════════════════════

    private static final String AI_MODEL_PATH_KEY = "ai.model.path";
    private static final String AI_TEMPERATURE_KEY = "ai.temperature";
    private static final String AI_TOP_P_KEY = "ai.top_p";
    private static final String AI_MAX_TOKENS_KEY = "ai.max_tokens";
    private static final String AI_SYSTEM_PROMPT_KEY = "ai.system_prompt";
    private static final String AI_MODE_KEY = "ai.mode";
    private static final String AI_OPENAI_ENDPOINT_KEY = "ai.openai.endpoint";
    private static final String AI_OPENAI_API_KEY_KEY = "ai.openai.api_key";
    private static final String AI_OPENAI_MODEL_KEY = "ai.openai.model";
    private static final String AI_ANTHROPIC_ENDPOINT_KEY = "ai.anthropic.endpoint";
    private static final String AI_ANTHROPIC_API_KEY_KEY = "ai.anthropic.api_key";
    private static final String AI_ANTHROPIC_MODEL_KEY = "ai.anthropic.model";
    private static final String AI_LOCAL_BACKEND_KEY = "ai.local.backend";

    private static VBox buildAiModelTab() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");

        Label title = sectionTitle(I18n.get("setting.ai.title"));

        // ── Mode selector ─────────────────────────────
        Label modeLabel = subLabel(I18n.get("setting.ai.mode"));
        ComboBox<String> modeCombo = new ComboBox<>(
            FXCollections.observableArrayList(
                I18n.get("setting.ai.mode.local"),
                I18n.get("setting.ai.mode.openai"),
                I18n.get("setting.ai.mode.anthropic")
            )
        );
        modeCombo.getStyleClass().add("glass-combo");
        modeCombo.setMaxWidth(250);
        loadAiSetting(AI_MODE_KEY, val -> {
            String label = switch (val) {
                case "openai" -> I18n.get("setting.ai.mode.openai");
                case "anthropic" -> I18n.get("setting.ai.mode.anthropic");
                default -> I18n.get("setting.ai.mode.local");
            };
            modeCombo.setValue(label);
        });
        if (modeCombo.getValue() == null) modeCombo.setValue(I18n.get("setting.ai.mode.local"));

        HBox modeRow = new HBox(10, modeLabel, modeCombo);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        // ── Mode-specific panels ───────────────────────
        VBox localPanel = buildLocalModelPanel();
        VBox openaiPanel = buildOpenAiPanel();
        VBox anthropicPanel = buildAnthropicPanel();

        StackPane modeStack = new StackPane(localPanel, openaiPanel, anthropicPanel);
        modeStack.setStyle("-fx-background-color: transparent;");
        showModePanel(modeStack, modeCombo.getValue());

        // ── Backend toggle (Java / Native) — only visible in local mode ──
        HBox backendToggleRow = buildBackendToggle();
        boolean isLocalMode = "local".equals(modeLabelToKey(modeCombo.getValue()));
        backendToggleRow.setVisible(isLocalMode);
        backendToggleRow.setManaged(isLocalMode);

        modeCombo.setOnAction(e -> {
            String selected = modeCombo.getValue();
            showModePanel(modeStack, selected);
            String modeKey = modeLabelToKey(selected);
            boolean showBackend = "local".equals(modeKey);
            backendToggleRow.setVisible(showBackend);
            backendToggleRow.setManaged(showBackend);
            saveAiSetting(AI_MODE_KEY, modeKey);
            initializeAiService(modeKey);
        });

        // ── Shared generation parameters ───────────────
        Label paramTitle = sectionTitle(I18n.get("setting.ai.genParams"));

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

        Spinner<Integer> maxTokensSpinner = new Spinner<>();
        maxTokensSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(64, 4096, LocalChatBackend.QWEN3_MIN_MAX_TOKENS, 64));
        maxTokensSpinner.getStyleClass().add("glass-field");
        maxTokensSpinner.setPrefWidth(120);
        maxTokensSpinner.valueProperty().addListener((obs, oldVal, newVal) ->
            saveAiSetting(AI_MAX_TOKENS_KEY, String.valueOf(newVal)));
        loadAiSetting(AI_MAX_TOKENS_KEY, val -> {
            try { maxTokensSpinner.getValueFactory().setValue(Integer.parseInt(val)); } catch (NumberFormatException ignored) {}
        });

        TextField sysPromptField = textField( "You are a helpful assistant.");
        HBox.setHgrow(sysPromptField, Priority.ALWAYS);
        sysPromptField.textProperty().addListener((obs, oldVal, newVal) ->
            saveAiSetting(AI_SYSTEM_PROMPT_KEY, newVal));
        loadAiSetting(AI_SYSTEM_PROMPT_KEY, sysPromptField::setText);

        root.getChildren().addAll(
            title, modeRow, backendToggleRow, modeStack,
            paramTitle,
            labeled(I18n.get("setting.ai.temperature"), tempRow),
            labeled(I18n.get("setting.ai.topP"), topPRow),
            labeled(I18n.get("setting.ai.maxTokens"), maxTokensSpinner),
            labeled(I18n.get("setting.ai.systemPrompt"), sysPromptField)
        );

        return root;
    }

    private static String modeLabelToKey(String label) {
        if (label == null) return "local";
        if (label.equals(I18n.get("setting.ai.mode.openai"))) return "openai";
        if (label.equals(I18n.get("setting.ai.mode.anthropic"))) return "anthropic";
        return "local";
    }

    private static void showModePanel(StackPane stack, String modeLabel) {
        String key = modeLabelToKey(modeLabel);
        var panels = stack.getChildren();
        int idx = switch (key) {
            case "openai" -> 1;
            case "anthropic" -> 2;
            default -> 0;
        };
        for (int i = 0; i < panels.size(); i++) {
            panels.get(i).setVisible(i == idx);
            panels.get(i).setManaged(i == idx);
        }
    }

    static void initializeAiService(String mode) {
        switch (mode) {
            case "openai" -> {
                CloudChatBackend svc = CloudChatBackend.openAi(
                    getAiOpenAiEndpoint(), getAiOpenAiApiKey(), getAiOpenAiModel());
                AiServiceProvider.switchMode(mode, svc);
            }
            case "anthropic" -> {
                CloudChatBackend svc = CloudChatBackend.anthropic(
                    getAiAnthropicEndpoint(), getAiAnthropicApiKey(), getAiAnthropicModel());
                AiServiceProvider.switchMode(mode, svc);
            }
            default -> createLocalBackend(true);
        }
    }

    /**
     * Creates and registers a local AI backend (LocalChatBackend).
     *
     * @param autoLoadModel if true, auto-load the last saved model path from DB
     */
    private static void createLocalBackend(boolean autoLoadModel) {
        String backendSetting = getAiLocalBackend();
        boolean useNative = "native".equals(backendSetting);

        if (useNative) {
            fan.summer.ai.nativejni.NativeLoader.load();
            if (!fan.summer.ai.nativejni.NativeLoader.isLoaded()) {
                log.warn("Native library not available, falling back to Java engine");
                useNative = false;
            }
        }

        LocalChatBackend aiService = new LocalChatBackend(useNative);
        AiServiceProvider.switchMode("local", aiService);

        if (autoLoadModel) {
            autoLoadModel(aiService);
        }
    }

    /**
     * Ensures the local AI backend is initialized. Called lazily when the AI tool is opened.
     * No-op if already initialized. On first call, attempts native loading and auto-loads
     * the last saved model.
     */
    public static synchronized void ensureLocalBackend() {
        // Only initialize the local backend if the current mode is "local".
        // If the user has selected OpenAI or Anthropic mode, the service was
        // already set up during startup or when the mode was changed in settings.
        String mode = getCachedSetting(AI_MODE_KEY, "local");
        if (!"local".equals(mode)) {
            log.debug("ensureLocalBackend skipped — current mode is '{}'", mode);
            return;
        }

        Optional<ChatBackend> svc = AiServiceProvider.getService();
        if (svc.isPresent() && svc.get() instanceof LocalChatBackend) {
            return; // already initialized
        }
        log.info("Initializing local AI backend (lazy)");
        createLocalBackend(true);
    }

    private static void autoLoadModel(LocalChatBackend aiService) {
        String modelPath = fan.summer.ai.AiConfigService.getAiModelPath();

        if (modelPath == null || modelPath.isBlank()) {
            log.info("No local AI model path configured — skipping auto-load");
            return;
        }
        // Log loudly when the saved path no longer points at a real file. Otherwise
        // auto-load silently no-ops, the native worker never spawns, and the chat UI
        // shows the "native unavailable — using pure Java" banner even though native
        // itself is fine — a stale/mis-typed path (e.g. a stray space) is invisible.
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(modelPath))) {
            log.warn("Configured local AI model not found, skipping auto-load: {}", modelPath);
            return;
        }

        log.info("Auto-loading local AI model: {}", modelPath);
        final String finalPath = modelPath;
        Thread.ofVirtual().start(() -> {
            try {
                aiService.loadModel(java.nio.file.Path.of(finalPath));
                AiServiceProvider.notifyStateChanged();
                log.info("Local AI model auto-loaded successfully");
            } catch (Exception e) {
                log.warn("Auto-load failed: {}", e.getMessage());
            }
        });
    }

    private static VBox buildLocalModelPanel() {
        VBox panel = new VBox(12);

        Label modelStatusLabel = new Label(I18n.get("setting.ai.noModelLoaded"));
        modelStatusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 13px;");

        Label modelPathLabel = new Label("—");
        modelPathLabel.setWrapText(true);
        modelPathLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.35); -fx-font-size: 12px;");

        TextField modelPathField = textField( I18n.get("setting.ai.selectModel"));
        loadAiSetting(AI_MODEL_PATH_KEY, modelPathField::setText);

        Button browseBtn = glassBtn(I18n.get("setting.ai.browse"), false);
        Button loadBtn = glassBtn(I18n.get("setting.ai.loadModel"), true);
        Button unloadBtn = glassBtn(I18n.get("setting.ai.unload"), false);
        unloadBtn.setDisable(true);

        browseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get("setting.ai.selectModel"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("GGUF Model", "*.gguf", "*.ggufz"));
            File file = chooser.showOpenDialog(browseBtn.getScene().getWindow());
            if (file != null) {
                modelPathField.setText(file.getAbsolutePath());
                saveAiSetting(AI_MODEL_PATH_KEY, file.getAbsolutePath());
            }
        });

        loadBtn.setOnAction(e -> {
            String path = modelPathField.getText();
            if (path == null || path.isBlank()) {
                GlassNotification.notify((Window) null, GlassNotification.Type.WARNING, I18n.get("setting.ai.selectModelFile"));
                return;
            }
            loadBtn.setDisable(true);
            modelStatusLabel.setText(I18n.get("setting.ai.loadingModel"));
            saveAiSetting(AI_MODEL_PATH_KEY, path.trim());

            Thread.ofVirtual().start(() -> {
                try {
                    Optional<ChatBackend> opt = AiServiceProvider.getService();
                    if (opt.isEmpty()) {
                        Platform.runLater(() -> {
                            modelStatusLabel.setText(I18n.get("setting.ai.aiServiceError"));
                            loadBtn.setDisable(false);
                        });
                        return;
                    }
                    ChatBackend service = opt.get();
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
            Optional<ChatBackend> opt = AiServiceProvider.getService();
            opt.ifPresent(ChatBackend::unloadModel);
            modelStatusLabel.setText(I18n.get("setting.ai.noModelLoaded"));
            modelPathLabel.setText("—");
            unloadBtn.setDisable(true);
            AiServiceProvider.notifyStateChanged();
        });

        HBox modelBtnRow = new HBox(8, browseBtn, loadBtn, unloadBtn);
        modelBtnRow.setAlignment(Pos.CENTER_LEFT);

        ProgressBar memBar = new ProgressBar(0);
        memBar.setPrefWidth(300);
        Label memText = new Label("—");
        memText.setStyle("-fx-text-fill: rgba(255,255,255,0.35); -fx-font-size: 11px;");
        HBox memRow = new HBox(10, memBar, memText);
        memRow.setAlignment(Pos.CENTER_LEFT);

        panel.getChildren().addAll(
            modelStatusLabel, modelPathLabel,
            labeled(I18n.get("setting.ai.modelPath"), modelPathField),
            modelBtnRow,
            labeled(I18n.get("setting.ai.memoryUsage"), memRow)
        );

        refreshAiModelState(modelStatusLabel, modelPathLabel, unloadBtn);
        return panel;
    }

    private static HBox buildBackendToggle() {
        Label label = subLabel(I18n.get("setting.ai.backend"));

        Button javaBtn = new Button(I18n.get("setting.ai.backend.java"));
        Button nativeBtn = new Button(I18n.get("setting.ai.backend.native"));

        // Segmented control: left button rounded on left, right button rounded on right
        String baseStyle = "-fx-font-size: 12px; -fx-padding: 5 14 5 14; -fx-cursor: hand;";
        javaBtn.setStyle(baseStyle + "-fx-background-radius: 4 0 0 4; -fx-border-radius: 4 0 0 4;");
        nativeBtn.setStyle(baseStyle + "-fx-background-radius: 0 4 4 0; -fx-border-radius: 0 4 4 0;");

        Runnable updateStyle = () -> {
            String current = getAiLocalBackend();
            boolean isJava = "java".equals(current);
            javaBtn.getStyleClass().setAll(isJava ? "glass-btn-primary" : "glass-btn-secondary");
            nativeBtn.getStyleClass().setAll(isJava ? "glass-btn-secondary" : "glass-btn-primary");
            // Re-apply shape styles after class change (stylesheet may override)
            javaBtn.setStyle(baseStyle + "-fx-background-radius: 4 0 0 4; -fx-border-radius: 4 0 0 4;");
            nativeBtn.setStyle(baseStyle + "-fx-background-radius: 0 4 4 0; -fx-border-radius: 0 4 4 0;");
        };
        updateStyle.run();

        javaBtn.setOnAction(e -> {
            saveAiSetting(AI_LOCAL_BACKEND_KEY, "java");
            updateStyle.run();
            initializeAiService("local");
        });

        nativeBtn.setOnAction(e -> {
            saveAiSetting(AI_LOCAL_BACKEND_KEY, "native");
            updateStyle.run();
            initializeAiService("local");
        });

        HBox toggle = new HBox(javaBtn, nativeBtn);
        toggle.setAlignment(Pos.CENTER_LEFT);

        HBox row = new HBox(10, label, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static VBox buildOpenAiPanel() {
        VBox panel = new VBox(12);

        TextField endpointField = textField( "https://api.openai.com");
        loadAiSetting(AI_OPENAI_ENDPOINT_KEY, endpointField::setText);

        PasswordField apiKeyField = new PasswordField();
        apiKeyField.getStyleClass().add(FIELD_STYLE_CLASS);
        loadAiSetting(AI_OPENAI_API_KEY_KEY, apiKeyField::setText);

        TextField modelField = textField( "gpt-4o");
        loadAiSetting(AI_OPENAI_MODEL_KEY, modelField::setText);

        endpointField.textProperty().addListener((obs, o, n) -> saveAiSetting(AI_OPENAI_ENDPOINT_KEY, n));
        apiKeyField.textProperty().addListener((obs, o, n) -> saveAiSetting(AI_OPENAI_API_KEY_KEY, n));
        modelField.textProperty().addListener((obs, o, n) -> saveAiSetting(AI_OPENAI_MODEL_KEY, n));

        Label statusLabel = new Label("");
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

        Button testBtn = glassBtn(I18n.get("setting.ai.testConnection"), false);
        testBtn.setOnAction(e -> {
            testBtn.setDisable(true);
            Thread.ofVirtual().start(() -> {
                CloudChatBackend svc = CloudChatBackend.openAi(
                    endpointField.getText(), apiKeyField.getText(), modelField.getText());
                String err = svc.testConnection();
                Platform.runLater(() -> {
                    if (err == null) {
                        statusLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
                        statusLabel.setText(I18n.get("setting.ai.testSuccess"));
                    } else {
                        statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
                        statusLabel.setText(I18n.get("setting.ai.testFailed", err));
                    }
                    testBtn.setDisable(false);
                });
            });
        });

        panel.getChildren().addAll(
            labeled(I18n.get("setting.ai.endpoint"), endpointField),
            labeled(I18n.get("setting.ai.apiKey"), apiKeyField),
            labeled(I18n.get("setting.ai.modelName"), modelField),
            testBtn, statusLabel
        );
        return panel;
    }

    private static VBox buildAnthropicPanel() {
        VBox panel = new VBox(12);

        TextField endpointField = textField( "https://api.anthropic.com");
        loadAiSetting(AI_ANTHROPIC_ENDPOINT_KEY, endpointField::setText);

        PasswordField apiKeyField = new PasswordField();
        apiKeyField.getStyleClass().add(FIELD_STYLE_CLASS);
        loadAiSetting(AI_ANTHROPIC_API_KEY_KEY, apiKeyField::setText);

        TextField modelField = textField( "claude-sonnet-4-20250514");
        loadAiSetting(AI_ANTHROPIC_MODEL_KEY, modelField::setText);

        endpointField.textProperty().addListener((obs, o, n) -> saveAiSetting(AI_ANTHROPIC_ENDPOINT_KEY, n));
        apiKeyField.textProperty().addListener((obs, o, n) -> saveAiSetting(AI_ANTHROPIC_API_KEY_KEY, n));
        modelField.textProperty().addListener((obs, o, n) -> saveAiSetting(AI_ANTHROPIC_MODEL_KEY, n));

        Label statusLabel = new Label("");
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

        Button testBtn = glassBtn(I18n.get("setting.ai.testConnection"), false);
        testBtn.setOnAction(e -> {
            testBtn.setDisable(true);
            Thread.ofVirtual().start(() -> {
                CloudChatBackend svc = CloudChatBackend.anthropic(
                    endpointField.getText(), apiKeyField.getText(), modelField.getText());
                String err = svc.testConnection();
                Platform.runLater(() -> {
                    if (err == null) {
                        statusLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
                        statusLabel.setText(I18n.get("setting.ai.testSuccess"));
                    } else {
                        statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
                        statusLabel.setText(I18n.get("setting.ai.testFailed", err));
                    }
                    testBtn.setDisable(false);
                });
            });
        });

        panel.getChildren().addAll(
            labeled(I18n.get("setting.ai.endpoint"), endpointField),
            labeled(I18n.get("setting.ai.apiKey"), apiKeyField),
            labeled(I18n.get("setting.ai.modelName"), modelField),
            testBtn, statusLabel
        );
        return panel;
    }

    private static void refreshAiModelState(Label statusLabel, Label pathLabel, Button unloadBtn) {
        Optional<ChatBackend> opt = AiServiceProvider.getService();
        if (opt.isPresent() && opt.get().isReady()) {
            ChatBackend service = opt.get();
            statusLabel.setText(I18n.get("setting.ai.modelLoaded", service.getModelName().orElse("Unknown")));
            unloadBtn.setDisable(false);
            loadAiSetting(AI_MODEL_PATH_KEY, pathLabel::setText);
        }
    }

    private static void saveSettingAsync(String key, String value, Runnable onSuccess) {
        // Update cache immediately so subsequent getters see the new value
        settingsCache.put(key, value);
        Thread.ofVirtual().name("settings-save").start(() -> {
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
                if (onSuccess != null) onSuccess.run();
            } catch (Exception e) {
                log.error("Failed to save setting: {}", key, e);
            }
        });
    }

    private static void loadAiSetting(String key, Consumer<String> consumer) {
        ensureCacheLoaded();
        String cached = settingsCache.get(key);
        if (cached != null && !cached.isBlank()) {
            consumer.accept(cached);
            return;
        }
        // Fallback: load from DB and populate cache
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey(key);
            if (entity != null && entity.getSettingValue() != null && !entity.getSettingValue().isBlank()) {
                settingsCache.put(key, entity.getSettingValue());
                consumer.accept(entity.getSettingValue());
            }
        } catch (Exception e) {
            log.debug("Could not read AI setting: {}", key, e);
        }
    }

    /**
     * Debounced save: coalesces rapid-fire changes (e.g. from Slider/TextField listeners)
     * into a single DB write, 300 ms after the last change.
     */
    private static void saveAiSetting(String key, String value) {
        // Update cache immediately
        settingsCache.put(key, value);
        // Cancel any pending save for this key, schedule a new one
        ScheduledFuture<?> prev = pendingSaves.remove(key);
        if (prev != null) prev.cancel(false);
        ScheduledFuture<?> future = debounceExecutor.schedule(() -> {
            pendingSaves.remove(key);
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
                log.error("Failed to save setting (debounced): {}", key, e);
            }
        }, 300, TimeUnit.MILLISECONDS);
        pendingSaves.put(key, future);
    }

    /**
     * Reads a setting from cache, falling back to DB on cache miss.
     * Populates the cache on DB hit.
     */
    private static String getCachedSetting(String key, String defaultValue) {
        ensureCacheLoaded();
        String cached = settingsCache.get(key);
        if (cached != null) return cached;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey(key);
            if (entity != null && entity.getSettingValue() != null) {
                settingsCache.put(key, entity.getSettingValue());
                return entity.getSettingValue();
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    /** Eagerly loads all app_settings rows into the in-memory cache. */
    private static synchronized void ensureCacheLoaded() {
        if (cacheLoaded) return;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            List<AppSettingEntity> all = mapper.selectAll();
            if (all != null) {
                for (AppSettingEntity e : all) {
                    if (e.getSettingKey() != null && e.getSettingValue() != null) {
                        settingsCache.put(e.getSettingKey(), e.getSettingValue());
                    }
                }
            }
            cacheLoaded = true;
            log.debug("Settings cache loaded: {} entries", settingsCache.size());
        } catch (Exception e) {
            // Cache load failure is non-fatal; individual getters will fall back to DB
            log.debug("Could not preload settings cache", e);
            cacheLoaded = true;
        }
    }

    /**
     * Returns the saved AI temperature value, or 0.7 if not set or invalid.
     *
     * @return the temperature value between 0 and 2
     */
    public static float getAiTemperature() {
        String val = getCachedSetting(AI_TEMPERATURE_KEY, null);
        if (val != null) {
            try { return Float.parseFloat(val); } catch (NumberFormatException ignored) {}
        }
        return 0.7f;
    }

    /**
     * Returns the saved AI top-p value, or 0.9 if not set or invalid.
     *
     * @return the top-p value between 0 and 1
     */
    public static float getAiTopP() {
        String val = getCachedSetting(AI_TOP_P_KEY, null);
        if (val != null) {
            try { return Float.parseFloat(val); } catch (NumberFormatException ignored) {}
        }
        return 0.9f;
    }

    /**
     * Returns the saved AI max tokens value, or {@link LocalChatBackend#QWEN3_MIN_MAX_TOKENS}
     * if not set or invalid — the default tracks the Qwen3 thinking-model floor so a
     * fresh install never truncates a thinking model mid-{@code <think>}.
     *
     * @return the max tokens value between 64 and 4096
     */
    public static int getAiMaxTokens() {
        String val = getCachedSetting(AI_MAX_TOKENS_KEY, null);
        if (val != null) {
            try { return Integer.parseInt(val); } catch (NumberFormatException ignored) {}
        }
        return LocalChatBackend.QWEN3_MIN_MAX_TOKENS;
    }

    /**
     * Returns the saved AI system prompt, or "You are a helpful assistant." if not set.
     *
     * @return the system prompt string
     */
    public static String getAiSystemPrompt() {
        String val = getCachedSetting(AI_SYSTEM_PROMPT_KEY, null);
        return (val != null && !val.isBlank()) ? val : "You are a helpful assistant.";
    }

    public static String getAiOpenAiEndpoint() {
        String val = getCachedSetting(AI_OPENAI_ENDPOINT_KEY, null);
        return (val != null && !val.isBlank()) ? val : "https://api.openai.com";
    }

    public static String getAiOpenAiApiKey() {
        return getCachedSetting(AI_OPENAI_API_KEY_KEY, "");
    }

    public static String getAiOpenAiModel() {
        String val = getCachedSetting(AI_OPENAI_MODEL_KEY, null);
        return (val != null && !val.isBlank()) ? val : "gpt-4o";
    }

    public static String getAiAnthropicEndpoint() {
        String val = getCachedSetting(AI_ANTHROPIC_ENDPOINT_KEY, null);
        return (val != null && !val.isBlank()) ? val : "https://api.anthropic.com";
    }

    public static String getAiAnthropicApiKey() {
        return getCachedSetting(AI_ANTHROPIC_API_KEY_KEY, "");
    }

    public static String getAiAnthropicModel() {
        String val = getCachedSetting(AI_ANTHROPIC_MODEL_KEY, null);
        return (val != null && !val.isBlank()) ? val : "claude-sonnet-4-20250514";
    }

    /**
     * Returns the saved local AI backend choice, or "java" if not set.
     *
     * @return "java" or "native"
     */
    public static String getAiLocalBackend() {
        return getCachedSetting(AI_LOCAL_BACKEND_KEY, "java");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Email Tab
    // ═══════════════════════════════════════════════════════════════════

    private static VBox buildEmailTab() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");

        TextField smtpField = textField( "smtp.example.com");
        TextField portField = textField( "587");
        TextField userField = textField( "user@example.com");
        PasswordField passField = passwordField();
        TextField fromField = textField( "noreply@example.com");

        TextField imapField = textField( "imap.example.com");
        TextField imapPortField = textField( "993");

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

        VBox imapRow = labeled(I18n.get("setting.email.imapServer"), imapField);
        imapRow.setUserData("imap");
        VBox imapPortRow = labeled(I18n.get("setting.email.imapPort"), imapPortField);
        imapPortRow.setUserData("imapPort");

        CheckBox imapSslCheck = new CheckBox("SSL");
        imapSslCheck.setUserData("IMAP_SSL");
        imapSslCheck.getStyleClass().add("glass-checkbox");
        imapSslCheck.setSelected(true);
        HBox imapSslRow = new HBox(16, imapSslCheck);
        imapSslRow.setAlignment(Pos.CENTER_LEFT);
        VBox imapSslBox = new VBox(4);
        Label imapSslLabel = new Label("");
        imapSslLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.50); -fx-font-size: 11px; -fx-font-weight: bold;");
        imapSslBox.getChildren().addAll(imapSslLabel, imapSslRow);
        imapSslBox.setUserData("imapSslBox");

        root.getChildren().addAll(
            sectionTitle(I18n.get("setting.email.title")),
            smtpRow, portRow, userRow, passRow, fromRow,
            tlsSslBox,
            sectionTitle(I18n.get("setting.email.imapSection")),
            imapRow, imapPortRow, imapSslBox,
            saveEmailBtn(),
            openAddressBookBtn()
        );

        // Load existing email settings
        loadEmailSettings(root);

        return root;
    }

    private static void loadEmailSettings(VBox root) {
        runAsync(() -> {
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                SwissKitSettingEmailMapper mapper = session.getMapper(SwissKitSettingEmailMapper.class);
                SwissKitSettingEmailEntity entity = mapper.selectLatest();
                if (entity != null) {
                    Platform.runLater(() -> {
                        for (Node child : root.getChildren()) {
                            if (child instanceof VBox vb && vb.getUserData() instanceof String key) {
                                Object fieldNode = vb.getChildren().get(1);
                                switch (key) {
                                    case "smtp" -> { if (fieldNode instanceof TextField tf) tf.setText(entity.getSmtpAddress()); }
                                    case "port" -> { if (fieldNode instanceof TextField tf) tf.setText(String.valueOf(entity.getSmtpPort())); }
                                    case "username" -> { if (fieldNode instanceof TextField tf) tf.setText(entity.getEmail()); }
                                    case "password" -> { if (fieldNode instanceof PasswordField pf) pf.setText(entity.getPassword()); }
                                    case "from" -> { if (fieldNode instanceof TextField tf) tf.setText(entity.getFromAddress()); }
                                    case "imap" -> { if (fieldNode instanceof TextField tf) tf.setText(entity.getImapAddress() != null ? entity.getImapAddress() : ""); }
                                    case "imapPort" -> { if (fieldNode instanceof TextField tf) tf.setText(entity.getImapPort() != null ? String.valueOf(entity.getImapPort()) : "993"); }
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
                                            if (first instanceof CheckBox tlsCb && "TLS".equals(tlsCb.getUserData()) && entity.getNeedTLS() != null) {
                                                tlsCb.setSelected(entity.getNeedTLS());
                                            }
                                            if (second instanceof CheckBox sslCb && "SSL".equals(sslCb.getUserData()) && entity.getNeedSSL() != null) {
                                                sslCb.setSelected(entity.getNeedSSL());
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // Update IMAP SSL checkbox
                        for (Node child : root.getChildren()) {
                            if (child instanceof VBox vb && "imapSslBox".equals(vb.getUserData())) {
                                for (Node vbChild : vb.getChildren()) {
                                    if (vbChild instanceof HBox hb) {
                                        for (Node n : hb.getChildren()) {
                                            if (n instanceof CheckBox cb && "IMAP_SSL".equals(cb.getUserData()) && entity.getImapSSL() != null) {
                                                cb.setSelected(entity.getImapSSL());
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
        });
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
        String imap = null, imapPort = null;
        boolean tls = false, ssl = false, imapSsl = true;
        List<Node> children = form.getChildren();

        for (Node child : children) {
            if (child instanceof VBox vb && vb.getUserData() instanceof String key && vb.getChildren().size() >= 2) {
                Object field = vb.getChildren().get(1);
                switch (key) {
                    case "smtp" -> { if (field instanceof TextField tf) smtp = tf.getText(); }
                    case "port" -> { if (field instanceof TextField tf) port = tf.getText(); }
                    case "username" -> { if (field instanceof TextField tf) user = tf.getText(); }
                    case "password" -> { if (field instanceof PasswordField pf) pass = pf.getText(); }
                    case "from" -> { if (field instanceof TextField tf) from = tf.getText(); }
                    case "imap" -> { if (field instanceof TextField tf) imap = tf.getText(); }
                    case "imapPort" -> { if (field instanceof TextField tf) imapPort = tf.getText(); }
                }
            } else if (child instanceof VBox vb) {
                for (Node vbChild : vb.getChildren()) {
                    if (vbChild instanceof HBox hb) {
                        for (Node n : hb.getChildren()) {
                            if (n instanceof CheckBox cb && cb.getUserData() instanceof String ud) {
                                if ("TLS".equals(ud)) tls = cb.isSelected();
                                if ("SSL".equals(ud)) ssl = cb.isSelected();
                                if ("IMAP_SSL".equals(ud)) imapSsl = cb.isSelected();
                            }
                        }
                    }
                }
            }
        }

        List<String> missing = new ArrayList<>();
        if (smtp == null || smtp.isBlank()) missing.add(I18n.get("setting.email.smtpServer"));
        if (port == null || port.isBlank()) missing.add(I18n.get("setting.email.port"));
        if (user == null || user.isBlank()) missing.add(I18n.get("setting.email.username"));
        if (pass == null || pass.isBlank()) missing.add(I18n.get("setting.email.password"));
        if (from == null || from.isBlank()) missing.add(I18n.get("setting.email.fromAddress"));
        if (!missing.isEmpty()) {
            GlassNotification.notify((Window) null, GlassNotification.Type.WARNING,
                I18n.get("setting.email.missingFields", String.join(", ", missing)));
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
        final String fImap = (imap != null && !imap.isBlank()) ? imap.trim() : null;
        int parsedImapPort = 993;
        if (imapPort != null && !imapPort.isBlank()) {
            try {
                parsedImapPort = Integer.parseInt(imapPort.trim());
                if (parsedImapPort < 1 || parsedImapPort > 65535) parsedImapPort = 993;
            } catch (NumberFormatException ignored) {}
        }
        final int fImapPort = parsedImapPort;
        final boolean fImapSsl = imapSsl;

        runAsync(() -> {
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
                entity.setImapAddress(fImap);
                entity.setImapPort(fImapPort);
                entity.setImapSSL(fImapSsl);
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
        });
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
        List<EmailTagEntity> allTags;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            entities = session.getMapper(EmailAddressBookMapper.class).selectEmailAddressBook();
            if (entities == null) entities = new ArrayList<>();
            allTags = session.getMapper(EmailTagMapper.class).selectAll();
            if (allTags == null) allTags = new ArrayList<>();
        } catch (Exception e) {
            GlassNotification.notify((Window) null, GlassNotification.Type.ERROR,
                I18n.get("setting.email.failedToSave", e.getMessage()));
            return;
        }

        // Build tag ID → name map for chip display
        Map<Long, String> tagNameMap = new HashMap<>();
        for (EmailTagEntity t : allTags) tagNameMap.put(t.getId(), t.getTag());

        TableView<EmailAddressBookEntity> table = new TableView<>(FXCollections.observableArrayList(entities));
        table.getStyleClass().add("glass-table");
        table.setPlaceholder(new Label(I18n.get("setting.email.noAddresses")));

        TableColumn<EmailAddressBookEntity, Integer> idCol = new TableColumn<>(I18n.get("setting.email.colId"));
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        idCol.setPrefWidth(50);

        TableColumn<EmailAddressBookEntity, String> addrCol = new TableColumn<>(I18n.get("setting.email.address"));
        addrCol.setCellValueFactory(new PropertyValueFactory<>("emailAddress"));
        addrCol.setPrefWidth(200);

        TableColumn<EmailAddressBookEntity, String> nickCol = new TableColumn<>(I18n.get("setting.email.nickname"));
        nickCol.setCellValueFactory(new PropertyValueFactory<>("nickname"));
        nickCol.setPrefWidth(120);

        // Tag chips column: resolve IDs to readable names
        TableColumn<EmailAddressBookEntity, String> tagsCol = new TableColumn<>(I18n.get("setting.email.tags"));
        tagsCol.setPrefWidth(200);
        tagsCol.setCellValueFactory(cellData -> {
            String tagsJson = cellData.getValue().getTags();
            if (tagsJson == null || tagsJson.isBlank()) return new javafx.beans.property.SimpleStringProperty("—");
            java.util.regex.Matcher m = NUMERIC_ID_PATTERN.matcher(tagsJson);
            List<String> names = new ArrayList<>();
            while (m.find()) {
                try {
                    long id = Long.parseLong(m.group());
                    String name = tagNameMap.get(id);
                    if (name != null) names.add(name);
                } catch (NumberFormatException ignored) {}
            }
            return new javafx.beans.property.SimpleStringProperty(names.isEmpty() ? "—" : String.join(", ", names));
        });

        // Delete action column
        TableColumn<EmailAddressBookEntity, Void> actionCol = new TableColumn<>("");
        actionCol.setPrefWidth(70);
        actionCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            private final Button delBtn = new Button(I18n.get("button.delete"));
            { delBtn.getStyleClass().add("glass-btn-secondary"); delBtn.setStyle("-fx-padding: 4 10 4 10; -fx-font-size: 11px;"); }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                EmailAddressBookEntity addr = getTableView().getItems().get(getIndex());
                delBtn.setOnAction(e -> runAsync(() -> {
                    try (SqlSession session = DatabaseInit.getSqlSession()) {
                        session.getMapper(EmailAddressBookMapper.class).deleteById(addr.getId());
                        session.commit();
                        Platform.runLater(() -> { dialog.close(); openAddressBookDialog(); });
                    } catch (Exception ex) {
                        log.error("Failed to delete address", ex);
                    }
                }));
                setGraphic(delBtn);
            }
        });

        table.getColumns().addAll(idCol, addrCol, nickCol, tagsCol, actionCol);
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

        Button importBtn = glassBtn(I18n.get("setting.email.importExcel"), false);
        importBtn.setOnAction(e -> {
            dialog.close();
            openImportExcelDialog();
        });

        Button manageTagsBtn = glassBtn(I18n.get("setting.email.manageTags"), false);
        manageTagsBtn.setOnAction(e -> openTagsDialog());

        Button closeBtn = glassBtn(I18n.get("button.close"), false);
        closeBtn.setOnAction(e -> dialog.close());

        HBox btnRow = new HBox(8, addBtn, importBtn, manageTagsBtn, spacer(), closeBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, table, btnRow);
        root.setPadding(new Insets(24));
        root.getStyleClass().add("glass-dialog");
        root.setPrefSize(750, 480);

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

        TextField addressField = textField( "");
        TextField nicknameField = textField( "");

        // Load all tags and build checkbox list
        List<EmailTagEntity> allTags = new ArrayList<>();
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            List<EmailTagEntity> loaded = session.getMapper(EmailTagMapper.class).selectAll();
            if (loaded != null) allTags = loaded;
        } catch (Exception e) {
            log.debug("Could not load tags", e);
        }

        // Parse existing tag IDs from edit entity
        List<Long> preSelectedTagIds = new ArrayList<>();
        if (editEntity != null) {
            addressField.setText(editEntity.getEmailAddress());
            nicknameField.setText(editEntity.getNickname());
            if (editEntity.getTags() != null && !editEntity.getTags().isBlank()) {
                java.util.regex.Matcher m = NUMERIC_ID_PATTERN.matcher(editEntity.getTags());
                while (m.find()) {
                    try { preSelectedTagIds.add(Long.parseLong(m.group())); } catch (NumberFormatException ignored) {}
                }
            }
        }

        // Build checkboxes for each tag
        VBox tagCheckBoxes = new VBox(6);
        tagCheckBoxes.setPadding(new Insets(8));
        tagCheckBoxes.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 8;");
        for (EmailTagEntity tag : allTags) {
            CheckBox cb = new CheckBox(tag.getTag());
            cb.setUserData(tag.getId());
            cb.getStyleClass().add("glass-checkbox");
            cb.setSelected(preSelectedTagIds.contains(tag.getId()));
            tagCheckBoxes.getChildren().add(cb);
        }
        if (allTags.isEmpty()) {
            tagCheckBoxes.getChildren().add(new Label(I18n.get("setting.email.noTagsHint")));
        }

        Button okBtn = glassBtn(I18n.get("button.save"), true);
        okBtn.setOnAction(e -> {
            String address = addressField.getText();
            if (address == null || address.isBlank() || !address.matches(".+@.+\\..+")) {
                GlassNotification.notify((Window) null, GlassNotification.Type.WARNING,
                    I18n.get("setting.email.validEmailRequired"));
                return;
            }

            // Collect selected tag IDs from checkboxes
            List<String> selectedIds = new ArrayList<>();
            for (Node node : tagCheckBoxes.getChildren()) {
                if (node instanceof CheckBox cb && cb.isSelected() && cb.getUserData() instanceof Long id) {
                    selectedIds.add(String.valueOf(id));
                }
            }

            try (SqlSession session = DatabaseInit.getSqlSession()) {
                EmailAddressBookMapper mapper = session.getMapper(EmailAddressBookMapper.class);
                EmailAddressBookEntity entity = new EmailAddressBookEntity();
                if (editEntity != null) entity.setId(editEntity.getId());
                entity.setEmailAddress(address.trim());
                entity.setNickname(nicknameField.getText() != null ? nicknameField.getText().trim() : "");
                entity.setTags(selectedIds.isEmpty() ? null : "[" + String.join(",", selectedIds.stream().map(s -> "\"" + s + "\"").toList()) + "]");
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

        HBox btnRow = new HBox(8, spacer(), okBtn, closeBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(14,
            labeled(I18n.get("setting.email.address"), addressField),
            labeled(I18n.get("setting.email.nickname"), nicknameField),
            labeled(I18n.get("setting.email.selectTag"), tagCheckBoxes),
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
        List<EmailAddressBookEntity> allAddresses;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            tags = session.getMapper(EmailTagMapper.class).selectAll();
            if (tags == null) tags = new ArrayList<>();
            allAddresses = session.getMapper(EmailAddressBookMapper.class).selectEmailAddressBook();
            if (allAddresses == null) allAddresses = new ArrayList<>();
        } catch (Exception e) {
            GlassNotification.notify((Window) null, GlassNotification.Type.ERROR,
                I18n.get("setting.email.failedToSave", e.getMessage()));
            return;
        }

        // Build tag ID → contact count map
        List<EmailAddressBookEntity> finalAllAddresses = allAddresses;
        Map<Long, Long> tagContactCount = new HashMap<>();
        for (EmailTagEntity tag : tags) {
            String tagIdStr = String.valueOf(tag.getId());
            // Match tag ID with word-boundary awareness in the JSON-like tags field
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?:^|[,\\[\"\\s])" + java.util.regex.Pattern.quote(tagIdStr) + "(?:[,\\]\"\\s]|$)");
            long count = finalAllAddresses.stream()
                .filter(a -> a.getTags() != null && p.matcher(a.getTags()).find())
                .count();
            tagContactCount.put(tag.getId(), count);
        }

        TableView<EmailTagEntity> table = new TableView<>(FXCollections.observableArrayList(tags));
        table.getStyleClass().add("glass-table");

        TableColumn<EmailTagEntity, Long> idCol = new TableColumn<>(I18n.get("setting.email.colId"));
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        idCol.setPrefWidth(60);

        TableColumn<EmailTagEntity, String> tagCol = new TableColumn<>(I18n.get("setting.email.tagName"));
        tagCol.setCellValueFactory(new PropertyValueFactory<>("tag"));
        tagCol.setPrefWidth(160);

        TableColumn<EmailTagEntity, String> countCol = new TableColumn<>(I18n.get("setting.email.contactCount"));
        countCol.setPrefWidth(100);
        countCol.setCellValueFactory(cellData -> {
            Long count = tagContactCount.getOrDefault(cellData.getValue().getId(), 0L);
            return new javafx.beans.property.SimpleStringProperty(String.valueOf(count));
        });

        TableColumn<EmailTagEntity, Void> actionCol = new TableColumn<>("");
        actionCol.setPrefWidth(80);
        actionCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            private final Button delBtn = new Button(I18n.get("button.delete"));
            {
                delBtn.getStyleClass().add("glass-btn-secondary");
                delBtn.setStyle("-fx-padding: 4 10 4 10; -fx-font-size: 11px;");
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                EmailTagEntity tag = getTableView().getItems().get(getIndex());
                delBtn.setOnAction(e -> {
                    long count = tagContactCount.getOrDefault(tag.getId(), 0L);
                    if (count > 0) {
                        GlassNotification.notify(dialog, GlassNotification.Type.WARNING,
                            I18n.get("setting.email.tagInUse", tag.getTag(), count));
                        return;
                    }
                    runAsync(() -> {
                        try (SqlSession session = DatabaseInit.getSqlSession()) {
                            session.getMapper(EmailTagMapper.class).deleteById(tag.getId());
                            session.commit();
                            Platform.runLater(() -> { dialog.close(); openTagsDialog(); });
                        } catch (Exception ex) {
                            log.error("Failed to delete tag", ex);
                            Platform.runLater(() -> GlassNotification.notify(dialog, GlassNotification.Type.ERROR,
                                I18n.get("setting.email.failedToSave", ex.getMessage())));
                        }
                    });
                });
                setGraphic(delBtn);
            }
        });

        table.getColumns().addAll(idCol, tagCol, countCol, actionCol);

        AtomicReference<Long> updateIdRef = new AtomicReference<>(null);
        TextField tagField = textField( "");
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
                runAsync(() -> {
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
                });
            } else {
                runAsync(() -> {
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
                });
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
        root.setPrefSize(480, 420);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.setFill(javafx.scene.paint.Color.web("#0d0e11"));
        Themes.applyTo(scene);
        dialog.setScene(scene);
        dialog.show();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Excel import
    // ═══════════════════════════════════════════════════════════════════

    private static void openImportExcelDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(I18n.get("setting.email.importExcel"));

        Label desc = new Label(I18n.get("setting.email.importDesc"));
        desc.setWrapText(true);
        desc.setMaxWidth(Double.MAX_VALUE);
        desc.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

        // Template download button
        Button templateBtn = glassBtn(I18n.get("setting.email.downloadTemplate"), false);

        // File selection
        TextField fileField = new TextField();
        fileField.setEditable(false);
        fileField.setPromptText(I18n.get("setting.email.selectExcelFile"));
        fileField.getStyleClass().add(FIELD_STYLE_CLASS);
        HBox.setHgrow(fileField, Priority.ALWAYS);

        Button browseBtn = glassBtn(I18n.get("button.browse"), false);
        AtomicReference<File> selectedFile = new AtomicReference<>(null);
        browseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(I18n.get("setting.email.selectExcelFile"));
            fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Excel Files", "*.xlsx", "*.xls"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
            );
            File f = fc.showOpenDialog(dialog);
            if (f != null) {
                selectedFile.set(f);
                fileField.setText(f.getName());
            }
        });

        templateBtn.setOnAction(e -> {
            FileChooser saveFc = new FileChooser();
            saveFc.setTitle(I18n.get("setting.email.saveTemplate"));
            saveFc.setInitialFileName("address_book_template.xlsx");
            saveFc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel Files", "*.xlsx")
            );
            File target = saveFc.showSaveDialog(dialog);
            if (target != null) downloadImportTemplate(target);
        });

        HBox fileRow = new HBox(8, fileField, browseBtn);

        // Import button + progress area
        Button importBtn = glassBtn(I18n.get("setting.email.importExcel"), true);
        importBtn.setDisable(true);

        Label statusLabel = new Label("");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);

        fileField.textProperty().addListener((obs, o, n) -> importBtn.setDisable(n == null || n.isBlank()));

        importBtn.setOnAction(e -> {
            File file = selectedFile.get();
            if (file == null) return;
            importBtn.setDisable(true);
            browseBtn.setDisable(true);
            progressBar.setVisible(true);
            progressBar.setManaged(true);
            progressBar.setProgress(-1);
            statusLabel.setText(I18n.get("setting.email.importing"));
            statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

            runAsync(() -> {
                try {
                    ImportResult result = doImportFromExcel(file);
                    Platform.runLater(() -> {
                        progressBar.setProgress(1.0);
                        boolean allOk = result.failed == 0 && result.skipped == 0;
                        String msg = I18n.get("setting.email.importResultDetail",
                            result.imported, result.failed, result.skipped, result.tagsCreated);
                        statusLabel.setText(msg);
                        statusLabel.setStyle(allOk
                            ? "-fx-text-fill: #4cd97b; -fx-font-size: 13px; -fx-font-weight: 500;"
                            : "-fx-text-fill: #f5a623; -fx-font-size: 13px; -fx-font-weight: 500;");
                        progressBar.getStyleClass().removeAll("success", "danger");
                        progressBar.getStyleClass().add(allOk ? "success" : "danger");
                        importBtn.setDisable(false);
                        browseBtn.setDisable(false);
                    });
                } catch (Exception ex) {
                    log.error("Excel import failed", ex);
                    Platform.runLater(() -> {
                        progressBar.setProgress(0);
                        progressBar.setVisible(false);
                        progressBar.setManaged(false);
                        statusLabel.setText(I18n.get("setting.email.importFailed", ex.getMessage()));
                        statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 13px;");
                        importBtn.setDisable(false);
                        browseBtn.setDisable(false);
                    });
                }
            });
        });

        Button closeBtn = glassBtn(I18n.get("button.close"), false);
        closeBtn.setOnAction(e -> {
            dialog.close();
            openAddressBookDialog();
        });

        HBox btnRow = new HBox(8, spacer(), closeBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(14,
            sectionTitle(I18n.get("setting.email.importExcel")),
            desc,
            templateBtn,
            fileRow,
            importBtn,
            progressBar,
            statusLabel,
            btnRow
        );
        root.setPadding(new Insets(24));
        root.getStyleClass().add("glass-dialog");
        root.setPrefWidth(520);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.setFill(javafx.scene.paint.Color.web("#0d0e11"));
        Themes.applyTo(scene);
        dialog.setScene(scene);
        dialog.show();
    }

    private static void downloadImportTemplate(File target) {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("Address Book");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("email");
            header.createCell(1).setCellValue("nickname");
            header.createCell(2).setCellValue("tags");

            // Example rows
            org.apache.poi.ss.usermodel.Row ex1 = sheet.createRow(1);
            ex1.createCell(0).setCellValue("alice@example.com");
            ex1.createCell(1).setCellValue("Alice");
            ex1.createCell(2).setCellValue("部门A, 项目X");

            org.apache.poi.ss.usermodel.Row ex2 = sheet.createRow(2);
            ex2.createCell(0).setCellValue("bob@example.com");
            ex2.createCell(1).setCellValue("Bob");
            ex2.createCell(2).setCellValue("部门B");

            sheet.setColumnWidth(0, 8000);
            sheet.setColumnWidth(1, 5000);
            sheet.setColumnWidth(2, 6000);

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(target)) {
                wb.write(fos);
            }
            GlassNotification.toast((Window) null, GlassNotification.Type.SUCCESS,
                I18n.get("setting.email.templateSaved", target.getName()));
        } catch (Exception e) {
            log.error("Failed to save template", e);
            GlassNotification.notify((Window) null, GlassNotification.Type.ERROR,
                I18n.get("setting.email.templateSaveFailed", e.getMessage()));
        }
    }

    private static class ImportResult {
        int imported;
        int failed;
        int skipped;
        int tagsCreated;
    }

    private static ImportResult doImportFromExcel(File file) throws Exception {
        ImportResult result = new ImportResult();

        // Load existing tags into a name→id map
        Map<String, Long> tagNameToId = new HashMap<>();
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            List<EmailTagEntity> existingTags = session.getMapper(EmailTagMapper.class).selectAll();
            if (existingTags != null) {
                for (EmailTagEntity t : existingTags) tagNameToId.put(t.getTag().trim(), t.getId());
            }
        }

        // Load existing emails to skip duplicates
        Set<String> existingEmails = new HashSet<>();
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            List<EmailAddressBookEntity> existing = session.getMapper(EmailAddressBookMapper.class).selectEmailAddressBook();
            if (existing != null) {
                for (EmailAddressBookEntity e : existing) {
                    if (e.getEmailAddress() != null) existingEmails.add(e.getEmailAddress().toLowerCase().trim());
                }
            }
        }

        // Read Excel using Apache POI
        try (org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(file, null, true)) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() < 2) {
                throw new RuntimeException(I18n.get("setting.email.importEmpty"));
            }

            // Read header row to determine column indices
            org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(0);
            int emailCol = -1, nickCol = -1, tagCol = -1;
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                org.apache.poi.ss.usermodel.Cell cell = headerRow.getCell(c);
                if (cell == null) continue;
                String header = cell.toString().trim().toLowerCase();
                if (header.contains("email") || header.contains("邮件") || header.contains("地址") || header.contains("address")) {
                    emailCol = c;
                } else if (header.contains("nick") || header.contains("昵称") || header.contains("name") || header.contains("姓名")) {
                    nickCol = c;
                } else if (header.contains("tag") || header.contains("标签") || header.contains("group") || header.contains("分组")) {
                    tagCol = c;
                }
            }
            // Fallback: if no header match, assume column order: email, nickname, tags
            if (emailCol < 0 && headerRow.getLastCellNum() >= 1) emailCol = 0;
            if (nickCol < 0 && headerRow.getLastCellNum() >= 2) nickCol = 1;
            if (tagCol < 0 && headerRow.getLastCellNum() >= 3) tagCol = 2;

            if (emailCol < 0) {
                throw new RuntimeException(I18n.get("setting.email.importNoEmailCol"));
            }

            // Process data rows
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                if (row == null) continue;

                String email = getCellString(row, emailCol);
                if (email == null || email.isBlank()) { result.failed++; continue; }
                email = email.trim();
                if (!email.matches(".+@.+\\..+")) { result.failed++; continue; }
                if (existingEmails.contains(email.toLowerCase())) { result.skipped++; continue; }

                String nickname = nickCol >= 0 ? getCellString(row, nickCol) : null;
                if (nickname != null) nickname = nickname.trim();

                // Parse tags: comma or Chinese comma separated
                List<Long> tagIds = new ArrayList<>();
                if (tagCol >= 0) {
                    String tagStr = getCellString(row, tagCol);
                    if (tagStr != null && !tagStr.isBlank()) {
                        for (String part : tagStr.split("[,，;；/\\s]+")) {
                            String tagName = part.trim();
                            if (tagName.isEmpty()) continue;
                            Long tagId = tagNameToId.get(tagName);
                            if (tagId == null) {
                                // Auto-create tag
                                EmailTagEntity newTag = new EmailTagEntity();
                                newTag.setTag(tagName);
                                try (SqlSession session = DatabaseInit.getSqlSession()) {
                                    session.getMapper(EmailTagMapper.class).insert(newTag);
                                    session.commit();
                                }
                                tagNameToId.put(tagName, newTag.getId());
                                tagId = newTag.getId();
                                result.tagsCreated++;
                            }
                            tagIds.add(tagId);
                        }
                    }
                }

                // Insert address book entry
                EmailAddressBookEntity entity = new EmailAddressBookEntity();
                entity.setEmailAddress(email);
                entity.setNickname(nickname != null ? nickname : "");
                entity.setTags(tagIds.isEmpty() ? null
                    : "[" + tagIds.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(",")) + "]");

                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    session.getMapper(EmailAddressBookMapper.class).insert(entity);
                    session.commit();
                }
                existingEmails.add(email.toLowerCase());
                result.imported++;
            }
        }

        return result;
    }

    private static String getCellString(org.apache.poi.ss.usermodel.Row row, int col) {
        org.apache.poi.ss.usermodel.Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                yield v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
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

    private static TextField textField(String prompt) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.getStyleClass().add(FIELD_STYLE_CLASS);
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

    /** Runs a task on a daemon thread (won't prevent JVM shutdown). */
    private static void runAsync(Runnable task) {
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }
}
