package fan.summer.plugin;

import fan.summer.api.KitPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Dynamic plugin loader.
 * Scans JAR files from specified directory and loads KitPage implementations via SPI.
 *
 * <p>Plugin JAR requirements:</p>
 * <ul>
 *   <li>Implement {@link KitPage} interface</li>
 *   <li>Class annotated with {@link fan.summer.annoattion.SwissKitPage}</li>
 *   <li>Declare implementation class fully qualified name in META-INF/services/fan.summer.api.KitPage file within the JAR</li>
 * </ul>
 */
public class PluginLoader {

    private static final Logger logger = LoggerFactory.getLogger(PluginLoader.class);

    /**
     * Plugin directory, located at .swisskit/plugins under working directory
     */
    public static String PLUGIN_DIR = Path.of(System.getProperty("user.dir"))
            .resolve(".swisskit")
            .resolve("plugins")
            .toAbsolutePath()
            .toString()
            .replace("\\", "/");

    public static List<KitPage> loadFromPluginDir() {
        return loadFromDir(Path.of(PLUGIN_DIR).toFile());
    }

    /**
     * Returns list of installed plugin JAR files.
     *
     * @return List of File objects for each JAR in plugin directory
     */
    public static List<File> getInstalledPlugins() {
        File dir = Path.of(PLUGIN_DIR).toFile();
        if (!dir.exists()) {
            return Collections.emptyList();
        }
        File[] jars = dir.listFiles(f -> f.getName().endsWith(".jar"));
        return jars != null ? Arrays.asList(jars) : Collections.emptyList();
    }

    public static List<KitPage> loadFromDir(File dir) {
        List<KitPage> result = new ArrayList<>();

        if (!dir.exists()) {
            if (dir.mkdirs()) {
                logger.info("Plugin directory created: {}", dir.getAbsolutePath());
            } else {
                logger.warn("Failed to create plugin directory: {}", dir.getAbsolutePath());
            }
            return result;
        }

        File[] jars = dir.listFiles(f -> f.getName().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            logger.info("No plugin JARs found in: {}", dir.getAbsolutePath());
            return result;
        }

        for (File jar : jars) {
            result.addAll(loadFromJar(jar));
        }

        logger.info("Total plugin KitPage(s) loaded from {}: {}", dir.getAbsolutePath(), result.size());
        return result;
    }

    public static List<KitPage> loadFromJar(File jarFile) {
        List<KitPage> result = new ArrayList<>();

        if (!jarFile.exists() || !jarFile.getName().endsWith(".jar")) {
            logger.warn("Invalid plugin file: {}", jarFile.getAbsolutePath());
            return result;
        }

        try {
            URL jarUrl = jarFile.toURI().toURL();

            // Fix duplicate loading: use isolated ClassLoader
            // Parent loader uses KitPage.class.getClassLoader() (i.e., main app's AppClassLoader),
            // and overrides loadClass to prioritize loading plugin classes from the plugin JAR,
            // without triggering parent chain's SPI scan.
            // fan.summer.* interfaces/annotations/UI components are still delegated to parent loader
            // to ensure consistent instanceof behavior.
            ClassLoader appClassLoader = KitPage.class.getClassLoader();
            URLClassLoader pluginClassLoader = new IsolatedPluginClassLoader(
                    new URL[]{jarUrl}, appClassLoader
            );

            ServiceLoader<KitPage> loader = ServiceLoader.load(KitPage.class, pluginClassLoader);

            for (KitPage page : loader) {
                // Fix duplicate loading: filter out built-in pages loaded by main ClassLoader
                if (page.getClass().getClassLoader() == appClassLoader) {
                    logger.debug("Skipped built-in page (loaded by app classloader): {}", page.getClass().getName());
                    continue;
                }

                fan.summer.annoattion.SwissKitPage annotation =
                        page.getClass().getAnnotation(fan.summer.annoattion.SwissKitPage.class);

                if (annotation == null) {
                    logger.debug("Plugin page skipped (no @SwissKitPage): {}", page.getClass().getName());
                    continue;
                }
                if (!annotation.visible()) {
                    logger.debug("Plugin page skipped (invisible): {}", page.getClass().getName());
                    continue;
                }

                result.add(page);
                logger.info("Plugin KitPage loaded: [{}] from {}", annotation.menuName(), jarFile.getName());
            }

        } catch (Exception e) {
            logger.error("Failed to load plugin JAR: {}", jarFile.getAbsolutePath(), e);
        }

        return result;
    }

    /**
     * Isolated plugin ClassLoader.
     *
     * <p>Loading strategy (break default parent delegation):</p>
     * <pre>
     * Class name starts with fan.summer.?
     *   YES → delegate to parent (main app) ClassLoader   ← interfaces/annotations/UI components share same Class object
     *   NO  → first search in plugin JAR itself          ← plugin implementation classes isolated, no parent chain SPI duplicate scan
     *         if not found, then delegate to parent ClassLoader
     * </pre>
     *
     * <p>Why fan.summer.* are delegated to parent loader:</p>
     * <ul>
     *   <li>fan.summer.api.KitPage — interface must be same Class, otherwise instanceof fails</li>
     *   <li>fan.summer.annoattion.* — annotation must be same Class, otherwise getAnnotation returns null</li>
     *   <li>fan.summer.plugin.swisskit.hpl.ui.components.* — plugins can directly use main app's UI components (e.g., GradientProgressBar)</li>
     * </ul>
     */
    private static class IsolatedPluginClassLoader extends URLClassLoader {

        private final ClassLoader appClassLoader;

        public IsolatedPluginClassLoader(URL[] urls, ClassLoader appClassLoader) {
            // Parent loader set to null (bootstrap) for complete isolation,
            // then manually delegate via appClassLoader
            super(urls, null);
            this.appClassLoader = appClassLoader;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                // 1. Already cached
                Class<?> cached = findLoadedClass(name);
                if (cached != null) {
                    if (resolve) resolveClass(cached);
                    return cached;
                }

                // 2. fan.summer.* → delegate to main app ClassLoader (interfaces/annotations/UI components shared)
                if (name.startsWith("fan.summer.")) {
                    return appClassLoader.loadClass(name);
                }

                // 3. JDK core classes → delegate to app ClassLoader (supports Java 9+ modules like java.net.http)
                if (name.startsWith("java.") || name.startsWith("javax.")
                        || name.startsWith("sun.") || name.startsWith("com.sun.")) {
                    return appClassLoader.loadClass(name);
                }

                // 4. All other classes (plugin's own dto/service/util and third-party libs like fastjson2):
                //    Try appClassLoader first → then plugin JAR → then appClassLoader again as last resort.
                //    This ensures classes in plugin JAR (dto.*, fastjson2 ASM-generated classes, etc.) are found
                //    even when called via Class.forName(name, resolve, null) from third-party library internals.
                try {
                    return appClassLoader.loadClass(name);
                } catch (ClassNotFoundException e) {
                    // Not in appClassLoader, try plugin JAR
                }

                // 5. Others (plugin's own classes, third-party libraries) → prioritize loading from plugin JAR
                try {
                    Class<?> c = findClass(name);
                    if (resolve) resolveClass(c);
                    return c;
                } catch (ClassNotFoundException e) {
                    // 5. Not found in plugin JAR → then try main app ClassLoader
                    return appClassLoader.loadClass(name);
                }
            }
        }
    }
}