package fan.summer.fengyu.plugin.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Locale;

/**
 * Best-effort extraction of a log level from a single plugin worker stderr line.
 *
 * <p>Current Worker SDKs emit a structured frame. Older plugin workers may log via
 * slf4j-simple, whose default format looks like
 * {@code [main] INFO JsonRpcWorker - hello} (slf4j-simple 1.x) or
 * {@code HH:mm:ss.SSS INFO JsonRpcWorker - hello} (the {@code simplelogger.properties} shipped in
 * the SDK). We don't try to fully parse either layout — we just look for a recognised level token
 * standing alone in the first ~80 characters. Anything we can't classify becomes
 * {@link PluginLogEntry#DEFAULT_LEVEL INFO}; the parser is total (never throws) so a malformed
 * line never breaks the log drain.
 */
final class PluginLogLineParser {

    private static final String[] LEVELS = {"TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR"};
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private PluginLogLineParser() {}

    static Parsed parse(String line) {
        if (line != null && line.startsWith(PluginWorkerProtocol.LOG_FRAME_PREFIX)) {
            try {
                JsonNode event = JSON.readTree(line.substring(PluginWorkerProtocol.LOG_FRAME_PREFIX.length()));
                String message = event.path("message").asText("");
                String throwable = event.path("throwable").asText("");
                if (!throwable.isBlank()) message = message + System.lineSeparator() + throwable;
                return new Parsed(
                    normalise(event.path("level").asText(PluginLogEntry.DEFAULT_LEVEL)),
                    event.path("logger").asText("worker"),
                    event.path("thread").asText(""),
                    message);
            } catch (Exception ignored) {
                // Fall through: malformed structured output remains visible as legacy stderr.
            }
        }
        return new Parsed(levelOf(line), null, null, line == null ? "" : line);
    }

    static String levelOf(String line) {
        if (line == null) return PluginLogEntry.DEFAULT_LEVEL;
        // Only scan the prefix — the message body may legitimately contain "ERROR" etc.
        int end = Math.min(line.length(), 80);
        for (int i = 0; i < end; i++) {
            char c = line.charAt(i);
            if (c < 'A' || c > 'Z') continue;
            for (String level : LEVELS) {
                if (regionMatchesUppercase(line, i, level)) {
                    return normalise(level);
                }
            }
        }
        return PluginLogEntry.DEFAULT_LEVEL;
    }

    private static boolean regionMatchesUppercase(String line, int start, String token) {
        if (start + token.length() > line.length()) return false;
        for (int j = 0; j < token.length(); j++) {
            if (line.charAt(start + j) != token.charAt(j)) return false;
        }
        // The token must not be part of a longer word (e.g. "INFO" inside "INFORMATION").
        int after = start + token.length();
        if (after < line.length()) {
            char next = line.charAt(after);
            if (Character.isLetter(next)) return false;
        }
        return true;
    }

    private static String normalise(String level) {
        String normalized = level == null ? PluginLogEntry.DEFAULT_LEVEL : level.toUpperCase(Locale.ROOT);
        if ("WARNING".equals(normalized)) return "WARN";
        for (String supported : LEVELS) {
            if (supported.equals(normalized)) return normalized;
        }
        return PluginLogEntry.DEFAULT_LEVEL;
    }

    record Parsed(String level, String logger, String thread, String message) {}
}
