package fan.summer.api;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Associates external plugins with their dedicated {@link ClassLoader} and
 * provides thread-context-classloader (TCCL) switching for safe plugin
 * method invocation.
 *
 * <p>In a plugin system where each JAR is loaded by its own
 * {@link java.net.URLClassLoader}, libraries used by the plugin (e.g.
 * {@link java.util.ServiceLoader}, resource-bundle lookups, XML parsers)
 * often rely on {@link Thread#getContextClassLoader()} to locate resources.
 * Without TCCL switching, the thread's default ClassLoader is the host
 * application's one — which cannot see classes or resources inside the
 * plugin JAR.</p>
 *
 * <h2>Usage — host side</h2>
 * <ol>
 *   <li>When loading a plugin from a JAR, call
 *       {@link #register(SwissKitJPlugin, ClassLoader)} to associate it with
 *       the plugin's ClassLoader.</li>
 *   <li>When unloading, call {@link #unregister(SwissKitJPlugin)}.</li>
 *   <li>Wrap every call to a plugin method ({@code createView()},
 *       {@code onActivate()}, etc.) with {@link #runWith(SwissKitJPlugin, Runnable)}
 *       or {@link #callWith(SwissKitJPlugin, Callable)}.</li>
 * </ol>
 *
 * <p>If no ClassLoader has been registered for a given plugin (e.g. a
 * built-in tool loaded by the host's own ClassLoader), the plugin's own
 * {@link Class#getClassLoader()} is used as the TCCL — which is effectively
 * a no-op for built-in tools.</p>
 *
 * @see SwissKitJPlugin
 * @since 3.0
 */
public final class PluginContext {

    private PluginContext() { /* utility class */ }

    /**
     * Maps each plugin instance to the ClassLoader that loaded it.
     * For external JAR-based plugins this is the dedicated URLClassLoader;
     * for built-in tools no entry is needed (falls back to plugin.getClass().getClassLoader()).
     */
    private static final ConcurrentMap<SwissKitJPlugin, ClassLoader> CLASS_LOADERS =
            new ConcurrentHashMap<>();

    // ── Registration (called by the host) ──────────────────────

    /**
     * Associates a plugin with the ClassLoader that loaded it.
     *
     * <p>Call this once after the plugin is instantiated by the host's
     * {@link java.util.ServiceLoader} or equivalent mechanism.</p>
     *
     * @param plugin the plugin instance; must not be {@code null}
     * @param loader the ClassLoader that loaded the plugin's JAR; must not be {@code null}
     */
    public static void register(SwissKitJPlugin plugin, ClassLoader loader) {
        CLASS_LOADERS.put(plugin, loader);
    }

    /**
     * Removes the ClassLoader association for a plugin.
     *
     * <p>Call this when the plugin is being unloaded or uninstalled.</p>
     *
     * @param plugin the plugin to unregister; must not be {@code null}
     */
    public static void unregister(SwissKitJPlugin plugin) {
        CLASS_LOADERS.remove(plugin);
    }

    // ── ClassLoader lookup ─────────────────────────────────────

    /**
     * Returns the ClassLoader associated with the given plugin.
     *
     * <p>If no explicit association exists (e.g. built-in tools), the
     * plugin's own {@link Class#getClassLoader()} is returned.</p>
     *
     * @param plugin the plugin; must not be {@code null}
     * @return the ClassLoader for the plugin, never {@code null}
     */
    public static ClassLoader getClassLoader(SwissKitJPlugin plugin) {
        ClassLoader cl = CLASS_LOADERS.get(plugin);
        return cl != null ? cl : plugin.getClass().getClassLoader();
    }

    // ── TCCL-safe invocation ───────────────────────────────────

    /**
     * Executes a action with the plugin's ClassLoader set as the
     * current thread's context ClassLoader.
     *
     * <p>The original TCCL is always restored in a {@code finally} block,
     * even if the action throws.</p>
     *
     * @param plugin the plugin whose ClassLoader should be on the TCCL
     * @param action the action to execute; must not be {@code null}
     */
    public static void runWith(SwissKitJPlugin plugin, Runnable action) {
        Thread thread = Thread.currentThread();
        ClassLoader prev = thread.getContextClassLoader();
        thread.setContextClassLoader(getClassLoader(plugin));
        try {
            action.run();
        } finally {
            thread.setContextClassLoader(prev);
        }
    }

    /**
     * Executes a callable with the plugin's ClassLoader set as the
     * current thread's context ClassLoader, returning the result.
     *
     * <p>The original TCCL is always restored in a {@code finally} block,
     * even if the callable throws.</p>
     *
     * @param plugin the plugin whose ClassLoader should be on the TCCL
     * @param action the callable to execute; must not be {@code null}
     * @param <T>    the return type
     * @return the result of the callable
     * @throws Exception if the callable throws a checked exception
     */
    public static <T> T callWith(SwissKitJPlugin plugin, Callable<T> action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader prev = thread.getContextClassLoader();
        thread.setContextClassLoader(getClassLoader(plugin));
        try {
            return action.call();
        } finally {
            thread.setContextClassLoader(prev);
        }
    }
}
