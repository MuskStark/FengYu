package fan.summer.log;

import fan.summer.api.log.LoggerBinder;
import fan.summer.api.log.PluginLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Host-side {@link LoggerBinder} that delegates every {@link PluginLogger} call to the
 * application's SLF4J + Logback backbone. Plugins call {@code LoggerFactory.getLogger(...)}
 * from the public API and their log entries flow into the same console and rolling file
 * appenders used by the host itself.
 */
public final class Slf4jPluginLoggerBinder implements LoggerBinder {

    /** Loggers are cached by name so repeated lookups return the same instance. */
    private final ConcurrentMap<String, PluginLogger> cache = new ConcurrentHashMap<>();

    @Override
    public PluginLogger getLogger(String name) {
        return cache.computeIfAbsent(name, n -> new Slf4jPluginLogger(LoggerFactory.getLogger(n)));
    }

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
