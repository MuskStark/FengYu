package fan.summer.app;

import fan.summer.api.i18n.I18n;
import fan.summer.api.log.LoggerBinder;
import fan.summer.api.theme.Themes;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.AppSettingEntity;
import fan.summer.database.mapper.AppSettingMapper;
import fan.summer.log.Slf4jPluginLoggerBinder;
import fan.summer.plugin.PluginLoader;
import fan.summer.plugin.PluginRegistry;
import fan.summer.ui.MainWindow;
import fan.summer.Registrar.BuiltinToolRegistrar;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * JavaFX application entry point for SwissKitJ.
 *
 * <p>This class extends {@link javafx.application.Application} and is invoked by
 * {@link fan.summer.Launcher} after the log directory has been primed. It is the
 * graphical entry point and is never used directly as a main class to avoid
 * JavaFX module-system constraints (see {@link Launcher} for details).
 *
 * <p><strong>Startup sequence:</strong>
 * <ol>
 *   <li>Install the plugin logger binder so plugins can use the shared SLF4J backbone.</li>
 *   <li>Initialize the H2 database via MyBatis, creating the schema if absent.</li>
 *   <li>Load the saved language preference from the database and apply i18n locale.</li>
 *   <li>Resolve the plugins directory ({@code .swisskit/plugins/} under the working directory).</li>
 *   <li>Create {@link PluginLoader} and {@link PluginRegistry}.</li>
 *   <li>Register all built-in tools via {@link BuiltinToolRegistrar}.</li>
 *   <li>Build and display the main window.</li>
 *   <li>Start the plugin loader (scans JARs and starts the file-change watcher).</li>
 * </ol>
 *
 * <p>Shutdown is initiated by the JavaFX platform when the last window is closed or
 * {@link #stop()} is called externally. It delegates to {@link MainWindow#shutdown()}
 * to perform cleanup.
 *
 * @since 1.0
 * @author SwissKitJ
 * @see fan.summer.Launcher
 * @see PluginRegistry
 * @see MainWindow
 */
public class SwissKitJApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(SwissKitJApp.class);

    private MainWindow mainWindow;

    @Override
    public void start(Stage stage) throws Exception {
        log.info("SwissKitJ application starting up");

        // ── Plugin logging bridge: plugins use fan.summer.api.log.LoggerFactory,
        //    which delegates to SLF4J via this binder. ──────────────────────
        LoggerBinder.bind(new Slf4jPluginLoggerBinder());
        log.debug("Plugin logger binder installed (SLF4J)");

        // ── Database (H2 + MyBatis) ─────────────────────────────────
        log.info("Initialising database");
        DatabaseInit.init();

        // ── I18n ───────────────────────────────────────────────
        I18n.registerBundle("i18n.messages", getClass().getClassLoader());
        String savedLang = readLanguageFromDb();
        if ("zh".equals(savedLang)) {
            I18n.setLocale(Locale.CHINESE);
        }

        // ── Plugin directory (.swisskit/plugin/ under working directory) ──
        Path pluginsDir = PluginLoader.resolvePluginsDir();
        log.info("Plugin directory resolved to: {}", pluginsDir.toAbsolutePath());

        // ── Plugin system ──────────────────────────────────────
        PluginLoader   loader   = new PluginLoader(pluginsDir);
        PluginRegistry registry = new PluginRegistry(loader);

        // ── Register built-in tools ──────────────────────────────
        BuiltinToolRegistrar.register(loader, registry);
        log.info("Built-in tools registered, count={}", registry.getPlugins().size());

        // ── Main window ────────────────────────────────────────
        mainWindow = new MainWindow(stage, loader, registry);

        // Transparent scene (for rounded window to display correctly)
        Scene scene = new Scene(mainWindow, 960, 620);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().addAll(
            Themes.commonStylesheetUrl(),
            getClass().getResource("/css/shell.css").toExternalForm(),
            getClass().getResource("/css/builtin.css").toExternalForm()
        );

        // App icon (shown in Dock / taskbar)
        var iconUrl = getClass().getResource("/icon.png");
        if (iconUrl != null) {
            stage.getIcons().add(new Image(iconUrl.toExternalForm()));
        }

        // Undecorated window (custom titlebar via TitleBar)
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("SwissKitJ");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(520);
        stage.show();
        log.info("Main window displayed");

        // ── Start plugin loading (after UI is displayed) ────────
        loader.start();
        log.info("Plugin loader started");
    }

    /**
     * Called by the JavaFX platform when the application should terminate.
     * This method shuts down the main window, which in turn deactivates all
     * active plugins and releases any held resources.
     *
     * @see #start(Stage)
     */
    @Override
    public void stop() {
        log.info("SwissKitJ application shutting down");
        if (mainWindow != null) mainWindow.shutdown();
        log.info("Shutdown complete");
    }

    /**
     * Standard Java entry point for launching a JavaFX Application.
     * Delegates to {@link javafx.application.Application#launch(Class, String[])}
     * which creates the JavaFX platform and eventually calls {@link #start(Stage)}.
     *
     * @param args command-line arguments passed to the Java virtual machine
     * @see javafx.application.Application#launch(Class, String[])
     */
    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Reads the saved language preference from the database.
     *
     * @return the language code stored in the database, or {@code "en"} if not set
     *         or if the database query fails
     */
    private String readLanguageFromDb() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey("language");
            if (entity != null) return entity.getSettingValue();
        } catch (Exception e) {
            log.debug("Could not read language setting", e);
        }
        return "en";
    }
}