package fan.summer.fengyu.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.codemode.CodeModeExecTool;
import fan.summer.fengyu.ai.tools.ConversationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan's S4 acceptance codex mirrors as {@code interrupt_active_cells}: cancelling
 * the turn terminates every still-running code-mode cell of that conversation (the
 * Terminate command plus the forced context close), instead of leaking the cell threads
 * to their natural end. Driven through the REAL loop driver's cancel path.
 */
class CodeModeTurnInterruptTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Long CONVERSATION = 4242L;

    @AfterEach
    void unbind() {
        ConversationContext.clear();
        CodeModeExecTool.terminateActiveCellsFor(CONVERSATION);
    }

    @Test
    void cancellingTheTurnTerminatesActiveCells() throws Exception {
        ConversationContext.set(CONVERSATION);
        CodeModeExecTool execTool = new CodeModeExecTool(() -> List.of()) {
            @Override protected boolean surfaceOn() { return true; }
        };
        ToolCallback execCallback = callbackFor(execTool);

        AtomicInteger attempts = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                if (attempts.incrementAndGet() == 1) {
                    AssistantMessage am = AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "call_exec", "function", "exec",
                                    "{\"source\":\"// @exec: {\\\"yield_time_ms\\\": 5000}\\n"
                                            + "text('started');\\n"
                                            + "await new Promise(r => setTimeout(r, 60000));\\n"
                                            + "text('never');\\n\"}")))
                            .build();
                    return reactor.core.publisher.Flux.just(
                            new ChatResponse(List.of(new Generation(am))));
                }
                // Round 2 NEVER completes on its own: the worker parks in the stream
                // await, so whenever the cancel lands the turn is still open — the
                // cancel-vs-yield outcome no longer depends on suite-load timing.
                return reactor.core.publisher.Flux.never();
            }
        };

        SpringAiCloudBackend backend = new SpringAiCloudBackend(model);
        backend.setToolCallbacks(List.of(execCallback));

        CountDownLatch errored = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        backend.chat(new ArrayList<>(List.of(AiChatMessage.user("run it"))), 0.7f, 0.9f, 256,
                new AiStreamCallback() {
                    @Override public void onToken(String fragment) { }
                    @Override public void onComplete(String s, int t, double r) { }
                    @Override public void onError(Throwable t) {
                        failure.set(t);
                        errored.countDown();
                    }
                });

        // The exec call holds for 5s before yielding and the cell keeps running its 60s
        // timer — the wide yield window keeps the cancel-vs-yield race out of timing luck
        // (GraalJS context spin-up can eat most of a short window under suite load).
        assertTrue(await(() -> !CodeModeExecTool.liveCellIds(CONVERSATION).isEmpty()),
                "the cell registered as live for the conversation");

        long startNanos = System.nanoTime();
        backend.cancelGeneration();

        assertTrue(errored.await(15, TimeUnit.SECONDS), "the cancelled turn errors out");
        assertTrue(String.valueOf(failure.get()).contains("cancelled"),
                "expected the cancellation error, got: " + failure.get());
        assertTrue(await(() -> CodeModeExecTool.liveCellIds(CONVERSATION).isEmpty()),
                "cancel deregistered the session's cells");

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        assertTrue(elapsedMs < 30_000,
                "the turn ended via interruption, not the cell's 60s natural end ("
                        + elapsedMs + "ms)");
    }

    /** The raw tool as a ToolCallback the loop driver can resolve and invoke. */
    private static ToolCallback callbackFor(CodeModeExecTool tool) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name(CodeModeExecTool.EXEC_NAME)
                        .description("code mode exec")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"source\":{\"type\":\"string\"}},"
                                + "\"required\":[\"source\"]}")
                        .build();
            }

            @Override public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }

            @Override public String call(String toolInput) {
                try {
                    String source = JSON.readTree(toolInput).path("source").asText("");
                    return tool.exec(source);
                } catch (Exception e) {
                    return "Script failed\nScript error:\n" + e.getMessage();
                }
            }
        };
    }

    private static boolean await(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
