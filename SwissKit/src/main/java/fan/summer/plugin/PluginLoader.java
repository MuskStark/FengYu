package fan.summer.plugin;

import fan.summer.api.PluginContext;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.i18n.I18n;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scans a plugins/ directory for JAR files, loads {@link SwissKitJPlugin} implementations
 * via {@link ServiceLoader}, and watches for hot-reload on file changes.
 *
 * <p>This class is responsible for discovering external plugins packaged as JAR files
 * in the configured plugins directory. Each JAR is loaded via a dedicated
 * {@link URLClassLoader} to allow proper unloading on JAR removal. Plugin discovery
 * uses the Java ServiceLoader mechanism, expecting implementations of
 * {@code fan.summer.api.SwissKitJPlugin} to be declared in
 * {@code META-INF/services/fan.summer.api.SwissKitJPlugin} within the JAR.</p>
 *
 * <p>After startup, a {@link WatchService} is registered on the plugins directory to
 * detect {@code ENTRY_CREATE}, {@code ENTRY_DELETE}, and {@code ENTRY_MODIFY} events.
 * New or modified JARs trigger (re)loading; deleted JARs trigger unloading.
 * All registry updates are pushed onto the JavaFX Application Thread via
 * {@link Platform#runLater} to ensure thread safety with the UI.</p>
 *
 * <p>This class is thread-safe and designed to be operated from a single {@code start()}
 * / {@code stop()} lifecycle. The actual plugins directory is resolved via
 * {@link #resolvePluginsDir()}; callers may pass any directory to the constructor
 * to override the default location.</p>
 *
 * @see PluginRegistry
 * @see SwissKitJPlugin
 * @since 1.0
 */
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    /**
     * Returns the canonical plugin directory path resolved from the current working directory.
     *
     * <p>The path is {@code <user.dir>/.swisskit/plugin/}, created automatically on first scan
     * if it does not yet exist.</p>
     *
     * @return the absolute path to the plugins directory
     * @since 1.0
     */
    public static Path resolvePluginsDir() {
        return Path.of(System.getProperty("user.dir"), ".swisskit", "plugin");
    }

    private final Path        pluginsDir;
    private PluginRegistry registry;
    private WatchService      watchService;
    private Thread            watchThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Maps JAR path → the ClassLoader opened for it (for unloading) */
    private final Map<Path, URLClassLoader> openLoaders = new ConcurrentHashMap<>();

    /** Maps JAR path → plugins loaded from that JAR */
    private final Map<Path, List<SwissKitJPlugin>> jarPlugins = new ConcurrentHashMap<>();

    /**
     * Constructs a PluginLoader that will scan the given directory for plugin JARs.
     *
     * @param pluginsDir the directory to scan for JAR files; may be any directory,
     *                    not necessarily the one returned by {@link #resolvePluginsDir()}
     * @since 1.0
     */
    public PluginLoader(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    // Called by PluginRegistry after construction
    void setRegistry(PluginRegistry registry) {
        this.registry = registry;
    }

    // ── Lifecycle ────────────────────────────────────────────────

    /**
     * Starts the plugin loader: creates the plugins directory if absent,
     * performs an initial scan of all existing JARs, and starts the directory watcher
     * for hot-reload events.
     *
     * <p>This method is idempotent: calling it when the loader is already running
     * is a no-op. This method must be called before {@link #stop()}.</p>
     *
     * @see #stop()
     * @since 1.0
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.debug("PluginLoader already running, ignoring duplicate start()");
            return;
        }
        log.info("Starting plugin loader, directory={}", pluginsDir.toAbsolutePath());

        // Ensure plugin directory exists
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            log.warn("Cannot create plugin directory {}: {}", pluginsDir, e.getMessage());
        }

        // Initial scan
        scanAll();

        // Watch for JAR add/remove
        try {
            if (Files.isDirectory(pluginsDir)) {
                watchService = FileSystems.getDefault().newWatchService();
                pluginsDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
                );
                watchThread = new Thread(this::watchLoop, "plugin-watcher");
                watchThread.setDaemon(true);
                watchThread.start();
                log.info("Plugin directory watcher active");
            } else {
                log.warn("Plugin directory does not exist, hot-reload disabled: {}", pluginsDir);
            }
        } catch (IOException e) {
            log.warn("Cannot start plugin watcher: {}", e.getMessage(), e);
        }
    }

    /**
     * Stops the plugin loader: interrupts the directory watcher, unloads all loaded
     * plugin JARs, and releases resources.
     *
     * <p>After this method returns all plugins previously loaded from JARs will have
     * been unloaded via {@link #unloadJar(Path)}. This method is idempotent.</p>
     *
     * @see #start()
     * @since 1.0
     */
    public void stop() {
        log.info("Stopping plugin loader");
        running.set(false);
        try {
            if (watchService != null) watchService.close();
        } catch (IOException ignored) {}
        if (watchThread != null) watchThread.interrupt();

        // Unload all plugins
        List<Path> jars = new ArrayList<>(jarPlugins.keySet());
        log.debug("Unloading {} JAR(s) on shutdown", jars.size());
        jars.forEach(this::unloadJar);
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Returns the JAR file path that loaded the given plugin, or {@code null}
     * if the plugin was not loaded from a JAR (e.g. a built-in tool).
     *
     * @param plugin the plugin to look up; must not be {@code null}
     * @return the JAR path, or {@code null} if not found
     * @since 3.0
     */
    public Path findJarPath(SwissKitJPlugin plugin) {
        for (Map.Entry<Path, List<SwissKitJPlugin>> entry : jarPlugins.entrySet()) {
            if (entry.getValue().contains(plugin)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Uninstalls the given plugin by unloading its JAR and deleting the file
     * from the plugins directory.
     *
     * <p>This method first unloads the JAR via {@link #unloadJar(Path)} (which
     * closes the ClassLoader and removes the plugin from the registry), then
     * deletes the JAR file from disk. The directory watcher will see the file
     * deletion, but the second {@code unloadJar} call is a harmless no-op
     * because the JAR was already removed from the internal maps.</p>
     *
     * <p>If the file is still locked by the OS after closing the ClassLoader,
     * deletion is retried with short delays. If all retries fail, the file is
     * marked for deletion on JVM exit.</p>
     *
     * @param plugin the plugin to uninstall; must not be {@code null}
     * @throws IllegalArgumentException if the plugin's JAR cannot be found
     * @since 3.0
     */
    public void uninstallPlugin(SwissKitJPlugin plugin) {
        Path jar = findJarPath(plugin);
        if (jar == null) {
            throw new IllegalArgumentException("No JAR found for plugin: " + plugin.getId());
        }
        log.info("Uninstalling plugin: id={}, jar={}", plugin.getId(), jar.getFileName());
        unloadJar(jar);

        // Retry deletion — the ClassLoader is closed but the OS may take a moment to release the file handle
        boolean deleted = deleteWithRetry(jar, 5, 300);
        if (deleted) {
            log.info("Deleted plugin JAR: {}", jar.getFileName());
        } else {
            log.warn("JAR still locked after retries, marking for deleteOnExit: {}", jar.getFileName());
            jar.toFile().deleteOnExit();
        }
    }

    /**
     * Attempts to delete the given path, retrying up to {@code maxAttempts} times
     * with {@code delayMs} delay between attempts. Each retry is preceded by
     * {@code System.gc()} to encourage the JVM to release native file handles.
     *
     * @param path        the file to delete
     * @param maxAttempts maximum number of delete attempts
     * @param delayMs     milliseconds to wait between attempts
     * @return {@code true} if the file was successfully deleted or did not exist
     */
    private boolean deleteWithRetry(Path path, int maxAttempts, long delayMs) {
        for (int i = 0; i < maxAttempts; i++) {
            try {
                if (Files.deleteIfExists(path)) {
                    return true;
                }
                // File doesn't exist — treat as success
                return true;
            } catch (IOException e) {
                if (i < maxAttempts - 1) {
                    log.debug("Delete attempt {}/{} failed for {}, retrying in {}ms: {}",
                            i + 1, maxAttempts, path.getFileName(), delayMs, e.getMessage());
                    System.gc(); // hint to release native file handles sooner
                    try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    // ── Scan ────────────────────────────────────────────────────

    private void scanAll() {
        if (!Files.isDirectory(pluginsDir)) {
            log.debug("Skipping plugin scan, directory does not exist: {}", pluginsDir);
            return;
        }
        int found = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (Path jar : stream) {
                if (!jarPlugins.containsKey(jar)) {
                    loadJar(jar);
                    found++;
                }
            }
        } catch (IOException e) {
            log.warn("Plugin scan failed: {}", e.getMessage(), e);
        }
        log.info("Initial plugin scan complete, processed {} JAR(s)", found);
    }

    // ── JAR load / unload ────────────────────────────────────────

    private void loadJar(Path jar) {
        log.debug("Loading plugin JAR: {}", jar.getFileName());
        try {
            // Child-first RESOURCE lookup (parent-first class loading) so a plugin's
            // own mybatis-config.xml / init.sql / mapper/** / i18n shadow the host's
            // identically-named root resources. See ChildFirstResourceClassLoader.
            URLClassLoader cl = new ChildFirstResourceClassLoader(
                new java.net.URL[]{jar.toUri().toURL()},
                getClass().getClassLoader()
            );
            ServiceLoader<SwissKitJPlugin> sl = ServiceLoader.load(SwissKitJPlugin.class, cl);

            List<SwissKitJPlugin> loaded = new ArrayList<>();
            for (SwissKitJPlugin plugin : sl) {
                loaded.add(plugin);
                PluginContext.register(plugin, cl);
                log.info("Loaded plugin: id={}, name={}, version={}, jar={}",
                        plugin.getId(), plugin.getName(), plugin.getVersion(), jar.getFileName());
            }

            if (loaded.isEmpty()) {
                log.warn("No SwissKitJPlugin services declared in {}", jar.getFileName());
                cl.close();
                return;
            }

            openLoaders.put(jar, cl);
            jarPlugins.put(jar, loaded);

            // Register plugin i18n bundle if present
            try {
                if (cl.getResource("i18n/messages.properties") != null) {
                    I18n.registerPluginBundle("i18n.messages", cl);
                    log.debug("Registered i18n bundle for plugin: {}", jar.getFileName());
                }
            } catch (Exception e) {
                log.debug("No i18n bundle found for plugin: {}", jar.getFileName());
            }

            if (registry != null) {
                Platform.runLater(() -> registry.addPlugins(loaded));
            }
        } catch (Exception e) {
            log.warn("Failed to load JAR {}: {}", jar.getFileName(), e.getMessage(), e);
        }
    }

    private void unloadJar(Path jar) {
        List<SwissKitJPlugin> plugins = jarPlugins.remove(jar);
        if (plugins != null) {
            log.info("Unloading plugin JAR: {} (contained {} plugin(s))", jar.getFileName(), plugins.size());
            // Fire onUnload lifecycle callback with TCCL set to the plugin's ClassLoader
            plugins.forEach(p -> {
                try {
                    PluginContext.runWith(p, p::onUnload);
                } catch (Exception e) {
                    log.warn("onUnload() failed for {}: {}", p.getId(), e.getMessage());
                }
                PluginContext.unregister(p);
            });
            if (registry != null) {
                Platform.runLater(() -> plugins.forEach(registry::removePlugin));
            }
        }

        URLClassLoader cl = openLoaders.remove(jar);
        if (cl != null) {
            I18n.unregisterPluginBundle(cl);
            try {
                cl.close();
            } catch (IOException e) {
                log.warn("Error closing ClassLoader for {}: {}", jar.getFileName(), e.getMessage());
            }
        }
    }

    // ── Watch loop ───────────────────────────────────────────────

    private void watchLoop() {
        while (running.get()) {
            try {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path name    = (Path) event.context();
                    Path fullPath = pluginsDir.resolve(name);

                    if (!name.toString().endsWith(".jar")) continue;

                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        log.info("Detected new plugin JAR: {}", name);
                        // Brief delay so the file is fully written before reading
                        Thread.sleep(500);
                        loadJar(fullPath);
                    } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        log.info("Detected plugin JAR removal: {}", name);
                        unloadJar(fullPath);
                    } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        log.info("Detected plugin JAR modification, reloading: {}", name);
                        unloadJar(fullPath);
                        Thread.sleep(500);
                        loadJar(fullPath);
                    }
                }
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
        }
    }
}
