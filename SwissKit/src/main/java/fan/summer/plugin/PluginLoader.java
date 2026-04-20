package fan.summer.plugin;

import fan.summer.annoattion.SwissKitPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Dynamic plugin loader.
 * Scans JAR files from specified directory and loads KitPage implementations via @SwissKitPage annotation.
 *
 * <p>Plugin JAR requirements:</p>
 * <ul>
 *   <li>Class annotated with {@link SwissKitPage}</li>
 *   <li>Page class must have a no-arg constructor</li>
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

    /**
     * Tracks plugin JAR file path → its ClassLoader.
     * Used to release file handles when uninstalling plugins.
     */
    private static final ConcurrentHashMap<String, URLClassLoader> pluginClassLoaders = new ConcurrentHashMap<>();

    public static List<Object> loadFromPluginDir() {
        return loadFromDir(Path.of(PLUGIN_DIR).toFile());
    }

    /**
     * Closes the ClassLoader for a plugin JAR and removes it from tracking.
     * Must be called before deleting a plugin JAR file.
     *
     * @param jarName the JAR file name (e.g., "MyPlugin-1.0.0.jar")
     * @return true if the ClassLoader was found and closed, false otherwise
     */
    public static boolean unloadPlugin(String jarName) {
        URLClassLoader classLoader = pluginClassLoaders.remove(jarName);
        if (classLoader != null) {
            try {
                classLoader.close();
                logger.info("Closed ClassLoader for plugin: {}", jarName);
                // Force GC to release JAR file handles - critical for Windows file deletion
                System.gc();
                return true;
            } catch (IOException e) {
                logger.warn("Failed to close ClassLoader for plugin {}: {}", jarName, e.getMessage());
                return false;
            }
        }
        logger.debug("No ClassLoader found for plugin: {}", jarName);
        return false;
    }

    /**
     * Deploys (loads) a plugin JAR that has already been copied to PLUGIN_DIR.
     * If the plugin is already loaded, it will be hot-reloaded (old ClassLoader closed, new one created).
     *
     * @param jarFile the JAR file to deploy (must exist in PLUGIN_DIR)
     * @return List of loaded page instances, or empty list on failure
     */
    public static List<Object> deployPlugin(File jarFile) {
        if (jarFile == null || !jarFile.exists() || !jarFile.getName().toLowerCase().endsWith(".jar")) {
            logger.warn("Invalid plugin file for deploy: {}", jarFile);
            return Collections.emptyList();
        }

        String jarName = jarFile.getName();

        // If already loaded, unload first for hot-reload
        if (pluginClassLoaders.containsKey(jarName)) {
            logger.info("Plugin {} already loaded, hot-reloading...", jarName);
            unloadPlugin(jarName);
        }

        return loadFromJar(jarFile);
    }

    /**
     * Hot-reloads an already-loaded plugin JAR.
     * Unloads the old ClassLoader and loads the new version from disk.
     *
     * @param jarName the JAR file name (e.g., "MyPlugin-1.0.0.jar")
     * @return List of reloaded pages, or empty list if JAR not found or reload fails
     */
    public static List<Object> reloadPlugin(String jarName) {
        File jarFile = new File(PLUGIN_DIR, jarName);
        if (!jarFile.exists()) {
            logger.warn("Plugin JAR not found for reload: {}", jarName);
            return Collections.emptyList();
        }

        unloadPlugin(jarName);
        return loadFromJar(jarFile);
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

    public static List<Object> loadFromDir(File dir) {
        List<Object> result = new ArrayList<>();

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

        logger.info("Total plugin pages loaded from {}: {}", dir.getAbsolutePath(), result.size());
        return result;
    }

    public static List<Object> loadFromJar(File jarFile) {
        List<Object> result = new ArrayList<>();

        if (!jarFile.exists() || !jarFile.getName().endsWith(".jar")) {
            logger.warn("Invalid plugin file: {}", jarFile.getAbsolutePath());
            return result;
        }

        ClassLoader appClassLoader = PluginLoader.class.getClassLoader();

        try {
            URL jarUrl = jarFile.toURI().toURL();
            URLClassLoader pluginClassLoader = new IsolatedPluginClassLoader(
                    new URL[]{jarUrl}, appClassLoader
            );

            // Track the classloader so it can be closed on uninstall
            pluginClassLoaders.put(jarFile.getName(), pluginClassLoader);

            // First scan: discover classes with @SwissKitPage annotation
            Set<String> pageClassNames = discoverPageClasses(jarFile);

            // Second pass: instantiate and validate pages
            for (String className : pageClassNames) {
                try {
                    Class<?> clazz = pluginClassLoader.loadClass(className);

                    // Skip if loaded by app classloader (shouldn't happen with isolation)
                    if (clazz.getClassLoader() == appClassLoader) {
                        logger.debug("Skipped built-in class: {}", className);
                        continue;
                    }

                    SwissKitPage annotation = clazz.getAnnotation(SwissKitPage.class);
                    if (annotation == null) {
                        logger.debug("Plugin page skipped (no @SwissKitPage): {}", className);
                        continue;
                    }

                    if (!annotation.visible()) {
                        logger.debug("Plugin page skipped (invisible): {}", className);
                        continue;
                    }

                    // Instantiate the page
                    Object page = clazz.getDeclaredConstructor().newInstance();
                    result.add(page);
                    logger.info("Plugin page loaded: [{}] from {}", annotation.menuName(), jarFile.getName());

                } catch (Exception e) {
                    logger.error("Failed to load plugin page: {} - {}", className, e.getMessage());
                }
            }

        } catch (Exception e) {
            logger.error("Failed to load plugin JAR: {}", jarFile.getAbsolutePath(), e);
        }

        return result;
    }

    /**
     * Scans a JAR file for classes that have @SwissKitPage annotation.
     * Uses JarInputStream to avoid needing a ClassLoader for initial scan.
     */
    private static Set<String> discoverPageClasses(File jarFile) {
        Set<String> pageClasses = new HashSet<>();
        try (JarInputStream jar = new JarInputStream(jarFile.toURI().toURL().openStream())) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    String className = name.replace('/', '.').substring(0, name.length() - 6);
                    pageClasses.add(className);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to scan JAR for classes: {}", jarFile.getName());
        }
        return pageClasses;
    }

    /**
     * Isolated plugin ClassLoader.
     *
     * <p>Loading strategy (break default parent delegation):</p>
     * <pre>
     * Class name starts with fan.summer.?
     *   YES → delegate to parent (main app) ClassLoader   ← annotations/UI components share same Class object
     *   NO  → first search in plugin JAR itself          ← plugin implementation classes isolated
     *         if not found, then delegate to parent ClassLoader
     * </pre>
     */
    private static class IsolatedPluginClassLoader extends URLClassLoader {

        private final ClassLoader appClassLoader;

        public IsolatedPluginClassLoader(URL[] urls, ClassLoader appClassLoader) {
            super(urls, null);
            this.appClassLoader = appClassLoader;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> cached = findLoadedClass(name);
                if (cached != null) {
                    if (resolve) resolveClass(cached);
                    return cached;
                }

                // fan.summer.* → delegate to main app ClassLoader
                if (name.startsWith("fan.summer.")) {
                    return appClassLoader.loadClass(name);
                }

                // JDK core classes → delegate to app ClassLoader
                if (name.startsWith("java.") || name.startsWith("javax.")
                        || name.startsWith("sun.") || name.startsWith("com.sun.")) {
                    return appClassLoader.loadClass(name);
                }

                // Try appClassLoader first
                try {
                    return appClassLoader.loadClass(name);
                } catch (ClassNotFoundException e) {
                    // Not in appClassLoader, try plugin JAR
                }

                // Plugin's own classes and third-party libraries → load from plugin JAR
                try {
                    Class<?> c = findClass(name);
                    if (resolve) resolveClass(c);
                    return c;
                } catch (ClassNotFoundException e) {
                    return appClassLoader.loadClass(name);
                }
            }
        }
    }
}