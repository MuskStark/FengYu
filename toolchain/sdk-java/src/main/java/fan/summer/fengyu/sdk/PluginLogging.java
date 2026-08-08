package fan.summer.fengyu.sdk;

import org.slf4j.event.Level;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared logging controls for a FengYu plugin worker.
 *
 * <p>The host supplies the initial level through {@link PluginEnvironment#LOG_LEVEL} and may
 * update a running worker through the SDK's built-in log-level JSON-RPC notification. All SLF4J
 * loggers created by the Worker SDK provider consult this value at call time, so a change applies
 * immediately even to logger instances held in {@code static final} fields.
 */
public final class PluginLogging {
    public static final String SET_LEVEL_METHOD = "$/fengyu/logging/setLevel";
    public static final String FRAME_PREFIX = "@fengyu-log:";

    private static final AtomicReference<Threshold> threshold =
        new AtomicReference<>(Threshold.parse(environmentLevel()));

    private PluginLogging() {}

    /** Current effective level, one of TRACE, DEBUG, INFO, WARN, ERROR, or OFF. */
    public static String level() {
        return threshold.get().name();
    }

    /** Set the effective level. Invalid values are rejected instead of silently widening output. */
    public static void setLevel(String value) {
        threshold.set(Threshold.parse(value));
    }

    /** Used by the bundled SLF4J provider; public so the provider subpackage can stay isolated. */
    public static boolean isEnabled(Level level) {
        Threshold current = threshold.get();
        return current != Threshold.OFF && severity(level) >= current.severity;
    }

    private static String environmentLevel() {
        String property = System.getProperty(PluginEnvironment.LOG_LEVEL);
        if (property != null && !property.isBlank()) return property;
        return System.getenv().getOrDefault(PluginEnvironment.LOG_LEVEL, "INFO");
    }

    private static int severity(Level level) {
        return switch (level) {
            case TRACE -> 0;
            case DEBUG -> 1;
            case INFO -> 2;
            case WARN -> 3;
            case ERROR -> 4;
            // Defensive: Level currently has exactly these five constants and isEnabled()
            // short-circuits OFF, so this branch is unreachable today. Throw (mirroring
            // Threshold.parse's message) rather than silently mis-grade a future enum constant.
            default -> throw new IllegalArgumentException("Unsupported plugin log level: " + level);
        };
    }

    private enum Threshold {
        TRACE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4), OFF(Integer.MAX_VALUE);

        private final int severity;

        Threshold(int severity) {
            this.severity = severity;
        }

        static Threshold parse(String value) {
            if (value == null || value.isBlank()) return INFO;
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if ("WARNING".equals(normalized)) normalized = "WARN";
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unsupported plugin log level: " + value);
            }
        }
    }
}
