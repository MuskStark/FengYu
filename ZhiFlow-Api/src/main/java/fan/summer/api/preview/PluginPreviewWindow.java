package fan.summer.api.preview;

import fan.summer.api.PluginContext;
import fan.summer.api.ZhiFlowPlugin;
import fan.summer.api.i18n.I18n;
import fan.summer.api.loader.ChildFirstResourceClassLoader;
import fan.summer.api.theme.Themes;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Preview window for third-party plugin developers.
 *
 * <p>Displays a ZhiFlow-like shell with the plugin's UI embedded,
 * so developers can verify appearance and behavior before deploying.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // From a JAR file
 * PluginPreviewWindow.configure()
 *     .withJar(Path.of("build/libs/my-plugin.jar"))
 *     .launch();
 *
 * // From a plugin instance
 * PluginPreviewWindow.configure()
 *     .withPlugin(new MyPlugin())
 *     .launch();
 *
 * // Full configuration
 * PluginPreviewWindow.configure()
 *     .withPlugin(myPlugin)
 *     .title("My Plugin — Preview")
 *     .windowSize(900, 600)
 *     .showSidebar(false)
 *     .showStatusBar(true)
 *     .launch();
 * }</pre>
 *
 * <p>Note: JavaFX must be initialized before calling {@code launch()}.
 * Call from the JavaFX Application thread or inside {@code Application.start()}.</p>
 */
public final class PluginPreviewWindow {

    private Path jarPath;
    private ZhiFlowPlugin pluginInstance;
    private String title = "Plugin Preview";
    private double width = 960;
    private double height = 620;
    private boolean showSidebar = true;
    private boolean showSearchBar = true;
    private boolean showStatusBar = true;
    private boolean showDetailPanel = true;

    private PluginPreviewWindow() {}

    /** Begin configuration. */
    public static PluginPreviewWindow configure() {
        return new PluginPreviewWindow();
    }

    /** Load a plugin from a JAR file via ServiceLoader. */
    public PluginPreviewWindow withJar(Path jarPath) {
        this.jarPath = jarPath;
        return this;
    }

    /** Use an already-instantiated plugin. Takes precedence over {@link #withJar(Path)}. */
    public PluginPreviewWindow withPlugin(ZhiFlowPlugin plugin) {
        this.pluginInstance = plugin;
        return this;
    }

    /** Window title. Default: "Plugin Preview". */
    public PluginPreviewWindow title(String title) {
        this.title = title != null && !title.isBlank() ? title : this.title;
        return this;
    }

    /** Window size. Default: 960 x 620. */
    public PluginPreviewWindow windowSize(double width, double height) {
        if (width > 0) this.width = width;
        if (height > 0) this.height = height;
        return this;
    }

    /** Show/hide the sidebar. Default: true. */
    public PluginPreviewWindow showSidebar(boolean show) {
        this.showSidebar = show;
        return this;
    }

    /** Show/hide the search bar. Default: true. */
    public PluginPreviewWindow showSearchBar(boolean show) {
        this.showSearchBar = show;
        return this;
    }

    /** Show/hide the status bar. Default: true. */
    public PluginPreviewWindow showStatusBar(boolean show) {
        this.showStatusBar = show;
        return this;
    }

    /** Show/hide the detail panel. Default: true. */
    public PluginPreviewWindow showDetailPanel(boolean show) {
        this.showDetailPanel = show;
        return this;
    }

    /**
     * Create the Stage and show the preview window.
     * Must be called from the JavaFX Application thread.
     */
    public void launch() {
        // Register this API module's own i18n bundle as a FALLBACK so the preview
        // window shows real text when run standalone (third-party plugin authors
        // depend only on ZhiFlow-Api, not the host app). The bundle is loaded
        // directly from the known resource URL (not via ResourceBundle.getBundle),
        // which is robust under any classloader layout — including fat-jars and
        // module layers where the getBundle lookup chain can't see the resource.
        // Low priority: when the preview runs inside the host app, the host's
        // i18n.messages bundle is consulted first and wins for shared keys.
        java.net.URL msgUrl = PluginPreviewWindow.class.getResource("/i18n/messages.properties");
        if (msgUrl != null) {
            I18n.registerFallbackBundle("i18n.messages", msgUrl);
        }

        List<ZhiFlowPlugin> loadedPlugins = new ArrayList<>();
        URLClassLoader classLoader = null;

        // Resolve plugins
        if (pluginInstance != null) {
            loadedPlugins.add(pluginInstance);
        } else if (jarPath != null) {
            try {
                classLoader = new ChildFirstResourceClassLoader(
                    new java.net.URL[]{jarPath.toUri().toURL()},
                    getClass().getClassLoader()
                );
                ServiceLoader<ZhiFlowPlugin> sl = ServiceLoader.load(ZhiFlowPlugin.class, classLoader);
                for (ZhiFlowPlugin p : sl) {
                    loadedPlugins.add(p);
                    PluginContext.register(p, classLoader);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to load plugin from JAR: " + jarPath, e);
            }
        } else {
            throw new IllegalStateException("Either withJar() or withPlugin() must be configured before launch()");
        }

        if (loadedPlugins.isEmpty()) {
            if (classLoader != null) {
                try { classLoader.close(); } catch (Exception ignored) {}
            }
            throw new IllegalStateException("No ZhiFlowPlugin implementation found" +
                (jarPath != null ? " in JAR: " + jarPath : ""));
        }

        // Build the window
        final URLClassLoader finalCl = classLoader;
        final List<ZhiFlowPlugin> finalPlugins = List.copyOf(loadedPlugins);

        // Inject PluginHost exactly like the real host does (before the plugin
        // becomes visible in the shell; init failures must not block the preview).
        final List<PreviewPluginHost> hosts = new ArrayList<>();
        for (ZhiFlowPlugin p : finalPlugins) {
            PreviewPluginHost host = new PreviewPluginHost(p);
            hosts.add(host);
            try {
                PluginContext.runWith(p, () -> p.init(host));
            } catch (Exception e) {
                System.err.println("[preview] plugin " + p.getId() + " threw on init(): " + e.getMessage());
            }
        }

        I18n.setLocale(java.util.Locale.getDefault());

        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle(title);
        stage.setWidth(width);
        stage.setHeight(height);

        PreviewShell shell = new PreviewShell(
            finalPlugins, title,
            showSidebar, showSearchBar, showStatusBar, showDetailPanel,
            () -> {
                for (PreviewPluginHost h : hosts) {
                    try { h.tasks().cancelAll(); } catch (Exception ignored) {}
                }
                if (finalCl != null) {
                    try { finalCl.close(); } catch (Exception ignored) {}
                }
            }
        );

        Scene scene = new Scene(shell, width, height);
        scene.setFill(Color.TRANSPARENT);
        // Register with the theme service: loads common.css (idempotent) AND stamps the
        // active theme class on this scene's root so the -sk-* tokens used in
        // zhiflow-preview.css resolve (otherwise they'd be undefined in this window).
        Themes.applyTo(scene);
        scene.getStylesheets().add(
            PluginPreviewWindow.class.getResource("/css/zhiflow-preview.css").toExternalForm()
        );

        stage.setScene(scene);

        // Wire title bar close button and drag-to-move now that we have the stage
        shell.bindStage(scene);

        stage.setOnCloseRequest(e -> shell.close());
        stage.show();
    }
}
