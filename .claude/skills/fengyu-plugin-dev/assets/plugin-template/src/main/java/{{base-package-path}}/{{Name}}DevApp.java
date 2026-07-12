package {{base-package}};

import fan.summer.fengyu.api.FengYuPlugin;
import fan.summer.fengyu.api.preview.PluginPreviewWindow;

import javafx.application.Application;
import javafx.stage.Stage;

/**
 * Standalone JavaFX {@link Application} for offline dev/testing of the plugin.
 *
 * <p>Launched via {@link DevLauncher} ({@code mvn javafx:run -Pdev}). Uses
 * {@link PluginPreviewWindow} — a host-like shell (sidebar/search/status/detail panel) that
 * loads {@code fengyu-common.css}, stamps the theme class, AND injects a {@code PluginHost}
 * into the plugin (calls {@code init(host)}) exactly like the real host. That injection is why
 * a hand-rolled {@code Scene} is NOT a valid dev harness in 3.2.0: without it the plugin's
 * {@code host} field stays null and every {@code host.i18n()/logger()/...} call throws.
 *
 * <p>Do not read plugin metadata (e.g. {@code plugin.getName()}) before {@code launch()} runs —
 * the host isn't injected until then. Use a literal window title here.
 *
 * <p>This class is NOT packaged into the production plugin JAR's runtime path — it's only used
 * for the dev profile.
 */
public class {{Name}}DevApp extends Application {

    private final FengYuPlugin plugin = new {{Name}}Plugin();

    @Override
    public void start(Stage stage) {
        // The host-like preview shell — injects a PluginHost via init(host) before showing the view.
        PluginPreviewWindow.configure()
                .withPlugin(plugin)
                .title("{{Name}} — dev preview")   // literal: host not injected until launch()
                .windowSize(960, 620)
                .showSidebar(true)
                .showSearchBar(true)
                .showStatusBar(true)
                .showDetailPanel(true)
                .launch();   // must run on the JavaFX Application thread
    }

    public static void main(String[] args) {
        launch(args);
    }
}
