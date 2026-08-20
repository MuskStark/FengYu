package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Operator semantics of the flow-control condition node: every operator family
 * (string containment, numeric order with fallback, emptiness, numeric-aware
 * equality) and the {@code {"branch":…}} output shape the engine's runWhen
 * evaluation reads.
 */
class FlowIfToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final FlowIfTool tool = new FlowIfTool();

    private String branch(String left, String operator, String right) throws Exception {
        JsonNode parsed = MAPPER.readTree(tool.flowIf(left, operator, right));
        return parsed.path("branch").asText();
    }

    @Test
    void stringOperators() throws Exception {
        assertEquals("true", branch("共拆分 3 个文件", "contains", "拆分"));
        assertEquals("false", branch("done", "contains", "fail"));
        assertEquals("true", branch("report.xlsx", "starts_with", "report"));
        assertEquals("true", branch("report.xlsx", "ends_with", ".xlsx"));
        assertEquals("true", branch("ok", "not_contains", "error"));
    }

    @Test
    void numericComparisonsAreNumericAwareAcrossFormats() throws Exception {
        assertEquals("true", branch("10", "gt", "9"));
        assertEquals("false", branch("9", "gte", "10"));
        assertEquals("true", branch(" 3.5 ", "lt", "4"));
        assertEquals("true", branch("10.0", "eq", "10"));
        assertEquals("true", branch("10", "ne", "9"));
    }

    @Test
    void orderedOperatorsFallBackToLexicographicForNonNumericOperands() throws Exception {
        assertEquals("true", branch("b.xlsx", "gt", "a.xlsx"));
        assertEquals("false", branch("a.xlsx", "gte", "b.xlsx"));
    }

    @Test
    void emptinessOperatorsIgnoreTheRightOperand() throws Exception {
        assertEquals("true", branch("", "is_empty", ""));
        assertEquals("true", branch(null, "is_empty", null));
        assertEquals("true", branch("anything", "is_not_empty", ""));
        assertEquals("false", branch("  ", "is_not_empty", ""));
    }

    @Test
    void outputCarriesBranchAndSummaryForRunWhenAndHistory() throws Exception {
        JsonNode parsed = MAPPER.readTree(tool.flowIf("10", "gt", "9"));
        assertEquals("true", parsed.path("branch").asText());
        assertEquals("gt(\"10\", \"9\") → true", parsed.path("summary").asText());
    }
}
