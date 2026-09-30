package fan.summer.fengyu.ai.codemode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The optional first-line pragma of an {@code exec} source: exactly
 * {@code // @exec: {"yield_time_ms": ..., "max_output_tokens": ...}} — the only two
 * supported fields, non-negative safe integers, and it must be followed by JavaScript
 * source on subsequent lines (codex {@code description.rs} parse_exec_source contract,
 * error messages mirrored).
 */
public final class ExecPragma {

    private ExecPragma() {}

    /** The parsed limits; null fields keep the defaults. */
    public record Pragma(Long yieldTimeMs, Long maxOutputTokens) {}

    /** The pragma (null when absent) plus the remaining source. */
    public record ExecSource(Pragma pragma, String source) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SUPPORTED = Set.of("yield_time_ms", "max_output_tokens");

    /**
     * Splits the pragma off an exec source; throws {@link IllegalArgumentException}
     * with the codex-shaped message on any malformed pragma.
     */
    public static ExecSource parse(String input) {
        if (input == null || input.isBlank() || !input.startsWith("// @exec:")) {
            return new ExecSource(null, input == null ? "" : input);
        }
        int lineEnd = input.indexOf('\n');
        if (lineEnd < 0) {
            throw new IllegalArgumentException(
                    "exec pragma must be followed by JavaScript source on subsequent lines");
        }
        String pragmaLine = input.substring("// @exec:".length(), lineEnd).trim();
        String source = input.substring(lineEnd + 1);
        if (source.isBlank()) {
            throw new IllegalArgumentException(
                    "exec pragma must be followed by JavaScript source on subsequent lines");
        }
        JsonNode json;
        try {
            json = JSON.readTree(pragmaLine);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "exec pragma must be valid JSON with supported fields `yield_time_ms` "
                            + "and `max_output_tokens`: " + e.getMessage());
        }
        if (!json.isObject()) {
            throw new IllegalArgumentException(
                    "exec pragma must be a JSON object with supported fields `yield_time_ms` "
                            + "and `max_output_tokens`");
        }
        Set<String> unknown = new LinkedHashSet<>();
        for (Iterator<String> fields = json.fieldNames(); fields.hasNext(); ) {
            String field = fields.next();
            if (!SUPPORTED.contains(field)) unknown.add(field);
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "exec pragma must be a JSON object with supported fields `yield_time_ms` "
                            + "and `max_output_tokens`");
        }
        Long yield = safeInteger(json.get("yield_time_ms"), "yield_time_ms");
        Long tokens = safeInteger(json.get("max_output_tokens"), "max_output_tokens");
        return new ExecSource(new Pragma(yield, tokens), source);
    }

    private static Long safeInteger(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || node.longValue() < 0 || node.longValue() > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException(
                    "exec pragma field `" + field + "` must be a non-negative safe integer");
        }
        return node.longValue();
    }
}
