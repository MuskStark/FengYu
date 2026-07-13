package fan.summer.fengyu.plugin.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Redacts non-empty secret-bearing environment values at process output boundaries. */
final class SensitiveValueRedactor {
    private static final String REDACTED = "<redacted>";
    private final List<String> sensitiveValues;

    private SensitiveValueRedactor(List<String> sensitiveValues) {
        this.sensitiveValues = sensitiveValues;
    }

    static SensitiveValueRedactor fromEnvironment(Map<String, String> environment) {
        List<String> values = environment.entrySet().stream()
            .filter(entry -> isSensitiveKey(entry.getKey()))
            .map(Map.Entry::getValue)
            .filter(value -> value != null && !value.isEmpty())
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
        return new SensitiveValueRedactor(values);
    }

    String redact(String text) {
        if (text == null) {
            return null;
        }
        String redacted = text;
        for (String value : sensitiveValues) {
            redacted = redacted.replace(value, REDACTED);
        }
        return redacted;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toUpperCase(Locale.ROOT);
        return normalized.endsWith("_PASSWORD")
            || normalized.endsWith("_SECRET")
            || normalized.endsWith("_TOKEN");
    }
}
