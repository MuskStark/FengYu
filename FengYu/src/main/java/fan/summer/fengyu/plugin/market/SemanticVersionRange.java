package fan.summer.fengyu.plugin.market;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, deterministic SemVer range evaluator for plugin host compatibility.
 * Supports whitespace-separated comparator sets and {@code ||} alternatives.
 */
public final class SemanticVersionRange {
    private SemanticVersionRange() {}

    public static boolean includes(String range, String version) {
        if (range == null || range.isBlank()) {
            throw new IllegalArgumentException("FengYu engine range is required");
        }
        SemanticVersion candidate = SemanticVersion.parse(version);
        boolean matched = false;
        for (String alternative : range.split("\\|\\|", -1)) {
            List<Comparator> comparators = parseSet(alternative.trim());
            if (!comparators.isEmpty() && comparators.stream().allMatch(c -> c.matches(candidate))) {
                matched = true;
            }
        }
        return matched;
    }

    public static boolean isValid(String range) {
        try {
            // Parsing is independent of this sentinel candidate; the result itself is irrelevant.
            includes(range, "0.0.0");
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static List<Comparator> parseSet(String set) {
        if (set.isBlank()) throw new IllegalArgumentException("Empty SemVer range alternative");
        List<Comparator> result = new ArrayList<>();
        for (String token : set.split("\\s+")) {
            String operator = "=";
            String value = token;
            for (String prefix : List.of(">=", "<=", ">", "<", "=")) {
                if (token.startsWith(prefix)) {
                    operator = prefix;
                    value = token.substring(prefix.length());
                    break;
                }
            }
            if (value.isBlank()) throw new IllegalArgumentException("Missing version in range: " + token);
            result.add(new Comparator(operator, SemanticVersion.parse(value)));
        }
        return result;
    }

    private record Comparator(String operator, SemanticVersion version) {
        boolean matches(SemanticVersion candidate) {
            int comparison = candidate.compareTo(version);
            return switch (operator) {
                case ">=" -> comparison >= 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case "<" -> comparison < 0;
                case "=" -> comparison == 0;
                default -> throw new IllegalArgumentException("Unsupported SemVer operator: " + operator);
            };
        }
    }
}
