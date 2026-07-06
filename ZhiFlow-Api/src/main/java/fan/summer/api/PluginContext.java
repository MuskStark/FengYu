package fan.summer.api;

import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
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
 *       {@link #register(ZhiFlowPlugin, ClassLoader)} to associate it with
 *       the plugin's ClassLoader.</li>
 *   <li>When unloading, call {@link #unregister(ZhiFlowPlugin)}.</li>
 *   <li>Wrap every call to a plugin method ({@code createView()},
 *       {@code onActivate()}, etc.) with {@link #runWith(ZhiFlowPlugin, Runnable)}
 *       or {@link #callWith(ZhiFlowPlugin, Callable)}.</li>
 * </ol>
 *
 * <p>If no ClassLoader has been registered for a given plugin (e.g. a
 * built-in tool loaded by the host's own ClassLoader), the plugin's own
 * {@link Class#getClassLoader()} is used as the TCCL — which is effectively
 * a no-op for built-in tools.</p>
 *
 * <p>Plugin keys are held via {@link WeakReference} so that if the host
 * fails to call {@link #unregister} (e.g. due to an exception during unload),
 * the entry is still eligible for garbage collection once no other reference
 * to the plugin exists. A {@link ReferenceQueue} is consulted on every
 * {@link #getClassLoader(ZhiFlowPlugin)} call to evict stale entries.</p>
 *
 * @see ZhiFlowPlugin
 * @since 3.0
 */
public final class PluginContext {

    private static final PluginLogger log = LoggerFactory.getLogger(PluginContext.class);

    private PluginContext() { /* utility class */ }

    /**
     * Weak key wrapper for {@link ZhiFlowPlugin}. Uses identity-based
     * equality so that the map lookup matches the exact plugin instance
     * even if the plugin doesn't override {@code equals/hashCode}.
     */
    private static final class PluginRef extends WeakReference<ZhiFlowPlugin> {
        private final int hash;

        PluginRef(ZhiFlowPlugin plugin, ReferenceQueue<ZhiFlowPlugin> queue) {
            super(plugin, queue);
            this.hash = System.identityHashCode(plugin);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof PluginRef other)) return false;
            // Both must still refer to the same live plugin instance
            ZhiFlowPlugin a = this.get();
            ZhiFlowPlugin b = other.get();
            return a != null && a == b;
        }
    }

    private static final ReferenceQueue<ZhiFlowPlugin> refQueue = new ReferenceQueue<>();

    /**
     * Maps each plugin instance (via weak key) to the ClassLoader that loaded it.
     * Stale entries are drained automatically via the reference queue.
     */
    private static final ConcurrentMap<PluginRef, ClassLoader> CLASS_LOADERS =
            new ConcurrentHashMap<>();

    /** Drain GC'd plugin references from the map. */
    private static void drainQueue() {
        Reference<? extends ZhiFlowPlugin> ref;
        while ((ref = refQueue.poll()) != null) {
            CLASS_LOADERS.remove(ref);
        }
    }

    /** Find the existing PluginRef for a live plugin, or null. */
    private static PluginRef findRef(ZhiFlowPlugin plugin) {
        PluginRef probe = new PluginRef(plugin, null);
        for (PluginRef key : CLASS_LOADERS.keySet()) {
            if (probe.equals(key)) return key;
        }
        return null;
    }

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
    public static void register(ZhiFlowPlugin plugin, ClassLoader loader) {
        drainQueue();
        PluginRef old = findRef(plugin);
        if (old != null) CLASS_LOADERS.remove(old);
        CLASS_LOADERS.put(new PluginRef(plugin, refQueue), loader);
        log.debug("[TCCL] register({}) → CL={}", plugin.getId(), loader);
    }

    /**
     * Removes the ClassLoader association for a plugin.
     *
     * <p>Call this when the plugin is being unloaded or uninstalled.</p>
     *
     * @param plugin the plugin to unregister; must not be {@code null}
     */
    public static void unregister(ZhiFlowPlugin plugin) {
        drainQueue();
        PluginRef ref = findRef(plugin);
        if (ref != null) {
            CLASS_LOADERS.remove(ref);
            log.debug("[TCCL] unregister({})", plugin.getId());
        }
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
    public static ClassLoader getClassLoader(ZhiFlowPlugin plugin) {
        drainQueue();
        PluginRef ref = findRef(plugin);
        ClassLoader cl = (ref != null) ? CLASS_LOADERS.get(ref) : null;
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
    public static void runWith(ZhiFlowPlugin plugin, Runnable action) {
        Thread thread = Thread.currentThread();
        ClassLoader prev = thread.getContextClassLoader();
        ClassLoader target = getClassLoader(plugin);
        thread.setContextClassLoader(target);
        log.debug("[TCCL] runWith({}) on thread={} | {} → {}", plugin.getId(), thread.getName(), clId(prev), clId(target));
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
    public static <T> T callWith(ZhiFlowPlugin plugin, Callable<T> action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader prev = thread.getContextClassLoader();
        ClassLoader target = getClassLoader(plugin);
        thread.setContextClassLoader(target);
        log.debug("[TCCL] callWith({}) on thread={} | {} → {}", plugin.getId(), thread.getName(), clId(prev), clId(target));
        try {
            return action.call();
        } finally {
            thread.setContextClassLoader(prev);
        }
    }

    // ── EventDispatcher TCCL wrapping ────────────────────────────

    /**
     * Wraps the given JavaFX node's {@link javafx.event.EventDispatcher} so that
     * every event dispatched to this node (and its children, via event propagation)
     * runs with the plugin's ClassLoader set as the thread-context ClassLoader.
     *
     * <p>This ensures that background threads spawned from event handlers (e.g.
     * {@code new Thread(task).start()}) inherit the correct TCCL, so libraries
     * like MyBatis, {@link java.util.ServiceLoader}, and resource-bundle lookups
     * work without the plugin author needing any ClassLoader awareness.</p>
     *
     * <p>Call this once after {@link ZhiFlowPlugin#createView()} returns:</p>
     * <pre>{@code
     * Node view = PluginContext.callWith(plugin, plugin::createView);
     * if (view != null) {
     *     PluginContext.wrapEvents(plugin, view);
     * }
     * }</pre>
     *
     * @param plugin the plugin that owns the node
     * @param node   the root node returned by the plugin's {@code createView()}
     */
    public static void wrapEvents(ZhiFlowPlugin plugin, javafx.scene.Node node) {
        ClassLoader pluginCl = getClassLoader(plugin);
        String pluginId = plugin.getId();
        javafx.event.EventDispatcher original = node.getEventDispatcher();
        log.debug("[TCCL] wrapEvents({}) node={} | wrapped with CL={}", pluginId, node.getClass().getSimpleName(), pluginCl);
        node.setEventDispatcher((event, tail) -> {
            Thread t = Thread.currentThread();
            ClassLoader prev = t.getContextClassLoader();
            boolean needsSwitch = prev != pluginCl;
            if (needsSwitch) {
                t.setContextClassLoader(pluginCl);
                log.trace("[TCCL] eventdispatch({}) on thread={} | {} → {} | event={}", pluginId, t.getName(), clId(prev), clId(pluginCl), event.getEventType());
            }
            try {
                return original.dispatchEvent(event, tail);
            } finally {
                if (needsSwitch) {
                    t.setContextClassLoader(prev);
                }
            }
        });
    }

    /** Short ClassLoader identifier for logging: "URLClassLoader@1a2b3c4" or "AppClassLoader". */
    private static String clId(ClassLoader cl) {
        if (cl == null) return "null";
        String cls = cl.getClass().getSimpleName();
        if (cls.isEmpty()) cls = cl.getClass().getName().replaceFirst(".*\\.", "");
        return cls + "@" + Integer.toHexString(System.identityHashCode(cl));
    }
}
