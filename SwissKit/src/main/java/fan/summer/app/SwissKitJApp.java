package fan.summer.app;

import fan.summer.plugin.PluginLoader;
import fan.summer.plugin.PluginRegistry;
import fan.summer.ui.MainWindow;
import fan.summer.Registrar.BuiltinToolRegistrar;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Application entry point.
 * Startup sequence:
 *   1. Determine plugins/ directory
 *   2. Create PluginLoader + PluginRegistry
 *   3. Register built-in tools
 *   4. Build MainWindow and display
 *   5. Start PluginLoader (scan JARs + start file watcher)
 */
public class SwissKitJApp extends Application {

    private MainWindow mainWindow;

    @Override
    public void start(Stage stage) throws Exception {

        // ── Plugin directory (prefer JAR sibling, fallback to working directory during dev) ──
        Path pluginsDir = resolvePluginsDir();

        // ── Plugin system ──────────────────────────────────────
        PluginLoader   loader   = new PluginLoader(pluginsDir);
        PluginRegistry registry = new PluginRegistry(loader);

        // ── Register built-in tools ──────────────────────────────
        BuiltinToolRegistrar.register(loader, registry);

        // ── Main window ────────────────────────────────────────
        mainWindow = new MainWindow(stage, loader, registry);

        // Transparent scene (for rounded window to display correctly)
        Scene scene = new Scene(mainWindow, 960, 620);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(
            getClass().getResource("/com/toolbox/css/glass.css").toExternalForm()
        );

        // Undecorated window (custom titlebar via TitleBar)
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("ToolBox");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(520);
        stage.show();

        // ── Start plugin loading (after UI is displayed) ────────
        loader.start();
    }

    @Override
    public void stop() {
        if (mainWindow != null) mainWindow.shutdown();
    }

    // ── Helper: locate plugins directory ───────────────────────────

    private Path resolvePluginsDir() {
        // Try JAR sibling directory
        try {
            Path jar = Paths.get(
                SwissKitJApp.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()
            );
            Path candidate = jar.getParent().resolve("plugins");
            if (candidate.toFile().isDirectory()) return candidate;
        } catch (Exception ignored) {}

        // Dev mode: plugins/ under working directory
        return Paths.get("plugins");
    }

    public static void main(String[] args) {
        launch(args);
    }
}