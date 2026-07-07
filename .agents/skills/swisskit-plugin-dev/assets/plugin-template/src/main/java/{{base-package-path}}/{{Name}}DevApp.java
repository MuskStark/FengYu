package {{base-package}};

import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.preview.PluginPreviewWindow;
import fan.summer.api.theme.Themes;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * Standalone JavaFX {@link Application} for offline dev/testing of the plugin.
 *
 * <p>Launched via {@link DevLauncher} ({@code mvn javafx:run -Pdev}). Uses
 * {@link PluginPreviewWindow} — a host-like shell (sidebar/search/status/detail panel) that
 * loads {@code swisskit-common.css} and stamps the theme class, so what you see matches the
 * real host. Alternatively, hand-roll a {@link Scene} and call {@link Themes#applyTo(Scene)}.
 *
 * <p>This class is NOT packaged into the production plugin JAR's runtime path — it's only used
 * for the dev profile.
 */
public class {{Name}}DevApp extends Application {

    private final SwissKitJPlugin plugin = new {{Name}}Plugin();

    @Override
    public void start(Stage stage) {
        // Option A (recommended): the host-like preview shell.
        PluginPreviewWindow.configure()
                .withPlugin(plugin)
                .title(plugin.getName() + " — dev preview")
                .windowSize(960, 620)
                .showSidebar(true)
                .showSearchBar(true)
                .showStatusBar(true)
                .showDetailPanel(true)
                .launch();   // must run on the JavaFX Application thread

        // Option B (minimal, hand-rolled) — swap in if you don't want the full preview shell:
        //   BorderPane pane = new BorderPane();
        //   pane.setCenter(plugin.createView());
        //   Scene scene = new Scene(pane, 720, 480);
        //   Themes.applyTo(scene);          // load swisskit-common.css + stamp theme class
        //   stage.setScene(scene);
        //   stage.setTitle(plugin.getName() + " — dev");
        //   stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
