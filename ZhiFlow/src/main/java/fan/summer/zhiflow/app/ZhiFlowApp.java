package fan.summer.zhiflow.app;

import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.log.LoggerBinder;
import fan.summer.zhiflow.api.theme.ThemeService;
import fan.summer.zhiflow.api.theme.Themes;
import fan.summer.zhiflow.database.DatabaseInit;
import fan.summer.zhiflow.database.entity.AppSettingEntity;
import fan.summer.zhiflow.database.mapper.AppSettingMapper;
import fan.summer.zhiflow.log.Slf4jPluginLoggerBinder;
import fan.summer.zhiflow.plugin.FavoriteService;
import fan.summer.zhiflow.plugin.PluginLoader;
import fan.summer.zhiflow.plugin.PluginRegistry;
import fan.summer.zhiflow.ui.MainWindow;
import fan.summer.zhiflow.registrar.BuiltinToolRegistrar;
import fan.summer.zhiflow.ai.service.SpringAiCloudBackend;
import fan.summer.zhiflow.ai.spring.AiSpringContext;
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
 * JavaFX application entry point for ZhiFlow.
 *
 * <p>This class extends {@link javafx.application.Application} and is invoked by
 * {@link fan.summer.zhiflow.Launcher} after the log directory has been primed. It is the
 * graphical entry point and is never used directly as a main class to avoid
 * JavaFX module-system constraints (see {@link Launcher} for details).
 *
 * <p><strong>Startup sequence:</strong>
 * <ol>
 *   <li>Install the plugin logger binder so plugins can use the shared SLF4J backbone.</li>
 *   <li>Initialize the H2 database via MyBatis, creating the schema if absent.</li>
 *   <li>Load the saved language preference from the database and apply i18n locale.</li>
 *   <li>Resolve the plugins directory ({@code .zhiflow/plugins/} under the working directory).</li>
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
 * @author ZhiFlow
 * @see fan.summer.zhiflow.Launcher
 * @see PluginRegistry
 * @see MainWindow
 */
public class ZhiFlowApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(ZhiFlowApp.class);

    private MainWindow mainWindow;

    @Override
    public void start(Stage stage) throws Exception {
        log.info("ZhiFlow application starting up");

        // ── Plugin logging bridge: plugins use fan.summer.zhiflow.api.log.LoggerFactory,
        //    which delegates to SLF4J via this binder. ──────────────────────
        LoggerBinder.bind(new Slf4jPluginLoggerBinder());
        log.debug("Plugin logger binder installed (SLF4J)");

        // ── Database (H2 + MyBatis) ─────────────────────────────────
        log.info("Initialising database");
        DatabaseInit.init();

        // ── Embedded Spring context (DI + Spring AI ChatModel beans) ────
        // Must start after DB init (AiConfigService reads H2 for the bean config)
        // and before initializeAiBackend() (which looks up ChatModel beans).
        AiSpringContext.start();

        // ── I18n ───────────────────────────────────────────────
        I18n.registerBundle("i18n.messages", getClass().getClassLoader());
        String savedLang = readLanguageFromDb();
        if ("zh".equals(savedLang)) {
            I18n.setLocale(Locale.CHINESE);
        }

        // ── Theme (dark default, persisted) ────────────────────────
        String savedTheme = readThemeFromDb();
        ThemeService.set("light".equalsIgnoreCase(savedTheme)
            ? ThemeService.Theme.LIGHT : ThemeService.Theme.DARK);

        // ── Plugin directory (.zhiflow/plugin/ under working directory) ──
        Path pluginsDir = PluginLoader.resolvePluginsDir();
        log.info("Plugin directory resolved to: {}", pluginsDir.toAbsolutePath());

        // ── Plugin system ──────────────────────────────────────
        PluginLoader   loader   = new PluginLoader(pluginsDir);
        PluginRegistry registry = new PluginRegistry(loader);

        // ── Favorites service (loads from DB) ──────────────────
        FavoriteService favoriteService = new FavoriteService();

        // ── Register built-in tools ──────────────────────────────
        BuiltinToolRegistrar.register(loader, registry);
        log.info("Built-in tools registered, count={}", registry.getPlugins().size());

        // ── Initialize AI backend based on saved mode ────────
        initializeAiBackend();

        // ── Main window ────────────────────────────────────────
        mainWindow = new MainWindow(stage, loader, registry, favoriteService);

        Scene scene = new Scene(mainWindow, 960, 620);
        // Best-effort static fill to avoid a flash-of-white before CSS resolves
        // (the root StackPane has no background of its own; only its .app-root
        // child does). CSS governs the visible background once painted.
        scene.setFill(ThemeService.current() == ThemeService.Theme.LIGHT
            ? Color.WHITE
            : Color.web("#1E1E1E"));
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

        // Native OS window decorations (titlebar + resize handled by the platform)
        stage.initStyle(StageStyle.DECORATED);
        stage.setTitle("ZhiFlow");
        stage.setScene(scene);
        ThemeService.registerScene(scene);
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
        log.info("ZhiFlow application shutting down");
        if (mainWindow != null) mainWindow.shutdown();
        try { AiSpringContext.close(); }
        catch (Exception e) { log.warn("AI Spring context close failed: {}", e.getMessage()); }
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

    private void initializeAiBackend() {
        String mode = fan.summer.zhiflow.ai.AiConfigService.getAiMode();
        log.info("AI backend mode: {}", mode);

        switch (mode) {
            case "openai" -> {
                SpringAiCloudBackend svc = SpringAiCloudBackend.openAi(
                    fan.summer.zhiflow.ai.AiConfigService.getAiOpenAiEndpoint(),
                    fan.summer.zhiflow.ai.AiConfigService.getAiOpenAiApiKey(),
                    fan.summer.zhiflow.ai.AiConfigService.getAiOpenAiModel()
                );
                AiServiceProvider.switchMode(mode, svc);
                log.info("OpenAI backend initialized: model={}", fan.summer.zhiflow.ai.AiConfigService.getAiOpenAiModel());
            }
            case "anthropic" -> {
                SpringAiCloudBackend svc = SpringAiCloudBackend.anthropic(
                    fan.summer.zhiflow.ai.AiConfigService.getAiAnthropicEndpoint(),
                    fan.summer.zhiflow.ai.AiConfigService.getAiAnthropicApiKey(),
                    fan.summer.zhiflow.ai.AiConfigService.getAiAnthropicModel()
                );
                AiServiceProvider.switchMode(mode, svc);
                log.info("Anthropic backend initialized: model={}", fan.summer.zhiflow.ai.AiConfigService.getAiAnthropicModel());
            }
            default -> {
                // Local mode: defer initialization until AI tool is opened.
                // See ZhiFlowSettingUi.ensureLocalBackend() for the lazy init logic.
                log.info("AI backend: local (deferred, will initialize when AI tool opens)");
            }
        }
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

    /**
     * Reads the saved theme preference from the database.
     *
     * @return "light" if the light theme is saved, otherwise "dark" (the default)
     */
    private String readThemeFromDb() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey("theme");
            if (entity != null) return entity.getSettingValue();
        } catch (Exception e) {
            log.debug("Could not read theme setting", e);
        }
        return "dark";
    }
}