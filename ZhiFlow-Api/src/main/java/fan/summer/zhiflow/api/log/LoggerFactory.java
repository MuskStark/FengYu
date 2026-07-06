package fan.summer.zhiflow.api.log;

/**
 * Entry point for plugins to obtain a {@link PluginLogger}.
 *
 * <p>Plugins use this factory rather than instantiating logger implementations
 * directly, so that log traffic is routed to whatever logging backend the host
 * application has installed.</p>
 *
 * <p>Typical usage from a plugin class:</p>
 * <pre>{@code
 * public class MyPlugin implements ZhiFlowPlugin {
 *     private static final PluginLogger log = LoggerFactory.getLogger(MyPlugin.class);
 *
 *     @Override
 *     public void onActivate() {
 *         log.info("Plugin activated, id={}", getId());
 *     }
 * }
 * }</pre>
 *
 * <p>The host application installs a real {@link LoggerBinder} during startup by
 * calling {@code LoggerBinder.bind(new MyLoggerBinder(...))}. Until a binder is
 * installed, all loggers returned by this factory are silent no-ops, which keeps
 * plugin code safe to run in unit-test contexts without any real logging
 * dependency.</p>
 *
 * @see PluginLogger
 * @see LoggerBinder
 * @see NoOpLoggerBinder
 * @since 1.0
 */
public final class LoggerFactory {

    private static volatile LoggerBinder binder = NoOpLoggerBinder.INSTANCE;

    private LoggerFactory() {
    }

    /**
     * Returns a logger named after the given class.
     *
     * <p>The class's fully-qualified name is used as the logger name, matching
     * the SLF4J convention.</p>
     *
     * @param clazz the class to name the logger after
     * @return a {@link PluginLogger} for the given class
     */
    public static PluginLogger getLogger(Class<?> clazz) {
        return binder.getLogger(clazz.getName());
    }

    /**
     * Returns a logger with the given explicit name.
     *
     * <p>Use this overload when you want a category-style name (e.g.
     * {@code "fan.summer.myplugin.network"}) rather than a class-based name.</p>
     *
     * @param name the logger name (dotted category string)
     * @return a {@link PluginLogger} for the given name
     */
    public static PluginLogger getLogger(String name) {
        return binder.getLogger(name);
    }

    /**
     * Package-private method used by {@link LoggerBinder#bind(LoggerBinder)} to
     * install or remove the active binder.
     *
     * @param newBinder the binder to install, or {@code null} to revert to
     *                  the built-in no-op binder
     */
    static void setBinder(LoggerBinder newBinder) {
        binder = (newBinder == null) ? NoOpLoggerBinder.INSTANCE : newBinder;
    }
}
