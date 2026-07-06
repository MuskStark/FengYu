package fan.summer.zhiflow.ui;

import fan.summer.zhiflow.api.PluginContext;
import fan.summer.zhiflow.api.component.SkNotification;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.plugin.FavoriteService;
import fan.summer.zhiflow.plugin.PluginLoader;
import fan.summer.zhiflow.plugin.PluginRegistry;
import fan.summer.zhiflow.ui.about.AboutPage;
import fan.summer.zhiflow.ui.content.ContentArea;
import fan.summer.zhiflow.ui.setting.SwissKitJSettingUi;
import fan.summer.zhiflow.ui.sidebar.Sidebar;
import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.buildintool.ai.AiChatPlugin;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.Node;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Root node of the main window that assembles the complete SwissKitJ UI.
 * Composes Sidebar, ContentArea, and StatusBar into a single
 * themed IDEA-New-UI container, and owns the lifecycle for PluginLoader
 * and PluginRegistry.
 * <p>
 * The window wires up navigation events from the Sidebar to the ContentArea,
 * including tool launching and the AI chat panel. A live clock is displayed
 * in the status bar at the bottom.
 *
 * @see ContentArea
 * @see Sidebar
 * @since 1.0
 */
public class MainWindow extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    private final Stage        stage;
    private final PluginLoader loader;
    private final PluginRegistry registry;
    private final FavoriteService favoriteService;
    private BorderPane windowPane;

    private final Sidebar     sidebar;
    private final ContentArea contentArea;
    private SwissKitJPlugin  aiChatPlugin;
    private Node aiChatView;
    private final Map<SwissKitJPlugin, Node> cachedViews = new ConcurrentHashMap<>();

    // Status bar labels
    private Label statusToolCount    = statusText("0 tools");
    private Label statusPluginCount  = statusText("0 plugins");
    private final Label clockLabel         = statusText("");

    private Timeline clockTimeline;

    /**
     * Constructs the main window and assembles the complete UI hierarchy.
     * Initializes the AI service, counts built-in tools and plugins,
     * builds the scene graph, wires navigation events, starts the clock,
     * and plays the entry animation.
     *
     * @param stage    the primary JavaFX Stage to attach this window to
     * @param loader   the PluginLoader that manages plugin discovery and hot-reload
     * @param registry the PluginRegistry holding all registered plugins and built-in tools
     */
    public MainWindow(Stage stage, PluginLoader loader, PluginRegistry registry, FavoriteService favoriteService) {
        log.debug("Initialising MainWindow");
        this.stage    = stage;
        this.loader   = loader;
        this.registry = registry;
        this.favoriteService = favoriteService;

        sidebar     = new Sidebar();
        contentArea = new ContentArea();
        contentArea.setRegistry(registry);
        contentArea.setFavoriteService(favoriteService);
        contentArea.setMinHeight(0);

        // AI chat plugin — look up the registered instance from BuiltinToolRegistrar
        // instead of creating a duplicate. Falls back to a new instance if not found.
        aiChatPlugin = registry.findPlugin("builtin.ai-chat").orElse(null);
        if (aiChatPlugin == null) {
            // Fallback: shouldn't happen normally, but safe guard
            aiChatPlugin = new AiChatPlugin();
            log.warn("AiChatPlugin not found in registry, created standalone instance");
        }

        int builtinCount = 0;
        int pluginCount = 0;
        for (SwissKitJPlugin plugin : registry.getPlugins()) {
            if (plugin.getType().isPlugin()) {
                pluginCount++;
            } else {
                builtinCount++;
            }
        }

        statusPluginCount = statusText(I18n.get("status.plugins", pluginCount));
        statusToolCount = statusText(I18n.get("status.tools", pluginCount + builtinCount));

        buildScene();
        wireEvents();
        wireShortcuts();
        startClock();
        playEntryAnimation();

        // Open AI chat after the scene is ready (avoids animation conflicts)
        javafx.application.Platform.runLater(this::openAiChat);
    }

    // ── Build scene graph ────────────────────────────────

    private void buildScene() {
        windowPane = new BorderPane();
        windowPane.setMaxWidth(Double.MAX_VALUE);
        windowPane.setMaxHeight(Double.MAX_VALUE);
        windowPane.getStyleClass().add("app-root");

        // Body: sidebar + content area
        HBox body = new HBox(sidebar, contentArea);
        HBox.setHgrow(contentArea, Priority.ALWAYS);
        body.setMinHeight(0);
        windowPane.setCenter(body);

        // Status bar
        HBox statusBar = buildStatusBar();
        BorderPane.setAlignment(statusBar, Pos.BOTTOM_LEFT);
        windowPane.setBottom(statusBar);

        getChildren().setAll(windowPane);
    }

    // ── Status bar ───────────────────────────────────────

    private HBox buildStatusBar() {
        // Activity indicator dot
        Circle dot = new Circle(3, Color.web("#4cd97b"));
        dot.setEffect(new javafx.scene.effect.Glow(0.8));
        FadeTransition pulse = new FadeTransition(Duration.millis(2500), dot);
        pulse.setFromValue(1.0); pulse.setToValue(0.4);
        pulse.setAutoReverse(true); pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();

        Label sep = statusText("·");
        sep.getStyleClass().add("status-sep");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(10);
        bar.getStyleClass().add("statusbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 16, 0, 16));
        bar.setMinHeight(Region.USE_PREF_SIZE);
        bar.getChildren().addAll(dot, statusToolCount, sep, statusPluginCount, spacer, clockLabel);
        return bar;
    }

    private Label statusText(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("status-text");
        return l;
    }

    // ── Event wiring ─────────────────────────────────────

    /**
     * Wires navigation events between sub-components:
     * sidebar category selection, plugin list changes, tool launch callbacks,
     * and back navigation from active tools.
     */
    private void wireEvents() {
        // Bind plugin list to content area
        contentArea.setPlugins(registry.getPlugins());

        // Sidebar category switch → content area filter
        sidebar.setOnCategorySelect(categoryId -> {
            if ("ai".equals(categoryId)) {
                openAiChat();
            } else if ("store".equals(categoryId)) {
                contentArea.showPage(fan.summer.zhiflow.ui.store.PluginStoreUi.build(registry.getPlugins()), I18n.get("store.online.title"));
            } else if ("settings".equals(categoryId)) {
                // settings is handled by setOnSettingsSelect
            } else {
                contentArea.showCategory(categoryId);
            }
        });

        // Settings (standalone, not part of nav state machine)
        sidebar.setOnSettingsSelect(this::openSettings);
        sidebar.setOnAboutSelect(this::openAbout);

        // Plugin list change → update status bar
        registry.getPlugins().addListener(
            (javafx.collections.ListChangeListener<SwissKitJPlugin>) c -> {
                int total   = registry.getPlugins().size();
                int plugins = (int) registry.getPlugins().stream()
                    .filter(p -> p.getType().isPlugin()).count();
                statusToolCount.setText(I18n.get("status.tools", total));
                statusPluginCount.setText(I18n.get("status.plugins", plugins));
                sidebar.updateBadge("plugins", plugins);
            }
        );

        // Favorites change → update sidebar badge + refresh content
        sidebar.updateBadge("fav", favoriteService.count());
        favoriteService.setOnFavoritesChanged(pluginId -> {
            sidebar.updateBadge("fav", favoriteService.count());
        });

        // Tool launch callback
        contentArea.setOnLaunch(plugin -> {
            log.info("Launching tool: id={}, name={}", plugin.getId(), plugin.getName());
            Node view = cachedViews.get(plugin);
            if (view == null) {
                try {
                    view = PluginContext.callWith(plugin, plugin::createView);
                    if (view == null) {
                        log.error("Plugin {} returned null from createView()", plugin.getId());
                        return;
                    }
                    PluginContext.wrapEvents(plugin, view);
                    cachedViews.put(plugin, view);
                } catch (Exception e) {
                    log.error("Failed to create view for plugin {}: {}", plugin.getId(), e.getMessage(), e);
                    return;
                }
            }
            registry.activate(plugin);
            contentArea.showPage(view, plugin.getName());
        });

        // Back / exit plugin view callback
        contentArea.setOnBack(() -> {
            log.debug("Returning to home from active plugin");
            SwissKitJPlugin current = registry.getActivePlugin();
            if (current != null && !registry.isBusy(current)) {
                cachedViews.remove(current);
            }
            registry.deactivate();
        });

        // Plugin uninstall callback
        contentArea.setOnUninstall(plugin -> {
            log.info("Uninstalling plugin: id={}, name={}", plugin.getId(), plugin.getName());
            // Always clear cached view so the plugin's classes can be GC'd
            cachedViews.remove(plugin);
            // If the plugin is currently active, deactivate and navigate back to grid
            SwissKitJPlugin active = registry.getActivePlugin();
            if (active == plugin) {
                registry.deactivate();
                contentArea.showToolGrid();
            }
            try {
                loader.uninstallPlugin(plugin);
                SkNotification.toast(this, SkNotification.Type.SUCCESS,
                    I18n.get("detail.uninstall.success", plugin.getName()));
            } catch (Exception ex) {
                log.error("Failed to uninstall plugin {}: {}", plugin.getId(), ex.getMessage(), ex);
                SkNotification.notify(this, SkNotification.Type.ERROR,
                    I18n.get("detail.uninstall.failed", plugin.getName()));
            }
        });
    }

    /**
     * Registers scene-level keyboard shortcuts once the scene is attached:
     * Cmd/Ctrl+K focuses the search bar, Escape closes the detail panel
     * or clears the search query.
     */
    private void wireShortcuts() {
        sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene == null) return;
            scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN),
                contentArea::focusSearch);
            scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.ESCAPE && contentArea.handleEscape()) {
                    e.consume();
                }
            });
        });
    }

    // ── Settings page ────────────────────────────────────

    /**
     * Opens the Settings page in the content area.
     */
    private void openSettings() {
        Node settingsPage = SwissKitJSettingUi.build();
        contentArea.showPage(settingsPage, I18n.get("sidebar.label.settings"));
    }

    private void openAbout() {
        contentArea.showPage(AboutPage.build(), I18n.get("sidebar.label.about"));
    }

    // ── AI Chat page ────────────────────────────────────

    /**
     * Opens the AI chat panel in the content area, creating the view on first access.
     * Ensures the local AI backend is initialized lazily when the AI tool is opened.
     */
    private void openAiChat() {
        // Ensure local backend is available (lazy init for local mode)
        SwissKitJSettingUi.ensureLocalBackend();

        if (aiChatView == null) {
            try {
                aiChatView = PluginContext.callWith(aiChatPlugin, aiChatPlugin::createView);
            } catch (Exception e) {
                log.error("Failed to create AI chat view: {}", e.getMessage(), e);
                return;
            }
        }
        contentArea.showPage(aiChatView, I18n.get("builtin.ai-chat.name"));
    }

    // ── Entry animation ──────────────────────────────────

    private void playEntryAnimation() {
        windowPane.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(250), windowPane);
        ft.setToValue(1);
        ft.setOnFinished(e -> windowPane.setOpacity(1));
        ft.play();
    }

    // ── Clock ────────────────────────────────────────────

    private void startClock() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        clockTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e ->
            clockLabel.setText(LocalTime.now().format(fmt))
        ));
        clockTimeline.setCycleCount(Animation.INDEFINITE);
        clockTimeline.play();
        clockLabel.setText(LocalTime.now().format(fmt)); // Initial display
    }

    /**
     * Called on application exit to clean up resources.
     * Stops the clock timeline and shuts down the plugin loader.
     */
    public void shutdown() {
        log.debug("MainWindow shutting down resources");
        if (clockTimeline != null) clockTimeline.stop();
        loader.stop();
    }
}
