package fan.summer.api.log;

/**
 * SPI for the host application to provide a concrete {@link PluginLogger} implementation.
 *
 * <p>The host calls {@link #bind(LoggerBinder)} during startup with an instance that
 * delegates to its real logging framework (e.g. SLF4J). All plugin loggers created
 * through {@link LoggerFactory} are routed through this binder.</p>
 *
 * <p>If no binder is bound, {@link LoggerFactory} falls back to a no-op logger so that
 * plugins compiled against the API can still run (e.g. in isolated tests).</p>
 */
public interface LoggerBinder {

    /**
     * Returns a {@link PluginLogger} for the given name.
     * Implementations should return the same instance for the same name (idempotent).
     */
    PluginLogger getLogger(String name);

    /**
     * Installs the host's binder. Calling more than once replaces the previous binder.
     * Pass {@code null} to revert to the built-in no-op binder.
     */
    static void bind(LoggerBinder binder) {
        LoggerFactory.setBinder(binder);
    }
}
