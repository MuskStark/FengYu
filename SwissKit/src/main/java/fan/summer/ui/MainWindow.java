package fan.summer.ui;

import fan.summer.api.PluginContext;
import fan.summer.api.i18n.I18n;
import fan.summer.plugin.FavoriteService;
import fan.summer.plugin.PluginLoader;
import fan.summer.plugin.PluginRegistry;
import fan.summer.ui.content.ContentArea;
import fan.summer.ui.setting.SwissKitJSettingUi;
import fan.summer.ui.sidebar.Sidebar;
import fan.summer.ui.titlebar.TitleBar;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.buildintool.ai.AiChatPlugin;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.Node;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
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
 * Composes TitleBar, Sidebar, ContentArea, and StatusBar into a single
 * glassmorphism-styled container, and owns the lifecycle for PluginLoader
 * and PluginRegistry.
 * <p>
 * The window displays animated background orbs for visual depth, and
 * wires up navigation events from the Sidebar to the ContentArea, including
 * tool launching and the AI chat panel. A live clock is displayed in the
 * status bar at the bottom.
 *
 * @see ContentArea
 * @see Sidebar
 * @see TitleBar
 * @since 1.0
 */
public class MainWindow extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    private final Stage        stage;
    private final PluginLoader loader;
    private final PluginRegistry registry;
    private final FavoriteService favoriteService;
    private BorderPane windowPane;

    private final TitleBar    titleBar;
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

        titleBar    = new TitleBar(stage, this::openSettings);
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
        startClock();
        playEntryAnimation();

        // Open AI chat after the scene is ready (avoids animation conflicts)
        javafx.application.Platform.runLater(this::openAiChat);
    }

    // ── Build scene graph ────────────────────────────────

    private void buildScene() {
        // Background orb layer (bottom layer)
        Pane orbLayer = buildOrbLayer();

        // Main window glass panel
        windowPane = new BorderPane();
        windowPane.setMaxWidth(Double.MAX_VALUE);
        windowPane.setMaxHeight(Double.MAX_VALUE);
        windowPane.getStyleClass().add("app-root");
        windowPane.setStyle(
            "-fx-background-color: rgba(13,14,17,0.72);" +
            "-fx-background-radius: 20;" +
            "-fx-border-radius: 20;" +
            "-fx-border-color: rgba(255,255,255,0.10);" +
            "-fx-border-width: 1;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 60, 0, 0, 20);"
        );

        // Title bar
        BorderPane.setAlignment(titleBar, Pos.TOP_LEFT);
        windowPane.setTop(titleBar);

        // Body: sidebar + content area
        HBox body = new HBox(sidebar, contentArea);
        HBox.setHgrow(contentArea, Priority.ALWAYS);
        body.setMinHeight(0);
        windowPane.setCenter(body);

        // Status bar
        HBox statusBar = buildStatusBar();
        BorderPane.setAlignment(statusBar, Pos.BOTTOM_LEFT);
        windowPane.setBottom(statusBar);

        // Top highlight border (glass thickness simulation)
        Rectangle topHighlight = new Rectangle();
        topHighlight.setMouseTransparent(true);
        topHighlight.setArcWidth(40);
        topHighlight.setArcHeight(40);
        topHighlight.setStyle(
            "-fx-fill: transparent;" +
            "-fx-stroke: rgba(255,255,255,0.12);" +
            "-fx-stroke-width: 1;"
        );
        topHighlight.widthProperty().bind(widthProperty());
        topHighlight.heightProperty().bind(heightProperty());

        getChildren().addAll(orbLayer, windowPane, topHighlight);
        setAlignment(topHighlight, Pos.CENTER);

        // Clip whole window to rounded rectangle so the dark orbLayer behind
        // never bleeds outside the rounded corners on any platform.
        Rectangle windowClip = new Rectangle();
        windowClip.setArcWidth(40);
        windowClip.setArcHeight(40);
        windowClip.widthProperty().bind(widthProperty());
        windowClip.heightProperty().bind(heightProperty());
        setClip(windowClip);
    }

    // ── Background orbs ───────────────────────────────────

    private Pane buildOrbLayer() {
        Pane layer = new Pane();
        layer.setMouseTransparent(true);
        layer.setStyle("-fx-background-color: #0d0e11;");

        // Three colored Gaussian blur orbs
        layer.getChildren().addAll(
            orb(480, "#3b5bdb", -80, -120, 0),
            orb(360, "#7048e8",  -60, 200,  -6000),
            orb(300, "#1c7ed6",  300, 400, -12000)
        );
        return layer;
    }

    private StackPane orb(double size, String color, double x, double y, double animDelay) {
        Circle c = new Circle(size / 2,
            Color.web(color, 0.28));
        c.setEffect(new javafx.scene.effect.GaussianBlur(60));

        StackPane wrap = new StackPane(c);
        wrap.setTranslateX(x);
        wrap.setTranslateY(y);
        wrap.setMouseTransparent(true);

        // Floating animation
        TranslateTransition drift = new TranslateTransition(Duration.millis(18000), wrap);
        drift.setByX(30); drift.setByY(20);
        drift.setAutoReverse(true);
        drift.setCycleCount(Animation.INDEFINITE);
        drift.setDelay(Duration.millis(Math.abs(animDelay)));
        drift.setInterpolator(Interpolator.EASE_BOTH);
        drift.play();

        ScaleTransition breathe = new ScaleTransition(Duration.millis(12000), wrap);
        breathe.setFromX(1.0); breathe.setFromY(1.0);
        breathe.setToX(1.08);  breathe.setToY(1.08);
        breathe.setAutoReverse(true);
        breathe.setCycleCount(Animation.INDEFINITE);
        breathe.setInterpolator(Interpolator.EASE_BOTH);
        breathe.play();

        return wrap;
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
        sep.setStyle("-fx-text-fill: rgba(255,255,255,0.15); -fx-font-size: 11px;");

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
                contentArea.showPage(fan.summer.ui.store.PluginStoreUi.build(), I18n.get("store.online.title"));
            } else if ("settings".equals(categoryId)) {
                // settings is handled by setOnSettingsSelect
            } else {
                contentArea.showCategory(categoryId);
            }
        });

        // Settings (standalone, not part of nav state machine)
        sidebar.setOnSettingsSelect(this::openSettings);

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
            if (current != null && !current.hasRunningTasks()) {
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
            } catch (Exception ex) {
                log.error("Failed to uninstall plugin {}: {}", plugin.getId(), ex.getMessage(), ex);
            }
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
        windowPane.setScaleX(0.94);
        windowPane.setScaleY(0.94);
        windowPane.setTranslateY(16);

        FadeTransition ft = new FadeTransition(Duration.millis(500), windowPane);
        ft.setToValue(1);

        ScaleTransition st = new ScaleTransition(Duration.millis(500), windowPane);
        st.setToX(1); st.setToY(1);
        st.setInterpolator(Interpolator.SPLINE(0.34, 0.9, 0.64, 1.0));

        TranslateTransition tt = new TranslateTransition(Duration.millis(500), windowPane);
        tt.setToY(0);
        tt.setInterpolator(Interpolator.SPLINE(0.34, 0.9, 0.64, 1.0));

        ParallelTransition entry = new ParallelTransition(ft, st, tt);
        entry.setOnFinished(e -> {
            // Ensure full visibility after animation
            windowPane.setOpacity(1);
            windowPane.setScaleX(1);
            windowPane.setScaleY(1);
            windowPane.setTranslateY(0);
        });
        entry.play();
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
