package fan.summer.fengyu.log;

import fan.summer.fengyu.api.log.LoggerBinder;
import fan.summer.fengyu.api.log.PluginLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Host-side {@link LoggerBinder} that delegates every {@link PluginLogger} call to the
 * application's SLF4J + Logback backbone.
 *
 * <p>Plugins call {@code LoggerFactory.getLogger(...)} from the public API ({@code fan.summer.fengyu.api.log})
 * and their log entries flow into the same console and rolling file appenders used by the host
 * itself. This class is installed by the host at startup as the single {@link LoggerBinder}
 * implementation returned by {@code LoggerFactory.getBinder()}.</p>
 *
 * <p>Loggers are cached by name in a {@link ConcurrentHashMap} so repeated lookups for the same
 * name return the identical {@link PluginLogger} instance. Each delegating logger holds a strong
 * reference to the underlying SLF4J {@link Logger} for the corresponding name.</p>
 *
 * @since 1.0
 * @author FengYu
 * @see LoggerBinder
 * @see PluginLogger
 */
public final class Slf4jPluginLoggerBinder implements LoggerBinder {

    /** Loggers are cached by name so repeated lookups return the same instance. */
    private final ConcurrentMap<String, PluginLogger> cache = new ConcurrentHashMap<>();

    /**
     * Returns a {@link PluginLogger} for the given name, caching the result for
     * subsequent lookups.
     *
     * <p>The first call for any given name creates a new {@link Slf4jPluginLogger}
     * wrapping the SLF4J {@link Logger} for that name and stores it in the cache.
     * Subsequent calls for the same name return the cached instance.</p>
     *
     * @param name the logger name; passed to {@link LoggerFactory#getLogger(String)}
     * @return a {@link PluginLogger} delegating to the SLF4J logger for {@code name};
     *         never {@code null}
     * @since 1.0
     */
    @Override
    public PluginLogger getLogger(String name) {
        return cache.computeIfAbsent(name, n -> new Slf4jPluginLogger(LoggerFactory.getLogger(n)));
    }

    /**
     * A {@link PluginLogger} implementation that delegates all logging calls to an
     * underlying SLF4J {@link Logger}.
     *
     * <p>This class is immutable and thread-safe. Each instance is tied to a single
     * SLF4J {@code Logger} and forwards all log levels (trace, debug, info, warn, error)
     * without transformation.</p>
     *
     * @since 1.0
     */
    private static final class Slf4jPluginLogger implements PluginLogger {

        private final Logger delegate;

        Slf4jPluginLogger(Logger delegate) {
            this.delegate = delegate;
        }

        @Override public String getName() { return delegate.getName(); }

        // ── Trace ────────────────────────────────────────────
        @Override public boolean isTraceEnabled() { return delegate.isTraceEnabled(); }
        @Override public void trace(String message) { delegate.trace(message); }
        @Override public void trace(String format, Object arg) { delegate.trace(format, arg); }
        @Override public void trace(String format, Object arg1, Object arg2) { delegate.trace(format, arg1, arg2); }
        @Override public void trace(String format, Object... args) { delegate.trace(format, args); }
        @Override public void trace(String message, Throwable t) { delegate.trace(message, t); }

        // ── Debug ────────────────────────────────────────────
        @Override public boolean isDebugEnabled() { return delegate.isDebugEnabled(); }
        @Override public void debug(String message) { delegate.debug(message); }
        @Override public void debug(String format, Object arg) { delegate.debug(format, arg); }
        @Override public void debug(String format, Object arg1, Object arg2) { delegate.debug(format, arg1, arg2); }
        @Override public void debug(String format, Object... args) { delegate.debug(format, args); }
        @Override public void debug(String message, Throwable t) { delegate.debug(message, t); }

        // ── Info ─────────────────────────────────────────────
        @Override public boolean isInfoEnabled() { return delegate.isInfoEnabled(); }
        @Override public void info(String message) { delegate.info(message); }
        @Override public void info(String format, Object arg) { delegate.info(format, arg); }
        @Override public void info(String format, Object arg1, Object arg2) { delegate.info(format, arg1, arg2); }
        @Override public void info(String format, Object... args) { delegate.info(format, args); }
        @Override public void info(String message, Throwable t) { delegate.info(message, t); }

        // ── Warn ─────────────────────────────────────────────
        @Override public boolean isWarnEnabled() { return delegate.isWarnEnabled(); }
        @Override public void warn(String message) { delegate.warn(message); }
        @Override public void warn(String format, Object arg) { delegate.warn(format, arg); }
        @Override public void warn(String format, Object arg1, Object arg2) { delegate.warn(format, arg1, arg2); }
        @Override public void warn(String format, Object... args) { delegate.warn(format, args); }
        @Override public void warn(String message, Throwable t) { delegate.warn(message, t); }

        // ── Error ────────────────────────────────────────────
        @Override public boolean isErrorEnabled() { return delegate.isErrorEnabled(); }
        @Override public void error(String message) { delegate.error(message); }
        @Override public void error(String format, Object arg) { delegate.error(format, arg); }
        @Override public void error(String format, Object arg1, Object arg2) { delegate.error(format, arg1, arg2); }
        @Override public void error(String format, Object... args) { delegate.error(format, args); }
        @Override public void error(String message, Throwable t) { delegate.error(message, t); }
    }
}
