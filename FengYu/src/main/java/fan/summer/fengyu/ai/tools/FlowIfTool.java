package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fan.summer.fengyu.ai.FengYuTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Flow-control condition node of the canvas ({@code flow_if}).
 *
 * <p>The flow builder compiles a branch edge (drawn from an IF node's {@code true}/{@code false}
 * output port) into {@code AgentStep#runWhen()} conditions that compare this tool's
 * {@code branch} output — the engine itself only evaluates skip semantics, never the
 * condition. Because {@code AgentRunner} resolves {@code {{steps.N.result…}}} references
 * BEFORE dispatch, both operands arrive as plain values (a bound object/array renders as
 * JSON text, mirroring the string-template rules), so a numeric-aware comparison covers
 * everything a canvas author can bind.
 */
@Component
public class FlowIfTool implements FengYuTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Evaluate one condition and report which branch the flow should take.
     *
     * @param left    left operand (a resolved value as text; objects render as JSON)
     * @param operator comparison id — eq/ne/gt/gte/lt/lte/contains/not_contains/
     *                 starts_with/ends_with/is_empty/is_not_empty
     * @param right   right operand; unused by the is_empty family (optional so those
     *                operators don't show a bogus "required" gap on the canvas)
     * @return {@code {"branch":"true|false","summary":"…"}} for runWhen evaluation
     */
    @Tool(name = "flow_if",
          description = "Evaluate a condition and report the branch to take. "
                  + "Returns {\"branch\":\"true|false\",\"summary\":\"…\"}.")
    public String flowIf(@ToolParam(description = "Left operand; may bind an upstream reference.") String left,
                         String operator,
                         @ToolParam(required = false,
                                    description = "Right operand; unused by is_empty/is_not_empty.") String right) {
        return evaluate(left, operator, right);
    }

    private String evaluate(String left, String operator, String right) {
        String l = left == null ? "" : left;
        String r = right == null ? "" : right;
        boolean result = switch (operator == null ? "eq" : operator) {
            case "is_empty" -> l.isBlank();
            case "is_not_empty" -> !l.isBlank();
            case "contains" -> l.contains(r);
            case "not_contains" -> !l.contains(r);
            case "starts_with" -> l.startsWith(r);
            case "ends_with" -> l.endsWith(r);
            case "ne" -> !compareEq(l, r);
            case "gt", "gte", "lt", "lte" -> compareOrdered(l, r, operator);
            case "eq" -> compareEq(l, r);
            default -> compareEq(l, r);
        };
        try {
            ObjectNode output = MAPPER.createObjectNode();
            output.put("branch", result ? "true" : "false");
            output.put("summary", describe(l, operator, r) + " → " + result);
            return MAPPER.writeValueAsString(output);
        } catch (Exception e) {
            // Jackson object-node serialization cannot fail in practice; keep the tool
            // total anyway — a condition node must never crash a run.
            return "{\"branch\":\"false\",\"summary\":\"" + operator + "\"}";
        }
    }

    /** Equality is numeric when BOTH operands parse as numbers, textual otherwise. */
    private static boolean compareEq(String left, String right) {
        java.math.BigDecimal a = asNumber(left);
        java.math.BigDecimal b = asNumber(right);
        if (a != null && b != null) return a.compareTo(b) == 0;
        return left.equals(right);
    }

    private static boolean compareOrdered(String left, String right, String operator) {
        java.math.BigDecimal a = asNumber(left);
        java.math.BigDecimal b = asNumber(right);
        if (a == null || b == null) {
            // Non-numeric operands fall back to lexicographic order so the node still
            // answers deterministically instead of erroring mid-flow.
            int cmp = left.compareTo(right);
            return switch (operator) {
                case "gt" -> cmp > 0;
                case "gte" -> cmp >= 0;
                case "lt" -> cmp < 0;
                default -> cmp <= 0;
            };
        }
        int cmp = a.compareTo(b);
        return switch (operator) {
            case "gt" -> cmp > 0;
            case "gte" -> cmp >= 0;
            case "lt" -> cmp < 0;
            default -> cmp <= 0;
        };
    }

    private static java.math.BigDecimal asNumber(String value) {
        try {
            return new java.math.BigDecimal(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String describe(String left, String operator, String right) {
        String op = operator == null ? "eq" : operator;
        String quotedLeft = "\"" + truncate(left) + "\"";
        return switch (op) {
            case "is_empty", "is_not_empty" -> op + "(" + quotedLeft + ")";
            default -> op + "(" + quotedLeft + ", \"" + truncate(right) + "\")";
        };
    }

    private static String truncate(String value) {
        return value.length() > 60 ? value.substring(0, 57) + "…" : value;
    }
}
