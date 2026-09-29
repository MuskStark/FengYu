package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ToolEffect;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Effect-grouped scheduling of a model round's tool calls (the parallel_tool_calls
 * read/write-lock discipline): READ runs execute concurrently on virtual threads,
 * WRITE/COMMAND/EXTERNAL calls execute alone and act as barriers, results come back in
 * the original call order, and unknown/plain callbacks schedule exclusively.
 */
class ToolBatchExecutorTest {

    private static final long UNIT = 150; // ms per fake tool; serial baseline = UNIT * calls

    /** Records [startNanos, endNanos] and the executing thread id for every invocation. */
    private static final class FakeTool implements AuditedToolCallback {
        final String name;
        final ToolEffect effect;
        final long sleepMillis;
        final List<long[]> spans = new CopyOnWriteArrayList<>();
        final List<Long> threadIds = new CopyOnWriteArrayList<>();
        private final ToolDefinition definition;

        FakeTool(String name, ToolEffect effect, long sleepMillis) {
            this.name = name;
            this.effect = effect;
            this.sleepMillis = sleepMillis;
            this.definition = DefaultToolDefinition.builder()
                    .name(name).description("fake").inputSchema("{\"type\":\"object\"}").build();
        }

        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public ToolEffect effect() { return effect; }
        @Override public String call(String input) {
            threadIds.add(Thread.currentThread().threadId());
            long start = System.nanoTime();
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            spans.add(new long[] {start, System.nanoTime()});
            return name + ":ok";
        }
    }

    /** A non-audited callback: the scheduler must treat it as exclusive. */
    private static final class PlainTool implements ToolCallback {
        final String name;
        final List<long[]> spans = new CopyOnWriteArrayList<>();
        private final ToolDefinition definition;

        PlainTool(String name) {
            this.name = name;
            this.definition = DefaultToolDefinition.builder()
                    .name(name).description("plain").inputSchema("{\"type\":\"object\"}").build();
        }

        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String input) {
            long start = System.nanoTime();
            try {
                Thread.sleep(UNIT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            spans.add(new long[] {start, System.nanoTime()});
            return name + ":ok";
        }
    }

    private static AssistantMessage round(AssistantMessage.ToolCall... calls) {
        return AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    }

    private static AssistantMessage.ToolCall call(String id, String name) {
        return new AssistantMessage.ToolCall(id, "function", name, "{}");
    }

    private static Prompt prompt(ToolCallback... callbacks) {
        return new Prompt(List.of(new UserMessage("run the batch")),
                ToolCallingChatOptions.builder().toolCallbacks(List.of(callbacks)).build());
    }

    private static ToolResponseMessage responses(ToolExecutionResult result) {
        Message last = result.conversationHistory().get(result.conversationHistory().size() - 1);
        return (ToolResponseMessage) last;
    }

    private static boolean overlaps(long[] a, long[] b) {
        return a[0] < b[1] && b[0] < a[1];
    }

    @Test
    void readRunsExecuteConcurrently() {
        FakeTool a = new FakeTool("read_a", ToolEffect.READ, UNIT);
        FakeTool b = new FakeTool("read_b", ToolEffect.READ, UNIT);
        FakeTool c = new FakeTool("read_c", ToolEffect.READ, UNIT);
        ToolCallingManager manager = ToolCallingManager.builder().build();

        long start = System.nanoTime();
        ToolExecutionResult result = ToolBatchExecutor.executeToolCalls(manager,
                prompt(a, b, c), round(call("1", "read_a"), call("2", "read_b"),
                        call("3", "read_c")), List.of(a, b, c));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // 3 × UNIT serial = 450ms; concurrent must beat it by at least a full unit.
        assertTrue(elapsedMillis < 2 * UNIT, "reads should overlap, took " + elapsedMillis + "ms");
        assertEquals(3, responses(result).getResponses().size());
        assertTrue(a.threadIds.stream().distinct().count() + b.threadIds.stream().distinct().count()
                        + c.threadIds.stream().distinct().count() >= 2,
                "reads must run on different threads");
    }

    @Test
    void writeCallsExecuteSeriallyWithoutOverlap() {
        FakeTool w1 = new FakeTool("write_one", ToolEffect.WRITE, UNIT);
        FakeTool w2 = new FakeTool("write_two", ToolEffect.WRITE, UNIT);
        ToolCallingManager manager = ToolCallingManager.builder().build();

        long start = System.nanoTime();
        ToolBatchExecutor.executeToolCalls(manager, prompt(w1, w2),
                round(call("1", "write_one"), call("2", "write_two")), List.of(w1, w2));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis >= 2 * UNIT - 30, "writes must serialize, took "
                + elapsedMillis + "ms");
        assertTrue(!overlaps(w1.spans.getFirst(), w2.spans.getFirst()),
                "write spans must not overlap");
    }

