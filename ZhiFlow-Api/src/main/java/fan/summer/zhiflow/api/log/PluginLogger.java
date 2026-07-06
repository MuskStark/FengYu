package fan.summer.zhiflow.api.log;

/**
 * Standard logging interface for SwissKitJ plugins.
 *
 * <p>Plugins obtain a logger via {@link LoggerFactory#getLogger(Class)} or
 * {@link LoggerFactory#getLogger(String)} and call the appropriate level methods.
 * Implementations are provided by the host application (typically backed by
 * SLF4J + Logback), so plugins do not need to depend on any specific logging
 * framework.</p>
 *
 * <p>Message formatting uses the SLF4J '{}' placeholder convention, which avoids
 * expensive string concatenation when the log level is disabled:</p>
 * <pre>{@code
 *   logger.info("User {} loaded {} records", userName, count);
 *   logger.error("Operation failed", exception);
 *   logger.warn("Retrying {} (attempt {} of {})", taskId, attempt, maxAttempts);
 * }</pre>
 *
 * <p>All methods are safe to call from any thread. Implementations need not
 * synchronize internally.</p>
 *
 * @see LoggerFactory
 * @see LoggerBinder
 * @since 1.0
 */
public interface PluginLogger {

    /**
     * Returns the logger's name, typically a class or category name.
     *
     * @return the logger name
     */
    String getName();

    // ── Trace ────────────────────────────────────────────────

    /**
     * Checks whether the TRACE level is enabled for this logger.
     *
     * @return {@code true} if TRACE is enabled
     */
    boolean isTraceEnabled();

    /**
     * Logs a message at the TRACE level.
     *
     * @param message the message to log
     */
    void trace(String message);

    /**
     * Logs a formatted message at the TRACE level using one placeholder.
     *
     * @param format the format string with exactly one '{}'
     * @param arg    the argument to substitute
     */
    void trace(String format, Object arg);

    /**
     * Logs a formatted message at the TRACE level using two placeholders.
     *
     * @param format the format string with exactly two '{}' placeholders
     * @param arg1   the first argument
     * @param arg2   the second argument
     */
    void trace(String format, Object arg1, Object arg2);

    /**
     * Logs a formatted message at the TRACE level using variable placeholders.
     *
     * @param format the format string with '{}' placeholders
     * @param args   the arguments to substitute in order
     */
    void trace(String format, Object... args);

    /**
     * Logs a message and exception at the TRACE level.
     *
     * @param message the message to log
     * @param t       the throwable to include in the log
     */
    void trace(String message, Throwable t);

    // ── Debug ────────────────────────────────────────────────

    /**
     * Checks whether the DEBUG level is enabled for this logger.
     *
     * @return {@code true} if DEBUG is enabled
     */
    boolean isDebugEnabled();

    /**
     * Logs a message at the DEBUG level.
     *
     * @param message the message to log
     */
    void debug(String message);

    /**
     * Logs a formatted message at the DEBUG level using one placeholder.
     *
     * @param format the format string with exactly one '{}'
     * @param arg    the argument to substitute
     */
    void debug(String format, Object arg);

    /**
     * Logs a formatted message at the DEBUG level using two placeholders.
     *
     * @param format the format string with exactly two '{}' placeholders
     * @param arg1   the first argument
     * @param arg2   the second argument
     */
    void debug(String format, Object arg1, Object arg2);

    /**
     * Logs a formatted message at the DEBUG level using variable placeholders.
     *
     * @param format the format string with '{}' placeholders
     * @param args   the arguments to substitute in order
     */
    void debug(String format, Object... args);

    /**
     * Logs a message and exception at the DEBUG level.
     *
     * @param message the message to log
     * @param t       the throwable to include in the log
     */
    void debug(String message, Throwable t);

    // ── Info ─────────────────────────────────────────────────

    /**
     * Checks whether the INFO level is enabled for this logger.
     *
     * @return {@code true} if INFO is enabled
     */
    boolean isInfoEnabled();

    /**
     * Logs a message at the INFO level.
     *
     * @param message the message to log
     */
    void info(String message);

    /**
     * Logs a formatted message at the INFO level using one placeholder.
     *
     * @param format the format string with exactly one '{}'
     * @param arg    the argument to substitute
     */
    void info(String format, Object arg);

    /**
     * Logs a formatted message at the INFO level using two placeholders.
     *
     * @param format the format string with exactly two '{}' placeholders
     * @param arg1   the first argument
     * @param arg2   the second argument
     */
    void info(String format, Object arg1, Object arg2);

    /**
     * Logs a formatted message at the INFO level using variable placeholders.
     *
     * @param format the format string with '{}' placeholders
     * @param args   the arguments to substitute in order
     */
    void info(String format, Object... args);

    /**
     * Logs a message and exception at the INFO level.
     *
     * @param message the message to log
     * @param t       the throwable to include in the log
     */
    void info(String message, Throwable t);

    // ── Warn ─────────────────────────────────────────────────

    /**
     * Checks whether the WARN level is enabled for this logger.
     *
     * @return {@code true} if WARN is enabled
     */
    boolean isWarnEnabled();

    /**
     * Logs a message at the WARN level.
     *
     * @param message the message to log
     */
    void warn(String message);

    /**
     * Logs a formatted message at the WARN level using one placeholder.
     *
     * @param format the format string with exactly one '{}'
     * @param arg    the argument to substitute
     */
    void warn(String format, Object arg);

    /**
     * Logs a formatted message at the WARN level using two placeholders.
     *
     * @param format the format string with exactly two '{}' placeholders
     * @param arg1   the first argument
     * @param arg2   the second argument
     */
    void warn(String format, Object arg1, Object arg2);

    /**
     * Logs a formatted message at the WARN level using variable placeholders.
     *
     * @param format the format string with '{}' placeholders
     * @param args   the arguments to substitute in order
     */
    void warn(String format, Object... args);

    /**
     * Logs a message and exception at the WARN level.
     *
     * @param message the message to log
     * @param t       the throwable to include in the log
     */
    void warn(String message, Throwable t);

    // ── Error ────────────────────────────────────────────────

    /**
     * Checks whether the ERROR level is enabled for this logger.
     *
     * @return {@code true} if ERROR is enabled
     */
    boolean isErrorEnabled();

    /**
     * Logs a message at the ERROR level.
     *
     * @param message the message to log
     */
    void error(String message);

    /**
     * Logs a formatted message at the ERROR level using one placeholder.
     *
     * @param format the format string with exactly one '{}'
     * @param arg    the argument to substitute
     */
    void error(String format, Object arg);

    /**
     * Logs a formatted message at the ERROR level using two placeholders.
     *
     * @param format the format string with exactly two '{}' placeholders
     * @param arg1   the first argument
     * @param arg2   the second argument
     */
    void error(String format, Object arg1, Object arg2);

    /**
     * Logs a formatted message at the ERROR level using variable placeholders.
     *
     * @param format the format string with '{}' placeholders
     * @param args   the arguments to substitute in order
     */
    void error(String format, Object... args);

    /**
     * Logs a message and exception at the ERROR level.
     *
     * @param message the message to log
     * @param t       the throwable to include in the log
     */
    void error(String message, Throwable t);
}
