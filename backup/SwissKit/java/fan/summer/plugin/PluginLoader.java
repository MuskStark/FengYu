package fan.summer.plugin;

import fan.summer.annoattion.SwissKitPage;
import fan.summer.plugin.dto.DeployResult;
import fan.summer.plugin.dto.PluginMetadata;
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
import java.util.stream.Collectors;

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
     * Tracks plugin state including ClassLoader, enabled status, and metadata.
     */
    private static final ConcurrentHashMap<String, PluginState> pluginStates = new ConcurrentHashMap<>();

    /**
     * Holds plugin state including ClassLoader, enabled status, and metadata.
     */
    public static class PluginState {
        private final URLClassLoader classLoader;
        private boolean isEnabled;
        private final String pluginName;
        private final String pluginVersion;

        PluginState(URLClassLoader classLoader, String pluginName, String pluginVersion) {
            this.classLoader = classLoader;
            this.isEnabled = true;
            this.pluginName = pluginName;
            this.pluginVersion = pluginVersion;
        }

        public URLClassLoader getClassLoader() {
            return classLoader;
        }

        public boolean isEnabled() {
            return isEnabled;
        }

        public void setEnabled(boolean enabled) {
            this.isEnabled = enabled;
        }

        public String getPluginName() {
            return pluginName;
        }

        public String getPluginVersion() {
            return pluginVersion;
        }
    }

    public static List<Object> loadFromPluginDir() {
        return loadFromDir(Path.of(PLUGIN_DIR).toFile());
    }

    /**
     * Disables a plugin without unloading its ClassLoader.
     * The plugin pages will be hidden from the sidebar but remain loaded in memory.
     *
     * @param jarName the JAR file name
     * @return true if plugin was found and disabled
     */
    public static boolean disablePlugin(String jarName) {
        PluginState state = pluginStates.get(jarName);
        if (state != null) {
            state.setEnabled(false);
            logger.info("Plugin disabled (not unloaded): {}", jarName);
            return true;
        }
        return false;
    }

    /**
     * Re-enables a previously disabled plugin.
     *
     * @param jarName the JAR file name
     * @return true if plugin was found and enabled
     */
    public static boolean enablePlugin(String jarName) {
        PluginState state = pluginStates.get(jarName);
        if (state != null) {
            state.setEnabled(true);
            logger.info("Plugin enabled: {}", jarName);
            return true;
        }
        return false;
    }

    /**
     * Returns whether a plugin is currently enabled.
     *
     * @param jarName the JAR file name
     * @return true if enabled or not found (assumes enabled by default)
     */
    public static boolean isPluginEnabled(String jarName) {
        PluginState state = pluginStates.get(jarName);
        return state == null || state.isEnabled();
    }

    /**
     * Returns list of all registered plugin JAR names (regardless of enabled state).
     */
    public static List<String> getRegisteredPlugins() {
        return new ArrayList<>(pluginStates.keySet());
    }

    /**
     * Returns list of enabled plugin JAR names.
     */
    public static List<String> getEnabledPlugins() {
        return pluginStates.entrySet().stream()
                .filter(e -> e.getValue().isEnabled())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Gets plugin state by JAR name.
     *
     * @param jarName the JAR file name
     * @return PluginState or null if not found
     */
    public static PluginState getPluginState(String jarName) {
        return pluginStates.get(jarName);
    }

    /**
     * Checks if a page from an external plugin is enabled.
     * Pages from built-in classes (loaded by app classloader) are always considered enabled.
     *
     * @param page the page instance
     * @return true if enabled or not an external plugin page
     */
    public static boolean isPageEnabled(Object page) {
        ClassLoader pageClassLoader = page.getClass().getClassLoader();
        ClassLoader appClassLoader = PluginLoader.class.getClassLoader();

        // Built-in pages are always enabled
        if (pageClassLoader == appClassLoader) {
            return true;
        }

        // Find the plugin state for this page's classloader
        for (PluginState state : pluginStates.values()) {
            if (state.getClassLoader() == pageClassLoader) {
                return state.isEnabled();
            }
        }

        // Unknown classloader, assume enabled
        return true;
    }

    /**
     * Closes the ClassLoader for a plugin JAR and removes it from tracking.
     * Must be called before deleting a plugin JAR file.
     *
     * @param jarName the JAR file name (e.g., "MyPlugin-1.0.0.jar")
     * @return true if the ClassLoader was found and closed, false otherwise
     */
    public static boolean unloadPlugin(String jarName) {
        PluginState state = pluginStates.remove(jarName);
        if (state != null) {
            try {
                state.getClassLoader().close();
                logger.info("Closed ClassLoader for plugin: {}", jarName);
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

        // Extract plugin metadata from JAR
        String pluginName = null;
        String pluginVersion = null;
        try {
            fan.summer.plugin.dto.PluginMetadata metadata = extractMetadata(jarFile);
            pluginName = metadata.getPluginName();
            pluginVersion = metadata.getPluginVersion();
        } catch (Exception e) {
            logger.warn("Could not extract plugin metadata from {}: {}", jarFile.getName(), e.getMessage());
        }

        try {
            URL jarUrl = jarFile.toURI().toURL();
            IsolatedPluginClassLoader pluginClassLoader = new IsolatedPluginClassLoader(
                    new URL[]{jarUrl}, appClassLoader
            );

            // Track the classloader with plugin state
            PluginState state = new PluginState(pluginClassLoader, pluginName, pluginVersion);
            pluginStates.put(jarFile.getName(), state);

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
     * Extracts plugin metadata from a JAR file by loading the class with @SwissKitPage annotation.
     */
    private static fan.summer.plugin.dto.PluginMetadata extractMetadata(File jarFile) {
        fan.summer.plugin.dto.PluginMetadata metadata = new fan.summer.plugin.dto.PluginMetadata();
        metadata.setPluginName(jarFile.getName().replace(".jar", ""));

        try {
            URL jarUrl = jarFile.toURI().toURL();
            ClassLoader appClassLoader = PluginLoader.class.getClassLoader();
            URLClassLoader tempClassLoader = new IsolatedPluginClassLoader(
                    new URL[]{jarUrl}, appClassLoader
            );

            Set<String> pageClasses = discoverPageClasses(jarFile);
            for (String className : pageClasses) {
                try {
                    Class<?> clazz = tempClassLoader.loadClass(className);
                    SwissKitPage annotation = clazz.getAnnotation(SwissKitPage.class);
                    if (annotation != null) {
                        metadata.setPluginName(annotation.pluginName());
                        metadata.setPluginVersion(annotation.pluginVersion());
                        metadata.setMenuName(annotation.menuName());
                        metadata.setMenuTooltip(annotation.menuTooltip());
                        metadata.setIconPath(annotation.iconPath());
                        metadata.setVisible(annotation.visible());
                        metadata.setOrder(annotation.order());
                        metadata.setJarName(jarFile.getName());
                        break;
                    }
                } catch (Exception e) {
                    // Continue trying other classes
                }
            }
            tempClassLoader.close();
        } catch (Exception e) {
            logger.warn("Failed to extract metadata from JAR: {}", jarFile.getName());
        }
        return metadata;
    }

    /**
     * Deploys a plugin JAR and returns both pages and metadata.
     *
     * @param jarFile the JAR file to deploy
     * @return DeployResult containing pages and metadata
     */
    public static DeployResult deployPluginWithMetadata(File jarFile) {
        if (jarFile == null || !jarFile.exists() || !jarFile.getName().toLowerCase().endsWith(".jar")) {
            logger.warn("Invalid plugin file for deploy: {}", jarFile);
            return new DeployResult(Collections.emptyList(), new fan.summer.plugin.dto.PluginMetadata());
        }

        String jarName = jarFile.getName();

        // If already loaded, unload first for hot-reload
        if (pluginStates.containsKey(jarName)) {
            logger.info("Plugin {} already loaded, hot-reloading...", jarName);
            unloadPlugin(jarName);
        }

        List<Object> pages = loadFromJar(jarFile);
        fan.summer.plugin.dto.PluginMetadata metadata = extractMetadata(jarFile);

        return new DeployResult(pages, metadata);
    }

    /**
     * Deploys (loads) a plugin JAR that has already been copied to PLUGIN_DIR.
     * If the plugin is already loaded, it will be hot-reloaded (old ClassLoader closed, new one created).
     *
     * @param jarFile the JAR file to deploy (must exist in PLUGIN_DIR)
     * @return List of loaded page instances, or empty list on failure
     */
    public static List<Object> deployPlugin(File jarFile) {
        return deployPluginWithMetadata(jarFile).getPages();
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
}