package fan.summer.plugin;

import fan.summer.api.SwissKitJPlugin;
import fan.summer.plugin.PluginRegistry;
import javafx.application.Platform;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Scans a plugins/ directory for JAR files, loads SwissKitJPlugin implementations
 * via ServiceLoader, and watches for hot-reload on file changes.
 */
public class PluginLoader {

    private static final Logger log = Logger.getLogger(PluginLoader.class.getName());

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
        if (!running.compareAndSet(false, true)) return;

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
            }
        } catch (IOException e) {
            log.warning("Cannot start plugin watcher: " + e.getMessage());
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (watchService != null) watchService.close();
        } catch (IOException ignored) {}
        if (watchThread != null) watchThread.interrupt();

        // Unload all plugins
        new ArrayList<>(jarPlugins.keySet()).forEach(this::unloadJar);
    }

    // ── Scan ────────────────────────────────────────────────────

    private void scanAll() {
        if (!Files.isDirectory(pluginsDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (Path jar : stream) {
                if (!jarPlugins.containsKey(jar)) loadJar(jar);
            }
        } catch (IOException e) {
            log.warning("Plugin scan failed: " + e.getMessage());
        }
    }

    // ── JAR load / unload ────────────────────────────────────────

    private void loadJar(Path jar) {
        try {
            URLClassLoader cl = new URLClassLoader(
                new java.net.URL[]{jar.toUri().toURL()},
                getClass().getClassLoader()
            );
            ServiceLoader<SwissKitJPlugin> sl = ServiceLoader.load(SwissKitJPlugin.class, cl);

            List<SwissKitJPlugin> loaded = new ArrayList<>();
            for (SwissKitJPlugin plugin : sl) {
                loaded.add(plugin);
                log.info("Loaded plugin: " + plugin.getId() + " from " + jar.getFileName());
            }

            if (loaded.isEmpty()) {
                cl.close();
                return;
            }

            openLoaders.put(jar, cl);
            jarPlugins.put(jar, loaded);

            if (registry != null) {
                Platform.runLater(() -> registry.addPlugins(loaded));
            }
        } catch (Exception e) {
            log.warning("Failed to load JAR " + jar.getFileName() + ": " + e.getMessage());
        }
    }

    private void unloadJar(Path jar) {
        List<SwissKitJPlugin> plugins = jarPlugins.remove(jar);
        if (plugins != null && registry != null) {
            Platform.runLater(() -> plugins.forEach(registry::removePlugin));
        }

        URLClassLoader cl = openLoaders.remove(jar);
        if (cl != null) {
            try { cl.close(); } catch (IOException ignored) {}
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
                        // Brief delay so the file is fully written before reading
                        Thread.sleep(500);
                        loadJar(fullPath);
                    } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        unloadJar(fullPath);
                    } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
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
