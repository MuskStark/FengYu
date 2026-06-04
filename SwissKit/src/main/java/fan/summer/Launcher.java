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
     * Configures JavaFX rendering before the toolkit initializes.
     *
     * <p>On macOS and Windows, hardware rendering always works — this method does nothing.
     * On Linux, it inspects {@code /etc/os-release} to detect distributions known to block
     * unsigned native libraries via system security verification (UOS, Deepin, Kylin).
     * On those distributions, JavaFX's hardware pipeline ({@code libprism_es2.so}) fails
     * to load because its native libraries are unsigned, causing the application to crash
     * on startup. To avoid this, software rendering ({@code prism.order=sw}) is forced on
     * these distributions only — other Linux distros keep hardware acceleration.
     *
     * <p>We intentionally do NOT probe by attempting to load the library, because the
     * security framework on UOS triggers a blocking dialog even for our probe call.
     * Reading {@code /etc/os-release} is a passive check that does not touch any
     * native libraries.
     *
     * <p>Users can always override the choice with {@code -Dprism.order=es2}
     * (force hardware) or {@code -Dprism.order=sw} (force software) on the command line.
     *
     * @since 3.0.0
     */
    private static void primeJavaFxRendering() {
        if (System.getProperty("prism.order") != null) {
            return; // user has explicitly chosen a pipeline
        }

        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("linux")) {
            return; // macOS and Windows don't block unsigned native libraries
        }

        if (isHardenedLinuxDistro()) {
            System.setProperty("prism.order", "sw");
        }
    }

    /**
     * Returns {@code true} if {@code /etc/os-release} identifies a Linux distribution
     * known to enforce system-level signature verification on native libraries (UOS,
     * Deepin, Kylin). These distros block JavaFX's unsigned hardware rendering libs.
     */
    private static boolean isHardenedLinuxDistro() {
        try {
            Path osRelease = Path.of("/etc/os-release");
            if (!Files.isRegularFile(osRelease)) return false;
            String content = Files.readString(osRelease).toLowerCase();
            return content.contains("uos")
                || content.contains("deepin")
                || content.contains("kylin");
        } catch (Exception ignored) {
            return false;
        }
    }
}
