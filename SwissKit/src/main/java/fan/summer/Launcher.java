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
     * Probes JavaFX native rendering support before the toolkit initializes.
     *
     * <p>On macOS and Windows, hardware rendering always works — this method does nothing.
     * On Linux, it probes whether the JavaFX Prism ES2 native library ({@code libprism_es2.so})
     * can be loaded. On hardened Linux distributions (e.g., UOS/Deepin), unsigned native
     * libraries are blocked by system security verification, which causes the application
     * to crash when JavaFX tries to initialize the hardware pipeline. If the probe fails,
     * this method sets {@code prism.order=sw} to fall back to pure-Java software rendering.
     *
     * <p>If the probe succeeds (or the platform is not Linux), JavaFX uses its default
     * rendering pipeline (hardware-accelerated ES2). Users can override the choice by
     * passing {@code -Dprism.order=es2} or {@code -Dprism.order=sw} on the command line.
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

        // Probe: try loading the Prism ES2 native library from the JavaFX cache.
        // If loading fails (UOS security verification, missing GPU drivers, etc.),
        // fall back to software rendering which requires no native libraries.
        Path cacheDir = Path.of(System.getProperty("user.home"), ".openjfx", "cache");
        if (!Files.isDirectory(cacheDir)) return;

        try (var stream = Files.walk(cacheDir, 3)) {
            stream.filter(p -> p.getFileName().toString().equals("libprism_es2.so"))
                  .findFirst()
                  .ifPresent(Launcher::probePrismLib);
        } catch (Exception ignored) {
            // Can't probe — let JavaFX use its default pipeline
        }
    }

    private static void probePrismLib(Path prismLib) {
        try {
            System.load(prismLib.toAbsolutePath().toString());
            // Probe succeeded — native rendering available, keep default pipeline
        } catch (Throwable t) {
            // Probe failed (UOS security, missing GPU, etc.) — fall back to software
            System.setProperty("prism.order", "sw");
        }
    }
}
