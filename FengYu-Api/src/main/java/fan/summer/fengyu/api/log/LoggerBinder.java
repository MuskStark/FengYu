package fan.summer.fengyu.api.log;

/**
 * SPI for the host application to provide a concrete {@link PluginLogger} implementation.
 *
 * <p>The host application calls {@link #bind(LoggerBinder)} during startup,
 * passing an instance that delegates to its real logging backend
 * (typically SLF4J + Logback). All plugin loggers obtained via
 * {@link LoggerFactory#getLogger(Class)} or {@link LoggerFactory#getLogger(String)}
 * are routed through whichever binder is currently bound.</p>
 *
 * <p>If no binder is explicitly bound, {@link LoggerFactory} falls back to a
 * built-in no-op implementation ({@link NoOpLoggerBinder}) so that plugins
 * compiled against this API can still run in isolation, such as in unit tests,
 * without pulling in any real logging framework.</p>
 *
 * <p>Only one binder is active at a time; calling {@link #bind(LoggerBinder)}
 * again replaces the previous binder. Passing {@code null} reverts to the
 * no-op binder.</p>
 *
 * @see PluginLogger
 * @see LoggerFactory
 * @see NoOpLoggerBinder
 * @since 1.0
 */
public interface LoggerBinder {

    /**
     * Returns a {@link PluginLogger} for the given name.
     *
     * <p>Implementations should return the same logger instance for the same
     * name across multiple calls (idempotent) so that loggers obtained at
     * different points in the code share state.</p>
     *
     * @param name the logger name (typically a fully-qualified class name or a
     *             dotted category string)
     * @return a {@link PluginLogger} instance for the given name
     */
    PluginLogger getLogger(String name);

    /**
     * Installs the host's binder, replacing any previously bound binder.
     *
     * <p>The static {@code bind} helper delegates to
     * {@link LoggerFactory#setBinder(LoggerBinder)} after performing any
     * validation the host requires. Calling this method more than once
     * simply swaps out the active binder.</p>
     *
     * @param binder the binder to install, or {@code null} to revert to the
     *               built-in no-op binder
     */
    static void bind(LoggerBinder binder) {
        LoggerFactory.setBinder(binder);
    }
}
