package fan.summer.ui;

import fan.summer.plugin.PluginLoader;
import fan.summer.plugin.PluginRegistry;
import fan.summer.ui.content.ContentArea;
import fan.summer.ui.setting.SwissKitJSettingUi;
import fan.summer.ui.sidebar.Sidebar;
import fan.summer.ui.titlebar.TitleBar;
import fan.summer.api.SwissKitJPlugin;
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

/**
 * Root node of the main window.
 * Assembles TitleBar / Sidebar / ContentArea / StatusBar,
 * and holds the lifecycle for PluginLoader and PluginRegistry.
 */
public class MainWindow extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    private final Stage        stage;
    private final PluginLoader loader;
    private final PluginRegistry registry;

    private final TitleBar    titleBar;
    private final Sidebar     sidebar;
    private final ContentArea contentArea;

    // Status bar labels
    private Label statusToolCount    = statusText("0 tools");
    private Label statusPluginCount  = statusText("0 plugins");
    private final Label clockLabel         = statusText("");

    private Timeline clockTimeline;

    public MainWindow(Stage stage, PluginLoader loader, PluginRegistry registry) {
        log.debug("Initialising MainWindow");
        this.stage    = stage;
        this.loader   = loader;
        this.registry = registry;

        titleBar    = new TitleBar(stage, this::openSettings);
        sidebar     = new Sidebar();
        contentArea = new ContentArea();

        int buildInTool = 0;
        int pluginInTool = 0;
        for (SwissKitJPlugin plugin : registry.getPlugins()) {
            if(plugin.getType().equals("plugin") ) {
                pluginInTool++;
            }else {
                buildInTool++;
            }
        }

        statusPluginCount = statusText(pluginInTool + "plugins");
        statusToolCount = statusText(pluginInTool + buildInTool +  "tools");

        buildScene();
        wireEvents();
        startClock();
        playEntryAnimation();
    }

    // ── Build scene graph ────────────────────────────────

    private void buildScene() {
        // Background orb layer (bottom layer)
        Pane orbLayer = buildOrbLayer();

        // Main window glass panel
        BorderPane windowPane = new BorderPane();
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
        windowPane.setTop(titleBar);

        // Body: sidebar + content area
        HBox body = new HBox(sidebar, contentArea);
        HBox.setHgrow(contentArea, Priority.ALWAYS);
        windowPane.setCenter(body);

        // Status bar
        windowPane.setBottom(buildStatusBar());

        // Top highlight border (glass thickness simulation)
        Rectangle topHighlight = new Rectangle();
        topHighlight.setMouseTransparent(true);
        topHighlight.setStyle(
            "-fx-fill: transparent;" +
            "-fx-stroke: rgba(255,255,255,0.12);" +
            "-fx-stroke-width: 1;" +
            "-fx-arc-width: 40; -fx-arc-height: 40;"
        );
        topHighlight.widthProperty().bind(widthProperty());
        topHighlight.heightProperty().bind(heightProperty());

        getChildren().addAll(orbLayer, windowPane, topHighlight);
        setAlignment(windowPane, Pos.CENTER);
        setAlignment(topHighlight, Pos.CENTER);
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
        bar.getChildren().addAll(dot, statusToolCount, sep, statusPluginCount, spacer, clockLabel);
        return bar;
    }

    private Label statusText(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("status-text");
        return l;
    }

    // ── Event wiring ─────────────────────────────────────

    private void wireEvents() {
        // Bind plugin list to content area
        contentArea.setPlugins(registry.getPlugins());

        // Sidebar category switch → content area filter
        sidebar.setOnCategorySelect(categoryId -> {
            if ("store".equals(categoryId)) {
                contentArea.showPage(fan.summer.ui.store.PluginStoreUi.build(), "Plugin Store");
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
                    .filter(p -> !"builtin".equals(p.getType())).count();
                statusToolCount.setText(total + " tools");
                statusPluginCount.setText(plugins + " plugins");
                sidebar.updateBadge("plugins", plugins);
            }
        );

        // Tool launch callback
        contentArea.setOnLaunch(plugin -> {
            log.info("Launching tool: id={}, name={}", plugin.getId(), plugin.getName());
            registry.activate(plugin);
            try {
                contentArea.showPage(plugin.createView(), plugin.getName());
            } catch (Exception e) {
                log.error("Failed to create view for plugin {}: {}", plugin.getId(), e.getMessage(), e);
            }
        });

        // Back / exit plugin view callback
        contentArea.setOnBack(() -> {
            log.debug("Returning to home from active plugin");
            registry.deactivate();
        });
    }

    // ── Settings page ────────────────────────────────────

    private void openSettings() {
        Node settingsPage = SwissKitJSettingUi.build();
        contentArea.showPage(settingsPage, "Settings");
    }

    // ── Entry animation ──────────────────────────────────

    private void playEntryAnimation() {
        // Get windowPane (second child node)
        javafx.scene.Node windowPane = getChildren().get(1);
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

        new ParallelTransition(ft, st, tt).play();
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

    /** Called on application exit to clean up resources */
    public void shutdown() {
        log.debug("MainWindow shutting down resources");
        if (clockTimeline != null) clockTimeline.stop();
        loader.stop();
    }
}
