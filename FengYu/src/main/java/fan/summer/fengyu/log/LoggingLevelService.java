package fan.summer.fengyu.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Persists and applies the one log threshold shared by the host and every plugin Worker. */
@Service
public class LoggingLevelService {
    public static final String DEFAULT_LEVEL = "INFO";
    public static final Set<String> SUPPORTED_LEVELS =
        Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

    private static final Logger log = LoggerFactory.getLogger(LoggingLevelService.class);

    private final Settings settings;
    private final AtomicReference<String> current = new AtomicReference<>(DEFAULT_LEVEL);

    @Autowired
    public LoggingLevelService(AiConfigServiceHeadless ignored) {
        this(new Settings() {
            @Override public String read() { return AiConfigServiceHeadless.getLogLevel(); }
            @Override public void write(String level) { AiConfigServiceHeadless.setLogLevel(level); }
        });
    }

    LoggingLevelService(Settings settings) {
        this.settings = settings;
    }

    @PostConstruct
    void initialize() {
        apply(normalize(settings.read()));
    }

    public String currentLevel() {
        return current.get();
    }

    /** Persist and immediately apply a new host-wide threshold. */
    public synchronized String setLevel(String requested) {
        String normalized = normalize(requested);
        settings.write(normalized);
        apply(normalized);
        log.info("Host and plugin log level changed to {}", normalized);
        return normalized;
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return DEFAULT_LEVEL;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("WARNING".equals(normalized)) normalized = "WARN";
        if (!SUPPORTED_LEVELS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported log level: " + value);
        }
        return normalized;
    }

    private void apply(String level) {
        current.set(level);
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) return;
        Level logbackLevel = Level.valueOf(level);
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(logbackLevel);
        // logback.xml gives these namespaces explicit defaults. Override both so application
        // classes and host-side plugin forwarding use the same user-selected threshold.
        context.getLogger("fan.summer").setLevel(logbackLevel);
        context.getLogger("plugin").setLevel(logbackLevel);
    }

    interface Settings {
        String read();
        void write(String level);
    }
}
