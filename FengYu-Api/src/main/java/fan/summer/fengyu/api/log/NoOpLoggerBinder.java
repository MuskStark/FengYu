package fan.summer.fengyu.api.log;

/**
 * Fallback {@link LoggerBinder} used when the host has not yet installed a real one.
 *
 * <p>This binder is the default in {@link LoggerFactory} and is silently substituted
 * whenever {@link LoggerBinder#bind(LoggerBinder)} is called with {@code null}.
 * All log calls are discarded without any side effects.</p>
 *
 * <p>This makes plugin code safe to execute in isolation — for example, in unit tests
 * or when a plugin JAR is loaded outside the host application — without requiring
 * any real logging infrastructure to be present on the classpath.</p>
 *
 * @see LoggerBinder
 * @see LoggerFactory
 * @since 1.0
 */
final class NoOpLoggerBinder implements LoggerBinder {

    /** The singleton instance used directly by {@link LoggerFactory}. */
    static final NoOpLoggerBinder INSTANCE = new NoOpLoggerBinder();

    private NoOpLoggerBinder() {
    }

    @Override
    public PluginLogger getLogger(String name) {
        return new NoOpLogger(name);
    }

    /**
     * A no-op logger that discards all log events.
     * All level checks return {@code false} and all log methods are no-ops.
     */
    private static final class NoOpLogger implements PluginLogger {
        private final String name;

        NoOpLogger(String name) {
            this.name = name;
        }

        @Override public String getName() { return name; }

        @Override public boolean isTraceEnabled() { return false; }
        @Override public void trace(String message) {}
        @Override public void trace(String format, Object arg) {}
        @Override public void trace(String format, Object arg1, Object arg2) {}
        @Override public void trace(String format, Object... args) {}
        @Override public void trace(String message, Throwable t) {}

        @Override public boolean isDebugEnabled() { return false; }
        @Override public void debug(String message) {}
        @Override public void debug(String format, Object arg) {}
        @Override public void debug(String format, Object arg1, Object arg2) {}
        @Override public void debug(String format, Object... args) {}
        @Override public void debug(String message, Throwable t) {}

        @Override public boolean isInfoEnabled() { return false; }
        @Override public void info(String message) {}
        @Override public void info(String format, Object arg) {}
        @Override public void info(String format, Object arg1, Object arg2) {}
        @Override public void info(String format, Object... args) {}
        @Override public void info(String message, Throwable t) {}

        @Override public boolean isWarnEnabled() { return false; }
        @Override public void warn(String message) {}
        @Override public void warn(String format, Object arg) {}
        @Override public void warn(String format, Object arg1, Object arg2) {}
        @Override public void warn(String format, Object... args) {}
        @Override public void warn(String message, Throwable t) {}

        @Override public boolean isErrorEnabled() { return false; }
        @Override public void error(String message) {}
        @Override public void error(String format, Object arg) {}
        @Override public void error(String format, Object arg1, Object arg2) {}
        @Override public void error(String format, Object... args) {}
        @Override public void error(String message, Throwable t) {}
    }
}
