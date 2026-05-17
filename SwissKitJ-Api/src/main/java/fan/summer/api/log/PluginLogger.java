package fan.summer.api.log;

/**
 * Standard logging interface for SwissKitJ plugins.
 *
 * <p>Plugins obtain a logger via {@link LoggerFactory#getLogger(Class)} or
 * {@link LoggerFactory#getLogger(String)} and call the level methods on it.
 * Implementations are provided by the host application (typically backed by
 * SLF4J + Logback), so plugins do not depend on any specific logging framework.</p>
 *
 * <p>Message formatting uses the SLF4J '{}' placeholder convention:</p>
 * <pre>
 *   logger.info("User {} loaded {} records", userName, count);
 *   logger.error("Operation failed", exception);
 *   logger.warn("Retrying {} (attempt {} of {})", taskId, attempt, maxAttempts);
 * </pre>
 *
 * <p>All methods are safe to call from any thread.</p>
 */
public interface PluginLogger {

    /** @return the logger's name (typically a class or category name). */
    String getName();

    // ── Trace ────────────────────────────────────────────────
    boolean isTraceEnabled();
    void trace(String message);
    void trace(String format, Object arg);
    void trace(String format, Object arg1, Object arg2);
    void trace(String format, Object... args);
    void trace(String message, Throwable t);

    // ── Debug ────────────────────────────────────────────────
    boolean isDebugEnabled();
    void debug(String message);
    void debug(String format, Object arg);
    void debug(String format, Object arg1, Object arg2);
    void debug(String format, Object... args);
    void debug(String message, Throwable t);

    // ── Info ─────────────────────────────────────────────────
    boolean isInfoEnabled();
    void info(String message);
    void info(String format, Object arg);
    void info(String format, Object arg1, Object arg2);
    void info(String format, Object... args);
    void info(String message, Throwable t);

    // ── Warn ─────────────────────────────────────────────────
    boolean isWarnEnabled();
    void warn(String message);
    void warn(String format, Object arg);
    void warn(String format, Object arg1, Object arg2);
    void warn(String format, Object... args);
    void warn(String message, Throwable t);

    // ── Error ────────────────────────────────────────────────
    boolean isErrorEnabled();
    void error(String message);
    void error(String format, Object arg);
    void error(String format, Object arg1, Object arg2);
    void error(String format, Object... args);
    void error(String message, Throwable t);
}
