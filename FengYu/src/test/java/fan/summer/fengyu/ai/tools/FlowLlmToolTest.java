package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The schema/parse/retry contract of the LLM node. The model call itself is stubbed
 * (a scripted {@link #complete}) so the tests pin the output shape without a network:
 * raw text always survives, structured output parses fenced replies, and one targeted
 * repair carries the validation error back into the prompt.
 */
class FlowLlmToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Scripted model: returns queued replies and records the prompts it received. */
    static final class ScriptedTool extends FlowLlmTool {
        final List<String> prompts = new ArrayList<>();
        final ArrayDeque<String> replies = new ArrayDeque<>();
        Exception throwOnCall;

        @Override
        protected String complete(String system, String userPrompt, Double temperature) {
            prompts.add(userPrompt);
            if (throwOnCall != null) throw new RuntimeException(throwOnCall);
            String reply = replies.poll();
            if (reply == null) throw new IllegalStateException("no scripted reply left");
            return reply;
        }
    }

    private static JsonNode run(ScriptedTool tool, String prompt, String schema) throws Exception {
        return MAPPER.readTree(tool.flowLlm(prompt, null, null, schema));
    }

    @Test
    void plainPromptReturnsRawTextWithoutData() throws Exception {
        ScriptedTool tool = new ScriptedTool();
        tool.replies.add("这是模型的回答。");
        JsonNode out = run(tool, "总结这段话", null);
        assertTrue(out.path("success").asBoolean());
        assertEquals("这是模型的回答。", out.path("text").asText());
        assertTrue(out.path("data").isNull());
        assertEquals(1, tool.prompts.size());
        assertTrue(tool.prompts.getFirst().startsWith("总结这段话"));
    }

    @Test
    void schemaReplyIsParsedIntoDataAndKeepsRawText() throws Exception {
        ScriptedTool tool = new ScriptedTool();
        tool.replies.add("```json\n{\"sentiment\":\"正面\"}\n```");
        JsonNode out = run(tool, "判断情感",
                "{\"type\":\"object\",\"properties\":{\"sentiment\":{\"type\":\"string\"}},\"required\":[\"sentiment\"]}");
        assertTrue(out.path("success").asBoolean());
        assertEquals("正面", out.path("data").path("sentiment").asText());
        assertEquals("```json\n{\"sentiment\":\"正面\"}\n```", out.path("text").asText());
        assertEquals(1, tool.prompts.size());
    }

    @Test
    void invalidSchemaReplyTriggersOneTargetedRepairWithTheErrorFedBack() throws Exception {
        ScriptedTool tool = new ScriptedTool();
        tool.replies.add("我觉得是正面");                          // not JSON at all
        tool.replies.add("{\"sentiment\":\"正面\"}");
        JsonNode out = run(tool, "判断情感",
                "{\"type\":\"object\",\"required\":[\"sentiment\"]}");
        assertTrue(out.path("success").asBoolean());
        assertEquals("正面", out.path("data").path("sentiment").asText());
        assertEquals(2, tool.prompts.size());
        // The repair prompt names the exact problem — a targeted fix, not a blind re-roll.
        assertTrue(tool.prompts.get(1).contains("rejected"), tool.prompts.get(1));
        assertTrue(tool.prompts.get(1).contains("not a JSON object"), tool.prompts.get(1));
    }

    @Test
    void missingRequiredFieldIsTheReportedProblem() throws Exception {
        ScriptedTool tool = new ScriptedTool();
        tool.replies.add("{\"other\":1}");
        tool.replies.add("{\"sentiment\":\"负面\"}");
        JsonNode out = run(tool, "判断情感", "{\"required\":[\"sentiment\"]}");
        assertTrue(tool.prompts.get(1).contains("missing required field 'sentiment'"),
                tool.prompts.get(1));
        assertEquals("负面", out.path("data").path("sentiment").asText());
    }

    @Test
    void repairFailureKeepsTheOriginalRawText() throws Exception {
        ScriptedTool tool = new ScriptedTool();
        tool.replies.add("first attempt, not json");
        tool.replies.add("still not json");
        JsonNode out = run(tool, "判断情感", "{\"required\":[\"sentiment\"]}");
        // The model answered, structuring failed — the answer must survive.
        assertTrue(out.path("success").asBoolean());
        assertEquals("first attempt, not json", out.path("text").asText());
        assertTrue(out.path("data").isNull());
        assertEquals(2, tool.prompts.size());
    }

    @Test
    void modelFailureSurfacesAsErrorResult() throws Exception {
        ScriptedTool tool = new ScriptedTool();
        tool.throwOnCall = new IllegalStateException("no API key configured");
        JsonNode out = run(tool, "hi", null);
        assertFalse(out.path("success").asBoolean());
        assertTrue(out.path("error").asText().contains("API key"));
    }

    @Test
    void argumentValidationFailsFastWithoutCallingTheModel() throws Exception {
        ScriptedTool tool = new ScriptedTool();
        assertFalse(MAPPER.readTree(tool.flowLlm("  ", null, null, null)).path("success").asBoolean());
        assertFalse(MAPPER.readTree(tool.flowLlm("hi", null, 5.0, null)).path("success").asBoolean());
        assertFalse(MAPPER.readTree(tool.flowLlm("hi", null, null, "{not json")).path("success")
                .asBoolean());
        assertTrue(tool.prompts.isEmpty(), "no model call for invalid arguments");
    }
}
