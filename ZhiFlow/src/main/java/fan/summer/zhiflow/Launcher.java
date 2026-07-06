package fan.summer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fat JAR / classpath launch entry point.
 *
 * <p>This class must be separate from {@link fan.summer.zhiflow.app.ZhiFlowApp} because it does not
 * extend {@link javafx.application.Application}. The JavaFX module system requires that when
 * a class extending {@code Application} is used as the main class, {@code javafx.graphics} must
 * be on the module-path rather than the classpath. Using this intermediate non-Application class
 * as the entry point allows the application to run in classpath mode (with JARs in {@code lib/}),
 * which simplifies distribution and is compatible with the fat JAR layout produced by Maven.
 *
 * <p>This class also primes the log directory system property before any logger is initialized.
 * The {@code zhiflow.log.dir} property must be set before the first SLF4J logger is accessed,
 * as logback.xml references it during configuration.
 *
 * @since 1.0
 * @author ZhiFlow
 * @see fan.summer.zhiflow.app.ZhiFlowApp
 */
public class Launcher {

    /**
     * Application entry point. Initializes the log directory and delegates to
     * {@link fan.summer.zhiflow.app.ZhiFlowApp#main(String[])}.
     *
     * @param args command-line arguments passed to the Java virtual machine
     */
    public static void main(String[] args) {
        primeLogDirectory();
        fan.summer.zhiflow.app.ZhiFlowApp.main(args);
    }

    /**
     * Resolves and creates the log directory, then exports its absolute path as the
     * {@code zhiflow.log.dir} system property so that logback.xml can reference it.
     * If the user has already set this property externally, this method does nothing.
     *
     * @since 1.0
     */
    private static void primeLogDirectory() {
        if (System.getProperty("zhiflow.log.dir") != null) {
            return;
        }
        Path logDir = Path.of(System.getProperty("user.dir"), ".zhiflow", "logs");
        try {
            Files.createDirectories(logDir);
        } catch (Exception ignored) {
            // Logback will fall back to a relative path; not fatal.
        }
        System.setProperty("zhiflow.log.dir", logDir.toAbsolutePath().toString());
    }
}
