package fan.summer.plugin;

import fan.summer.annoattion.SwissKitPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Plugin loading diagnostic tool.
 *
 * NOTE: This diagnostic tool is deprecated as the plugin loading mechanism
 * has been changed from SPI-based (KitPage interface) to annotation-based (@SwissKitPage).
 *
 * @deprecated Use annotation-based discovery instead.
 */
@Deprecated
public class PluginDiagnostic {

    private static final Logger logger = LoggerFactory.getLogger(PluginDiagnostic.class);

    public static void run() {
        logger.info("========== SwissKitJ Plugin Diagnostic ==========");
        logger.info("NOTE: This diagnostic is deprecated. Plugin loading now uses @SwissKitPage annotation.");

        // 1. Check plugin directory
        String pluginDirPath = Path.of(System.getProperty("user.dir"))
                .resolve(".swisskit").resolve("plugins")
                .toAbsolutePath().toString();

        logger.info("\n[1] Plugin directory: {}", pluginDirPath);
        File pluginDir = new File(pluginDirPath);

        if (!pluginDir.exists()) {
            logger.error("Directory does NOT exist!");
            logger.info("=================================================");
            return;
        }
        logger.info("Directory exists");

        // 2. List all JARs
        File[] jars = pluginDir.listFiles(f -> f.getName().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            logger.info("No .jar files found in plugin directory");
            logger.info("=================================================");
            return;
        }

        logger.info("Found {} JAR(s):", jars.length);
        for (File jar : jars) {
            logger.info("  - {} ({} bytes)", jar.getName(), jar.length());
        }

        // 3. Diagnose each JAR for @SwissKitPage annotated classes
        for (File jar : jars) {
            logger.info("\n[3] Inspecting: {}", jar.getName());
            diagnoseJar(jar);
        }

        logger.info("=================================================");
    }

    private static void diagnoseJar(File jarFile) {
        logger.info("  Scanning for classes with @SwissKitPage annotation...");
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    String className = entry.getName().replace('/', '.').substring(0, entry.getName().length() - 6);
                    logger.info("  Found class: {}", className);
                    // Note: Actual annotation checking requires loading the class
                }
            }
        } catch (Exception e) {
            logger.error("Failed to scan JAR: {}", e.getMessage(), e);
        }
    }
}