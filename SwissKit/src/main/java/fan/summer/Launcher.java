package fan.summer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fat JAR / classpath launch entry point.
 *
 * <p>This class must be separate from {@link fan.summer.app.SwissKitJApp} because it does not
 * extend {@link javafx.application.Application}. The JavaFX module system requires that when
 * a class extending {@code Application} is used as the main class, {@code javafx.graphics} must
 * be on the module-path rather than the classpath. Using this intermediate non-Application class
 * as the entry point allows the application to run in classpath mode (with JARs in {@code lib/}),
 * which simplifies distribution and is compatible with the fat JAR layout produced by Maven.
 *
 * <p>This class also primes the log directory system property before any logger is initialized.
 * The {@code swisskit.log.dir} property must be set before the first SLF4J logger is accessed,
 * as logback.xml references it during configuration.
 *
 * @since 1.0
 * @author SwissKitJ
 * @see fan.summer.app.SwissKitJApp
 */
public class Launcher {

    /**
     * Application entry point. Initializes the log directory and delegates to
     * {@link fan.summer.app.SwissKitJApp#main(String[])}.
     *
     * @param args command-line arguments passed to the Java virtual machine
     */
    public static void main(String[] args) {
        primeLogDirectory();
        primeJavaFxRendering();
        fan.summer.app.SwissKitJApp.main(args);
    }

    /**
     * Resolves and creates the log directory, then exports its absolute path as the
     * {@code swisskit.log.dir} system property so that logback.xml can reference it.
     * If the user has already set this property externally, this method does nothing.
     *
     * @since 1.0
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

    /**
     * Configures the JavaFX rendering pipeline before the toolkit initializes.
     *
     * <p>On hardened Linux distributions (e.g., UOS/Deepin), unsigned native libraries
     * are blocked by system security verification. JavaFX's default hardware-accelerated
     * renderer (Prism ES2 / OpenGL) ships unsigned native libraries ({@code libprism_es2.so}),
     * which causes the application to crash on startup with a security verification error.
     *
     * <p>This method checks if the user has explicitly set a rendering pipeline via the
     * {@code prism.order} system property. If not, it defaults to software rendering
     * ({@code sw}) which uses pure Java and does not require native OpenGL libraries.
     *
     * <p>Users who want hardware acceleration on supported platforms can override this
     * by passing {@code -Dprism.order=es2} on the command line.
     *
     * @since 3.0.0
     */
    private static void primeJavaFxRendering() {
        if (System.getProperty("prism.order") != null) {
            return; // user has explicitly chosen a pipeline
        }
        System.setProperty("prism.order", "sw");
    }
}
