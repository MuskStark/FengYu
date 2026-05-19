package fan.summer.api.log;

/**
 * Fallback {@link LoggerBinder} used when the host has not yet installed a real one.
 * All log calls are silent. Useful for plugin unit tests and standalone tooling.
 */
final class NoOpLoggerBinder implements LoggerBinder {

    static final NoOpLoggerBinder INSTANCE = new NoOpLoggerBinder();

    private NoOpLoggerBinder() {
    }

    @Override
    public PluginLogger getLogger(String name) {
        return new NoOpLogger(name);
    }

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
