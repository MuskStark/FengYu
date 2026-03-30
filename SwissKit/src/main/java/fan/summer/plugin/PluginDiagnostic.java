package fan.summer.plugin;

import fan.summer.api.KitPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Plugin loading diagnostic tool.
 *
 * Usage: Temporarily call at the very beginning of Main.java:
 *   PluginDiagnostic.run();
 *
 * This will print a complete diagnostic report to console to locate the specific reasons
 * why a plugin cannot be loaded. Delete this call after debugging is complete.
 */
public class PluginDiagnostic {

    private static final Logger logger = LoggerFactory.getLogger(PluginDiagnostic.class);

    public static void run() {
        logger.info("========== SwissKitJ Plugin Diagnostic ==========");

        // 1. Check plugin directory
        String pluginDirPath = Path.of(System.getProperty("user.dir"))
                .resolve(".swisskit").resolve("plugins")
                .toAbsolutePath().toString();

        logger.info("\n[1] Plugin directory: {}", pluginDirPath);
        File pluginDir = new File(pluginDirPath);

        if (!pluginDir.exists()) {
            logger.error("Directory does NOT exist!");
            logger.error("Fix: create directory or check PLUGIN_DIR path");
            logger.info("=================================================");
            return;
        }
        logger.info("Directory exists");

        // 2. List all JARs
        File[] jars = pluginDir.listFiles(f -> f.getName().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            logger.error("No .jar files found in plugin directory!");
            logger.error("Fix: run 'mvn package' in plugin project and copy JAR here");
            logger.info("=================================================");
            return;
        }

        logger.info("Found {} JAR(s):", jars.length);
        for (File jar : jars) {
            logger.info("  - {} ({} bytes)", jar.getName(), jar.length());
        }

        // 3. Diagnose each JAR
        for (File jar : jars) {
            logger.info("\n[3] Inspecting: {}", jar.getName());
            diagnoseJar(jar);
        }

        logger.info("=================================================");
    }

    private static void diagnoseJar(File jarFile) {
        // 3a. Check SPI file in JAR
        logger.info("  [3a] Checking META-INF/services/fan.summer.api.KitPage ...");
        String spiContent = null;
        try (JarFile jar = new JarFile(jarFile)) {
            // List all META-INF entries in JAR for debugging path issues
            logger.info("       All META-INF entries in JAR:");
            Enumeration<JarEntry> entries = jar.entries();
            boolean foundSpi = false;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF")) {
                    logger.info("         {}", entry.getName());
                    if (entry.getName().equals("META-INF/services/fan.summer.api.KitPage")) {
                        foundSpi = true;
                        try (InputStream is = jar.getInputStream(entry)) {
                            spiContent = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
                        }
                    }
                }
            }
            if (!foundSpi) {
                logger.error("SPI file NOT found in JAR!");
                logger.error("Fix: ensure file exists at:");
                logger.error("     src/main/resources/META-INF/services/fan.summer.api.KitPage");
                logger.error("Also check maven-shade-plugin has ServicesResourceTransformer");
                return;
            }
            logger.info("SPI file found, content: [{}]", spiContent);
        } catch (Exception e) {
            logger.error("Failed to open JAR: {}", e.getMessage(), e);
            return;
        }

        if (spiContent == null || spiContent.isEmpty()) {
            logger.error("SPI file is empty!");
            return;
        }

        // 3b. Try loading with IsolatedClassLoader
        logger.info("  [3b] Trying to load classes with IsolatedPluginClassLoader ...");
        try {
            URL jarUrl = jarFile.toURI().toURL();
            ClassLoader appCL = KitPage.class.getClassLoader();

            // Same IsolatedPluginClassLoader logic as in PluginLoader
            URLClassLoader pluginCL = new URLClassLoader(new URL[]{jarUrl}, null) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> cached = findLoadedClass(name);
                        if (cached != null) {
                            if (resolve) resolveClass(cached);
                            return cached;
                        }
                        if (name.startsWith("fan.summer.")) return appCL.loadClass(name);
                        if (name.startsWith("java.") || name.startsWith("javax.")
                                || name.startsWith("sun.") || name.startsWith("com.sun."))
                            return Class.forName(name, resolve, null);
                        try {
                            Class<?> c = findClass(name);
                            if (resolve) resolveClass(c);
                            return c;
                        } catch (ClassNotFoundException e) {
                            return appCL.loadClass(name);
                        }
                    }
                }
            };

            // Try loading each class declared in SPI file line by line
            for (String className : spiContent.split("\\r?\\n")) {
                className = className.trim();
                if (className.isEmpty() || className.startsWith("#")) continue;

                logger.info("  Loading class: {}", className);
                try {
                    Class<?> clazz = pluginCL.loadClass(className);
                    logger.info("  Class loaded: {}", clazz.getName());
                    logger.info("    ClassLoader: {}", clazz.getClassLoader());

                    // Check if implements KitPage
                    boolean isKitPage = KitPage.class.isAssignableFrom(clazz);
                    logger.info("    implements KitPage: {}", isKitPage);
                    if (!isKitPage) {
                        logger.error("Does NOT implement KitPage - ClassLoader mismatch?");
                        logger.error("  KitPage loaded by: {}", KitPage.class.getClassLoader());
                        continue;
                    }

                    // Check @SwissKitPage annotation
                    fan.summer.annoattion.SwissKitPage ann =
                            clazz.getAnnotation(fan.summer.annoattion.SwissKitPage.class);
                    if (ann == null) {
                        logger.error("@SwissKitPage annotation NOT found!");
                        logger.error("  Add @SwissKitPage annotation to {}", className);
                    } else {
                        logger.info("@SwissKitPage: menuName=[{}] order={} visible={}",
                                ann.menuName(), ann.order(), ann.visible());
                    }

                    // Try to instantiate
                    try {
                        KitPage instance = (KitPage) clazz.getDeclaredConstructor().newInstance();
                        logger.info("Instantiation OK: {}", instance.getMenuName());
                    } catch (Exception e) {
                        logger.error("Instantiation FAILED: {}", e.getMessage(), e);
                    }

                } catch (ClassNotFoundException e) {
                    logger.error("ClassNotFoundException: {}", className, e);
                } catch (Exception e) {
                    logger.error("Error loading {}: {}", className, e.getMessage(), e);
                }
            }

            // 3c. Full walkthrough with ServiceLoader
            logger.info("  [3c] ServiceLoader.load(KitPage.class, pluginCL) ...");
            ServiceLoader<KitPage> sl = ServiceLoader.load(KitPage.class, pluginCL);
            int count = 0;
            for (KitPage page : sl) {
                logger.info("ServiceLoader found: {} | CL={}", page.getClass().getName(), page.getClass().getClassLoader());
                count++;
            }
            if (count == 0) {
                logger.error("ServiceLoader returned NOTHING");
                logger.error("  Most likely: SPI file missing or class not loadable");
            }

        } catch (Exception e) {
            logger.error("Diagnostic failed: {}", e.getMessage(), e);
        }
    }
}