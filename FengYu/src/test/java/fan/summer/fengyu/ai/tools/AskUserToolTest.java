package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.AiStreamCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ask_user flow end to end at the gate/tool level: the question event fires with
 * the validated payload, the tool blocks until the user resolves it, cancellation aborts,
 * non-interactive contexts get an immediate graceful result, and malformed inputs are
 * rejected before anything reaches the user.
 */
class AskUserToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void unbind() {
        ToolApprovalContext.clear();
    }

    /** Records the fired question and lets the test resolve it asynchronously. */
    private static final class CapturingCallback implements AiStreamCallback {
        final CountDownLatch fired = new CountDownLatch(1);
        final AtomicReference<String> questionId = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        volatile Instant expiresAt;

        @Override public void onToken(String fragment) { }

        @Override
        public void onQuestionRequired(String id, Map<String, Object> payload, Instant expiresAt) {
            this.questionId.set(id);
            this.payload.set(payload);
            this.expiresAt = expiresAt;
            fired.countDown();
        }
    }

    private static AskUserTool.OptionInput option(String label) {
        return new AskUserTool.OptionInput(label, null);
    }

    @Test
    void questionRoundTripsUserAnswers() throws Exception {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        CapturingCallback callback = new CapturingCallback();
        ToolApprovalContext.set(gate, callback);
        AskUserTool tool = new AskUserTool();

        AtomicReference<String> result = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> result.set(tool.askUser(List.of(
                new AskUserTool.QuestionInput("Which database?", "DB",
                        List.of(option("H2"), option("PostgreSQL")), false)))));

        assertTrue(callback.fired.await(5, TimeUnit.SECONDS), "question event fired");
        String id = callback.questionId.get();
        assertNotNull(gate.resolveQuestion(id, Map.of("answers", List.of(
                Map.of("header", "DB", "selected", List.of("PostgreSQL"))))));
        worker.join(5_000);

        JsonNode parsed = JSON.readTree(result.get());
        assertTrue(parsed.path("success").asBoolean());
        assertTrue(parsed.path("answered").asBoolean());
        assertEquals("PostgreSQL",
                parsed.path("answers").get(0).path("selected").get(0).asText());
        // The payload the UI received carries the validated shape.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions = (List<Map<String, Object>>) callback.payload.get().get("questions");
        assertEquals("Which database?", questions.get(0).get("question"));
        assertEquals("DB", questions.get(0).get("header"));
    }

    @Test
    void cancellingTheTurnAbortsTheBlockedQuestion() throws Exception {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        CapturingCallback callback = new CapturingCallback();
        ToolApprovalContext.set(gate, callback);
        AskUserTool tool = new AskUserTool();

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                tool.askUser(List.of(new AskUserTool.QuestionInput(
                        "Proceed?", null, List.of(option("Yes"), option("No")), false)));
            } catch (Throwable t) {
                thrown.set(t);
            }
        });

        assertTrue(callback.fired.await(5, TimeUnit.SECONDS));
        gate.cancelPending();
        worker.join(5_000);
        assertTrue(thrown.get() instanceof ChatToolApprovalGate.ToolApprovalException,
                "expected cancellation exception, got: " + thrown.get());
    }

    @Test
    void nonInteractiveContextsGetGracefulResult() {
        ToolApprovalContext.clear();   // no gate/callback bound (subagents, one-shot flows)
        String result = new AskUserTool().askUser(List.of(new AskUserTool.QuestionInput(
                "Which?", null, List.of(option("A"), option("B")), false)));
        try {
            JsonNode parsed = JSON.readTree(result);
            assertFalse(parsed.path("success").asBoolean());
            assertTrue(parsed.path("error").asText().contains("interactive"));
        } catch (Exception e) {
            throw new AssertionError("result not JSON: " + result, e);
        }
    }

    @Test
    void malformedQuestionsAreRejectedBeforeReachingTheUser() {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        ToolApprovalContext.set(gate, new CapturingCallback());
        AskUserTool tool = new AskUserTool();

        assertFalse(jsonOf(tool.askUser(null)).path("success").asBoolean());
        assertFalse(jsonOf(tool.askUser(List.of())).path("success").asBoolean());
        assertFalse(jsonOf(tool.askUser(List.of(new AskUserTool.QuestionInput(
                "Only one option?", null, List.of(option("A")), false)))).path("success").asBoolean());
        assertFalse(jsonOf(tool.askUser(List.of(new AskUserTool.QuestionInput(
                " ", null, List.of(option("A"), option("B")), false)))).path("success").asBoolean());
    }

    private static JsonNode jsonOf(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + json, e);
        }
    }

    /** Regression: header truncation never splits a surrogate pair — a lone high half
     *  would serialize into a malformed chip label. */
    @Test
    void headerTruncationNeverSplitsASurrogatePair() {
        String header = "abcdefghijk" + "😀" + "xy"; // 11 BMP chars + a surrogate pair
        String truncated = AskUserTool.truncateHeader(header);
        assertTrue(truncated.length() <= 12);
        assertTrue(truncated.length() >= 11);
        assertFalse(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1)),
                "the truncated header must not end on a lone high surrogate");
        assertEquals("abcdefghijk", truncated);
    }
}
