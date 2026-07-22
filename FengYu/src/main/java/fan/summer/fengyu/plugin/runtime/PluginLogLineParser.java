package fan.summer.fengyu.plugin.runtime;

import java.util.Locale;

/**
 * Best-effort extraction of a log level from a single plugin worker stderr line.
 *
 * <p>Plugin workers log via slf4j-simple, whose default format looks like
 * {@code [main] INFO JsonRpcWorker - hello} (slf4j-simple 1.x) or
 * {@code HH:mm:ss.SSS INFO JsonRpcWorker - hello} (the {@code simplelogger.properties} shipped in
 * the SDK). We don't try to fully parse either layout — we just look for a recognised level token
 * standing alone in the first ~80 characters. Anything we can't classify becomes
 * {@link PluginLogEntry#DEFAULT_LEVEL INFO}; the parser is total (never throws) so a malformed
 * line never breaks the log drain.
 */
final class PluginLogLineParser {

    private static final String[] LEVELS = {"TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR"};

    private PluginLogLineParser() {}

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
        return "WARNING".equals(level) ? "WARN" : level.toUpperCase(Locale.ROOT);
    }
}