    @Test
    void writeActsAsABarrierBetweenReadRuns() {
        FakeTool readA = new FakeTool("read_a", ToolEffect.READ, UNIT);
        FakeTool write = new FakeTool("write", ToolEffect.WRITE, UNIT);
        FakeTool readB = new FakeTool("read_b", ToolEffect.READ, UNIT);
        ToolCallingManager manager = ToolCallingManager.builder().build();

        ToolBatchExecutor.executeToolCalls(manager, prompt(readA, write, readB),
                round(call("1", "read_a"), call("2", "write"), call("3", "read_b")),
                List.of(readA, write, readB));

        assertTrue(!overlaps(readA.spans.getFirst(), write.spans.getFirst()),
                "the write must wait for the preceding read run");
        assertTrue(!overlaps(write.spans.getFirst(), readB.spans.getFirst()),
                "the following read run must wait for the write");
        assertTrue(readB.spans.getFirst()[0] >= write.spans.getFirst()[1],
                "read_b starts only after the write finished");
    }

    @Test
    void resultsStayInOriginalCallOrderWithStableConversationShape() {
        FakeTool writeA = new FakeTool("write_a", ToolEffect.WRITE, 10);
        FakeTool readB = new FakeTool("read_b", ToolEffect.READ, UNIT);
        FakeTool readC = new FakeTool("read_c", ToolEffect.READ, 10);
        FakeTool writeD = new FakeTool("write_d", ToolEffect.WRITE, 10);
        ToolCallingManager manager = ToolCallingManager.builder().build();
        Prompt prompt = prompt(writeA, readB, readC, writeD);
        AssistantMessage assistant = round(call("i1", "write_a"), call("i2", "read_b"),
                call("i3", "read_c"), call("i4", "write_d"));

        ToolExecutionResult result = ToolBatchExecutor.executeToolCalls(
                manager, prompt, assistant, List.of(writeA, readB, readC, writeD));

        List<Message> history = result.conversationHistory();
        assertEquals(3, history.size(), "instructions + assistant + one tool response");
        assertSame(assistant, history.get(1), "the model's own assistant message is kept");
        ToolResponseMessage response = responses(result);
        assertEquals(4, response.getResponses().size());
        assertEquals("i1", response.getResponses().get(0).id());
        assertEquals("write_a", response.getResponses().get(0).name());
        assertEquals("i2", response.getResponses().get(1).id());
        assertEquals("i3", response.getResponses().get(2).id());
        assertEquals("i4", response.getResponses().get(3).id());
        assertEquals("write_a:ok", response.getResponses().get(0).responseData());
        assertNotEquals("", response.getResponses().get(1).responseData());
    }

    @Test
    void plainCallbacksScheduleExclusively() {
        // A non-audited callback carries no effect classification — the scheduler must
        // default it to exclusive rather than run it beside the READ run.
        FakeTool read = new FakeTool("read", ToolEffect.READ, UNIT);
        PlainTool plain = new PlainTool("plain");
        ToolCallingManager manager = ToolCallingManager.builder().build();

        ToolBatchExecutor.executeToolCalls(manager, prompt(read, plain),
                round(call("1", "read"), call("2", "plain")), List.of(read, plain));

        assertTrue(!overlaps(read.spans.getFirst(), plain.spans.getFirst()),
                "a plain (non-audited) callback must not overlap reads");
    }

    @Test
    void singleCallTakesTheDirectManagerPath() {
        FakeTool only = new FakeTool("only", ToolEffect.WRITE, 1);
        ToolCallingManager manager = ToolCallingManager.builder().build();

        ToolExecutionResult result = ToolBatchExecutor.executeToolCalls(manager,
                prompt(only), round(call("solo", "only")), List.of(only));

        ToolResponseMessage response = responses(result);
        assertEquals(1, response.getResponses().size());
        assertEquals("only", response.getResponses().getFirst().name());
        assertEquals("only:ok", response.getResponses().getFirst().responseData());
    }
}
