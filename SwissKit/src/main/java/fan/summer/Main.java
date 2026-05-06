package fan.summer;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.AppSettingEntity;
import fan.summer.database.mapper.AppSettingMapper;
import fan.summer.i18n.I18nManager;
import fan.summer.i18n.Language;
import fan.summer.plugin.PluginDiagnostic;
import fan.summer.ui.StartLoadingPage;
import fan.summer.ui.home.HomePage;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;

/**
 * Application entry point.
 * Initializes the database and displays the splash screen before loading the main application.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/1
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * Application entry point. Initializes logging, database, and displays the main UI.
     *
     * @param args command-line arguments (not used)
     */
    public static void main(String[] args) {
        // Initialize Log4j first by accessing logger
        logger.info("SwissKitJ starting...");

        // Run plugin diagnostic for plugin loading issues (silent, DEBUG level)
        PluginDiagnostic.run();

        SwingUtilities.invokeLater(() -> {
            JWindow splash = new StartLoadingPage().getWindow();
            splash.setVisible(true);

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    try {
                        DatabaseInit.init();
                        Language language = loadLanguageFromDb();
                        I18nManager.init(language);
                    } catch (Exception e) {
                        publish((Void) null);
                    }
                    return null;
                }

                @Override
                protected void done() {
                    splash.dispose();
                    try {
                        get();
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(null,
                            "Database initialization failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()),
                            "Error", JOptionPane.ERROR_MESSAGE);
                        System.exit(1);
                        return;
                    }
                    new HomePage().init();
                }
            }.execute();
        });
    }

    private static Language loadLanguageFromDb() {
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
}
