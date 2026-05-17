package fan.summer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fat JAR / classpath launch entry point.
 *
 * Must be separate from ToolboxApp (extends Application).
 * JavaFX module system requires: when using a class that extends Application
 * as the main class, javafx.graphics needs to be on the module-path,
 * not classpath. Using this non-Application intermediate class for launch
 * allows compatibility with classpath mode (lib/ directory).
 *
 * Also primes the logback log directory system property before any logger is
 * initialised — must happen before the first SLF4J class is touched.
 */
public class Launcher {

    public static void main(String[] args) {
        primeLogDirectory();
        fan.summer.app.SwissKitJApp.main(args);
    }

    /**
     * Resolves and creates the log directory, then exports its absolute path as the
     * {@code swisskit.log.dir} system property so that logback.xml can reference it.
     * Honours an override if the user has already set the property externally.
     */
    private static void primeLogDirectory() {
        if (System.getProperty("swisskit.log.dir") != null) {
            return;
        }
        Path logDir = Path.of(System.getProperty("user.dir"), ".swisskit", "logs");
        try {
            Files.createDirectories(logDir);
        } catch (Exception ignored) {
            // Logback will fall back to a relative path; not fatal.
        }
        System.setProperty("swisskit.log.dir", logDir.toAbsolutePath().toString());
    }
}
