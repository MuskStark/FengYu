package fan.summer.api.log;

/**
 * Entry point for plugins to obtain a {@link PluginLogger}.
 *
 * <pre>
 *   public class MyPlugin implements SwissKitJPlugin {
 *       private static final PluginLogger log =
 *           LoggerFactory.getLogger(MyPlugin.class);
 *       ...
 *   }
 * </pre>
 *
 * <p>The host application installs a real {@link LoggerBinder} during startup
 * (typically backed by SLF4J + Logback). Until the host binds one, all loggers
 * are silent no-ops — safe for unit tests and isolated plugin loading.</p>
 */
public final class LoggerFactory {

    private static volatile LoggerBinder binder = NoOpLoggerBinder.INSTANCE;

    private LoggerFactory() {
    }

    /** Returns a logger named after the given class. */
    public static PluginLogger getLogger(Class<?> clazz) {
        return binder.getLogger(clazz.getName());
    }

    /** Returns a logger with the given name (typically a dotted category). */
    public static PluginLogger getLogger(String name) {
        return binder.getLogger(name);
    }

    /** Package-private — used by {@link LoggerBinder#bind(LoggerBinder)}. */
    static void setBinder(LoggerBinder newBinder) {
        binder = (newBinder == null) ? NoOpLoggerBinder.INSTANCE : newBinder;
    }
}
