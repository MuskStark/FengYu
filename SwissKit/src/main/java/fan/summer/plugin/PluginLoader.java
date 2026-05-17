package fan.summer.plugin;

import fan.summer.api.SwissKitJPlugin;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scans a plugins/ directory for JAR files, loads SwissKitJPlugin implementations
 * via ServiceLoader, and watches for hot-reload on file changes.
 */
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private final Path        pluginsDir;
    private PluginRegistry registry;
    private WatchService      watchService;
    private Thread            watchThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Maps JAR path → the ClassLoader opened for it (for unloading) */
    private final Map<Path, URLClassLoader> openLoaders = new LinkedHashMap<>();

    /** Maps JAR path → plugins loaded from that JAR */
    private final Map<Path, List<SwissKitJPlugin>> jarPlugins = new LinkedHashMap<>();

    public PluginLoader(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    // Called by PluginRegistry after construction
    void setRegistry(PluginRegistry registry) {
        this.registry = registry;
    }

    // ── Lifecycle ────────────────────────────────────────────────

    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.debug("PluginLoader already running, ignoring duplicate start()");
            return;
        }
        log.info("Starting plugin loader, directory={}", pluginsDir.toAbsolutePath());

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
            URLClassLoader cl = new URLClassLoader(
                new java.net.URL[]{jar.toUri().toURL()},
                getClass().getClassLoader()
            );
            ServiceLoader<SwissKitJPlugin> sl = ServiceLoader.load(SwissKitJPlugin.class, cl);

            List<SwissKitJPlugin> loaded = new ArrayList<>();
            for (SwissKitJPlugin plugin : sl) {
                loaded.add(plugin);
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
            if (registry != null) {
                Platform.runLater(() -> plugins.forEach(registry::removePlugin));
            }
        }

        URLClassLoader cl = openLoaders.remove(jar);
        if (cl != null) {
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
