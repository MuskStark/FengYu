package fan.summer;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.AppSettingEntity;
import fan.summer.database.mapper.AppSettingMapper;
import fan.summer.i18n.I18nManager;
import fan.summer.i18n.Language;
import fan.summer.plugin.PluginDiagnostic;
import fan.summer.api.plugin.PluginRegistry;
import fan.summer.ui.loading.SplashScreen;
import fan.summer.ui.theme.ThemeManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX Application entry point.
 * Initializes database, theme, and displays the main application window.
 *
 * @author summer
 * @version 2.00 - JavaFX migration
 */
public class Main extends Application {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static Main instance;
    private static CountDownLatch initLatch = new CountDownLatch(1);

    private Stage primaryStage;
    private SplashScreen splashScreen;

    public static void main(String[] args) {
        logger.info("SwissKitJ starting...");

        // Run plugin diagnostic for plugin loading issues (silent, DEBUG level)
        PluginDiagnostic.run();

        launch(args);
    }

    @Override
    public void init() throws Exception {
        instance = this;
        logger.info("Initializing SwissKitJ...");

        // Show splash screen
        Platform.runLater(() -> {
            splashScreen = new SplashScreen();
            splashScreen.show();
        });

        // Initialize database in background
        new Thread(() -> {
            try {
//                DatabaseInit.init();
//                Language language = loadLanguageFromDb();
//                I18nManager.init(language);
//
//                // Initialize theme
//                ThemeManager.init();

                logger.info("SwissKitJ initialization complete");
            } catch (Exception e) {
                logger.error("Initialization failed", e);
            } finally {
                initLatch.countDown();
            }
        }, "InitThread").start();
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        this.primaryStage = primaryStage;

        // Wait for initialization (max 30 seconds)
        boolean initComplete = initLatch.await(30, TimeUnit.SECONDS);
        if (!initComplete) {
            logger.error("Initialization timed out");
            Platform.exit();
            return;
        }

        // Close splash
        if (splashScreen != null) {
            splashScreen.hide();
        }

        // Create main scene
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/css/uiFxml/app/app.fxml"));
            Parent root = loader.load();

            // Apply CSS
            Scene scene = new Scene(root);
            String cssPath = getClass().getResource("/css/app.css").toExternalForm();
            scene.getStylesheets().add(cssPath);

            // Configure stage
            primaryStage.setTitle("SwissKit");
            primaryStage.setScene(scene);
            primaryStage.setWidth(960);
            primaryStage.setHeight(620);
            primaryStage.setMinWidth(800);
            primaryStage.setMinHeight(500);

            // Center on screen
            Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
            primaryStage.setX((screenBounds.getWidth() - 960) / 2);
            primaryStage.setY((screenBounds.getHeight() - 620) / 2);

            // Set window icon
            setAppIcon(primaryStage);

            // Remove window decorations for frameless look (optional)
            // primaryStage.initStyle(StageStyle.UNDECORATED);

            primaryStage.show();

            // Initialize plugin registry
            PluginRegistry.getInstance().notifyAppReady();

            logger.info("Main window displayed");

        } catch (Exception e) {
            logger.error("Failed to load main window", e);
            Platform.exit();
        }
    }

    @Override
    public void stop() throws Exception {
        logger.info("SwissKitJ shutting down...");
        PluginRegistry.getInstance().clear();
        super.stop();
    }

    private Language loadLanguageFromDb() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity setting = mapper.selectByKey("language");
            if (setting != null && setting.getSettingValue() != null) {
                return Language.fromCode(setting.getSettingValue());
            }
        } catch (Exception e) {
            logger.warn("Failed to load language from database: {}", e.getMessage());
        }
        return Language.ENGLISH;
    }

    /**
     * Set application icon
     * Place icon file in resources directory, supports: icon.png, icon.jpg, app.png
     */
    private void setAppIcon(Stage stage) {
        String[] iconPaths = {"/icon.png", "/icon.jpg", "/app.png"};

        for (String path : iconPaths) {
            java.net.URL url = getClass().getResource(path);
            if (url != null) {
                stage.getIcons().add(new Image(url.toString()));
                return;
            }
        }
        logger.info("Application icon not found. Please add icon.png to resources directory");
    }

    /**
     * Get the primary stage.
     */
    public Stage getPrimaryStage() {
        return primaryStage;
    }

    /**
     * Get the singleton instance.
     */
    public static Main getInstance() {
        return instance;
    }
}