package fan.summer.ai.tools;

import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;
import fan.summer.api.ai.AiToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionGemmaAdapterTest {

    private static AiTool tool(String name, String desc, AiToolParam... params) {
        return new AiTool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return desc; }
            @Override public List<AiToolParam> getParameters() { return List.of(params); }
            @Override public AiToolResult execute(Map<String, Object> arguments) { return AiToolResult.success("{}"); }
        };
    }

    @Test
    void buildToolDeclarations_emitsEnumForConstrainedParam() {
        AiTool t = tool("excel_configure", "Configure split",
            AiToolParam.of("mode", "string", "Split mode", true, List.of("BY_SHEET", "BY_COLUMN", "COMPLEX")));
        String decl = new FunctionGemmaAdapter().buildToolDeclarations(List.of(t));
        assertTrue(decl.contains("enum:[BY_SHEET,BY_COLUMN,COMPLEX]"),
            "enum must appear in declaration; was:\n" + decl);
    }

    @Test
    void buildToolDeclarations_omitsEnumWhenAbsent() {
        AiTool t = tool("excel_analyze", "Analyze",
            AiToolParam.of("filePath", "string", "Path", true));
        String decl = new FunctionGemmaAdapter().buildToolDeclarations(List.of(t));
        assertFalse(decl.contains("enum:"), "no enum when param has none; was:\n" + decl);
    }

    @Test
    void parseToolCalls_parsesSingleCallWithDelimitedString() {
        String out = "<start_function_call>call:excel_analyze{filePath:🪙/a,b/c.xlsx🪙}<end_function_call>";
        var calls = new FunctionGemmaAdapter().parseToolCalls(out);
        assertEquals(1, calls.size());
        assertEquals("excel_analyze", calls.get(0).name());
        assertEquals("/a,b/c.xlsx", calls.get(0).arguments().get("filePath"));
    }

    @Test
    void parseToolCalls_parsesMultipleCalls() {
        String out = "<start_function_call>call:t1{k:🪙v1🪙}<end_function_call>"
                   + "<start_function_call>call:t2{k:🪙v2🪙}<end_function_call>";
        var calls = new FunctionGemmaAdapter().parseToolCalls(out);
        assertEquals(2, calls.size());
        assertEquals("t1", calls.get(0).name());
        assertEquals("t2", calls.get(1).name());
    }

    @Test
    void parseToolCalls_toleratesBraceInsideDelimitedValue() {
        String out = "<start_function_call>call:t{k:🪙a}b🪙}<end_function_call>";
        var calls = new FunctionGemmaAdapter().parseToolCalls(out);
        assertEquals(1, calls.size(), "must still match with a brace inside the value");
        assertEquals("a}b", calls.get(0).arguments().get("k"));
    }

    @Test
    void parseToolCalls_toleratesMissingDelimiters() {
        String out = "<start_function_call>call:t{k:plain}<end_function_call>";
        var calls = new FunctionGemmaAdapter().parseToolCalls(out);
        assertEquals(1, calls.size());
        assertEquals("plain", calls.get(0).arguments().get("k"));
    }
}
