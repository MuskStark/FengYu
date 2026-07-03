package fan.summer.plugin;

import fan.summer.api.PluginContext;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.i18n.I18n;
import fan.summer.api.loader.ChildFirstResourceClassLoader;
import fan.summer.plugin.host.H2PluginSettings;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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

    /** Maps original JAR path → temp copy used by the ClassLoader (avoids locking the original on Windows). */
    private final Map<Path, Path> tempCopies = new ConcurrentHashMap<>();

    /** Single-thread scheduler for all JAR load/unload/reload work — serialising these on one
     *  thread prevents concurrent double-loads of the same JAR (and keeps file I/O off the watch thread). */
    private final java.util.concurrent.ScheduledExecutorService loadScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "plugin-load-scheduler");
                t.setDaemon(true);
                return t;
            });

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

        // Clean up leftover temp copies from previous sessions (crash recovery)
        cleanupStaleTempCopies();

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
        loadScheduler.shutdownNow();
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
     * closes the ClassLoader, removes the plugin from the registry, and deletes
     * the temp copy), then deletes the original JAR file from disk. Because the
     * ClassLoader was opened against a temp copy, the original file is never
     * locked and can be deleted immediately.</p>
     *
     * <p>The directory watcher will see the file deletion, but the second
     * {@code unloadJar} call is a harmless no-op because the JAR was already
     * removed from the internal maps.</p>
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

        // Explicit uninstall wipes the plugin's persisted settings; hot-reload keeps them.
        H2PluginSettings.purge(plugin.getId());

        // Original JAR was never locked (ClassLoader used temp copy), so deletion
        // should succeed immediately without any retry/GC hacks.
        try {
            Files.deleteIfExists(jar);
            log.info("Deleted plugin JAR: {}", jar.getFileName());
        } catch (IOException e) {
            log.warn("Failed to delete plugin JAR {}: {}", jar.getFileName(), e.getMessage());
            jar.toFile().deleteOnExit();
        }
    }

    // ── Temp file cleanup ─────────────────────────────────────

    /** Prefix used for temp JAR copies, so stale files can be identified on startup. */
    private static final String TEMP_PREFIX = "skj-plugin-";

    /**
     * Deletes leftover temp JAR copies from previous sessions (crash recovery).
     * Scans the system temp directory for files matching {@value #TEMP_PREFIX}*.jar.
     */
    private void cleanupStaleTempCopies() {
        try {
            Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
            if (!Files.isDirectory(tmpDir)) return;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmpDir, TEMP_PREFIX + "*.jar")) {
                for (Path leftover : stream) {
                    try {
                        Files.deleteIfExists(leftover);
                        log.debug("Cleaned up leftover temp JAR: {}", leftover.getFileName());
                    } catch (IOException e) {
                        log.debug("Could not delete leftover temp JAR {}: {}", leftover.getFileName(), e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Could not scan temp directory for cleanup: {}", e.getMessage());
        }
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
            // Copy the JAR to a temp file so the original is never locked by the ClassLoader.
            // This is essential on Windows where URLClassLoader holds an exclusive native
            // file handle, preventing deletion even after cl.close().
            Path tempJar = Files.createTempFile("skj-plugin-", ".jar");
            Files.copy(jar, tempJar, StandardCopyOption.REPLACE_EXISTING);

            URLClassLoader cl = new ChildFirstResourceClassLoader(
                new java.net.URL[]{tempJar.toUri().toURL()},
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
                Files.deleteIfExists(tempJar);
                return;
            }

            openLoaders.put(jar, cl);
            tempCopies.put(jar, tempJar);
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

        // Delete temp copy — ClassLoader is closed, so the lock is released
        Path tempJar = tempCopies.remove(jar);
        if (tempJar != null) {
            try {
                Files.deleteIfExists(tempJar);
            } catch (IOException e) {
                log.debug("Could not delete temp JAR {}: {}", tempJar, e.getMessage());
                tempJar.toFile().deleteOnExit();
            }
        }
    }

    // ── Watch loop ───────────────────────────────────────────────

    /** Debounce map: JAR path → scheduled reload timestamp. Prevents rapid-fire reloads. */
    private final ConcurrentHashMap<Path, Long> pendingReloads = new ConcurrentHashMap<>();
    private static final long RELOAD_DEBOUNCE_MS = 1500;

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
                        // Schedule load after a brief delay so the file is fully written
                        loadScheduler.schedule(() -> loadJar(fullPath), 500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        log.info("Detected plugin JAR removal: {}", name);
                        pendingReloads.remove(fullPath);
                        // Route through the single-thread loadScheduler so this unload
                        // never overlaps a concurrent CREATE-load of the same JAR.
                        loadScheduler.execute(() -> unloadJar(fullPath));
                    } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        // Debounce: schedule a reload, coalescing rapid-fire modify events
                        long reloadAt = System.currentTimeMillis() + RELOAD_DEBOUNCE_MS;
                        pendingReloads.put(fullPath, reloadAt);
                        log.debug("Scheduled debounced reload for: {}", name);
                    }
                }
                key.reset();

                // Process pending reloads whose debounce timer has expired
                long now = System.currentTimeMillis();
                for (Map.Entry<Path, Long> entry : pendingReloads.entrySet()) {
                    if (now >= entry.getValue()) {
                        pendingReloads.remove(entry.getKey());
                        Path jar = entry.getKey();
                        if (Files.exists(jar)) {
                            log.info("Executing debounced reload for: {}", jar.getFileName());
                            // Route unload+load through the single-thread loadScheduler so a
                            // reload never overlaps a concurrent CREATE-load of the same JAR.
                            loadScheduler.execute(() -> { unloadJar(jar); loadJar(jar); });
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
        }
    }
}
