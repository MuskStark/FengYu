package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.config.ChatModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The output contract of the LLM node. Two stub seams, one per production path: plain
 * (no schema) tests stub {@link #complete}; structured tests stub the {@link ChatModel}
 * so the reply flows through the REAL ChatClient + {@link StructuredOutputValidationAdvisor}
 * — real draft-2020-12 validation, the single targeted repair with the error fed back,
 * and the raw-text-survives rule when every attempt fails.
 */
class FlowLlmToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Stub seam for the model CALL (plain path): returns queued replies, records prompts. */
    static final class ScriptedTool extends FlowLlmTool {
        final List<String> prompts = new ArrayList<>();
        final ArrayDeque<String> replies = new ArrayDeque<>();
        Exception throwOnCall;

        @Override
        protected String complete(String system, String userPrompt, Double temperature,
                StructuredOutputValidationAdvisor... validation) {
            prompts.add(userPrompt);
            if (throwOnCall != null) throw new RuntimeException(throwOnCall);
            String reply = replies.poll();
            if (reply == null) throw new IllegalStateException("no scripted reply left");
            return reply;
        }
    }

    /**
     * ChatModel-level stub (structured path): the reply flows through the real ChatClient
     * and the real validation advisor, so repair behavior under test is Spring AI's own.
     */
    static final class ScriptedModel implements ChatModel {
        final List<String> prompts = new ArrayList<>();
        final ArrayDeque<String> replies = new ArrayDeque<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getContents());
            String reply = replies.poll();
            if (reply == null) throw new IllegalStateException("no scripted reply left");
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            throw new UnsupportedOperationException();
        }
    }

    private static FlowLlmTool toolWith(ChatModel model) {
        return new FlowLlmTool() {
            @Override
            protected ChatModelConfig.ResolvedModel resolveModel() {
                return new ChatModelConfig.ResolvedModel(model, null);
            }
        };
    }

    private static JsonNode run(ScriptedTool tool, String prompt, String schema) throws Exception {
        return MAPPER.readTree(tool.flowLlm(prompt, null, null, schema));
    }

    // ── plain path ──────────────────────────────────────────────────────────────────────

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

    // ── structured path (real ChatClient + validation advisor) ──────────────────────────

    @Test
    void schemaReplyIsParsedIntoDataAndKeepsRawText() throws Exception {
        ScriptedModel model = new ScriptedModel();
        model.replies.add("{\"sentiment\":\"正面\"}");
        JsonNode out = MAPPER.readTree(toolWith(model).flowLlm("判断情感", null, null,
                "{\"type\":\"object\",\"properties\":{\"sentiment\":{\"type\":\"string\"}},\"required\":[\"sentiment\"]}"));
        assertTrue(out.path("success").asBoolean());
        assertEquals("正面", out.path("data").path("sentiment").asText());
        assertEquals("{\"sentiment\":\"正面\"}", out.path("text").asText(),
                "the raw reply survives verbatim");
        assertEquals(1, model.prompts.size(), "a valid reply passes on the first attempt");
        assertTrue(model.prompts.getFirst().contains("JSON Schema instance"),
                "the converter's format instruction rides along");
    }

    @Test
    void fencedReplyCostsOneRepairButStillParses() throws Exception {
        // The advisor validates the RAW text against the schema (strict JSON), so a
        // fenced reply fails validation and costs one targeted repair; the clean
        // second reply is what the flow sees. This is Spring AI's documented
        // strict-validation semantic — the lenient fence stripping lives in our
        // converter's final parse, not in the validation loop.
        ScriptedModel model = new ScriptedModel();
        model.replies.add("```json\n{\"sentiment\":\"正面\"}\n```");
        model.replies.add("{\"sentiment\":\"正面\"}");
        JsonNode out = MAPPER.readTree(toolWith(model).flowLlm("判断情感", null, null,
                "{\"type\":\"object\",\"required\":[\"sentiment\"]}"));
        assertTrue(out.path("success").asBoolean());
        assertEquals("正面", out.path("data").path("sentiment").asText());
        assertEquals(2, model.prompts.size());
        assertTrue(model.prompts.get(1).contains("validation failed"), model.prompts.get(1));
    }

    @Test
    void invalidSchemaReplyTriggersOneTargetedRepairWithTheErrorFedBack() throws Exception {
        ScriptedModel model = new ScriptedModel();
        model.replies.add("我觉得是正面");                          // not JSON at all
        model.replies.add("{\"sentiment\":\"正面\"}");
        JsonNode out = MAPPER.readTree(toolWith(model).flowLlm("判断情感", null, null,
                "{\"type\":\"object\",\"required\":[\"sentiment\"]}"));
        assertTrue(out.path("success").asBoolean());
        assertEquals("正面", out.path("data").path("sentiment").asText());
        assertEquals(2, model.prompts.size(), "one repair, not a re-roll storm");
        // The advisor feeds the exact validation error back into the user message.
        assertTrue(model.prompts.get(1).contains("validation failed"), model.prompts.get(1));
    }

    @Test
    void missingRequiredFieldIsTheReportedProblem() throws Exception {
        ScriptedModel model = new ScriptedModel();
        model.replies.add("{\"other\":1}");
        model.replies.add("{\"sentiment\":\"负面\"}");
        JsonNode out = MAPPER.readTree(toolWith(model).flowLlm("判断情感", null, null,
                "{\"type\":\"object\",\"required\":[\"sentiment\"]}"));
        assertEquals(2, model.prompts.size(), "the schema violation costs one repair");
        assertTrue(model.prompts.get(1).contains("sentiment"),
                "the validation error names the missing property: " + model.prompts.get(1));
        assertEquals("负面", out.path("data").path("sentiment").asText());
    }

    @Test
    void repairFailureKeepsTheLastRawAnswer() throws Exception {
        ScriptedModel model = new ScriptedModel();
        model.replies.add("first attempt, not json");
        model.replies.add("still not json");
        JsonNode out = MAPPER.readTree(toolWith(model).flowLlm("判断情感", null, null,
                "{\"type\":\"object\",\"required\":[\"sentiment\"]}"));
        // The model answered twice, structuring failed — the LAST answer (closest to
        // valid, the advisor's returned response) must survive.
        assertTrue(out.path("success").asBoolean());
        assertEquals("still not json", out.path("text").asText());
        assertTrue(out.path("data").isNull());
        assertEquals(2, model.prompts.size());
    }

    // ── wall-clock bound ────────────────────────────────────────────────────────────────

    @Test
    void hungModelCallIsAbandonedAtTheDeadlineAndInterrupted() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch gaveUp = new java.util.concurrent.CountDownLatch(1);
        long start = System.nanoTime();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FlowLlmTool.boundedModelCall(1, () -> {
                    entered.countDown();
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException woken) {
                        gaveUp.countDown();
                    }
                    return "late";
                }));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(failure.getMessage().contains("timed out after 1s"), failure.getMessage());
        // The deadline governs: close() must not join the hung call for its full sleep.
        assertTrue(elapsedMs < 30_000, "abandoned in " + elapsedMs + " ms");
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the model call started");
        assertTrue(gaveUp.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the hung call was interrupted, not left to sleep on");
    }

    @Test
    void boundedCallUnwrapsModelFailure() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FlowLlmTool.boundedModelCall(5, () -> {
                    throw new java.io.IOException("connection reset");
                }));
        assertEquals("connection reset", failure.getMessage());
    }
}
