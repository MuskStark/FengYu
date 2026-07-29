package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.api.ai.AiStreamCallback;
import fan.summer.fengyu.api.ai.AiToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatToolApprovalGateTest {

    @Test
    void sensitiveToolBlocksUntilApproved() throws Exception {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        AssistantMessage message = toolCall("execute_command");
        AtomicReference<String> approvalId = new AtomicReference<>();
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        Thread.ofVirtual().start(() -> {
            gate.awaitRequiredApprovals(message, List.of(sensitiveTool()), new AiStreamCallback() {
                @Override public void onToken(String fragment) {}
                @Override public void onToolApprovalRequired(
                        String id, AiToolCall call, Instant expiresAt) {
                    approvalId.set(id);
                    requested.countDown();
                }
            });
            completed.countDown();
        });

        assertTrue(requested.await(2, TimeUnit.SECONDS));
        assertFalse(completed.await(100, TimeUnit.MILLISECONDS),
                "approval gate must block before execution");
        assertTrue(gate.resolve(approvalId.get(), true));
        assertTrue(completed.await(2, TimeUnit.SECONDS));
    }

    @Test
    void rejectionAbortsToolExecution() throws Exception {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        AtomicReference<String> approvalId = new AtomicReference<>();
        CountDownLatch requested = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        Thread.ofVirtual().start(() -> {
            try {
                gate.awaitRequiredApprovals(
                        toolCall("execute_command"), List.of(sensitiveTool()), new AiStreamCallback() {
                            @Override public void onToken(String fragment) {}
                            @Override public void onToolApprovalRequired(
                                    String id, AiToolCall call, Instant expiresAt) {
                                approvalId.set(id);
                                requested.countDown();
                            }
                        });
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                completed.countDown();
            }
        });

        assertTrue(requested.await(2, TimeUnit.SECONDS));
        assertTrue(gate.resolve(approvalId.get(), false));
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertTrue(failure.get() instanceof ChatToolApprovalGate.ToolApprovalException);
        assertTrue(failure.get().getMessage().contains("rejected"));
        assertFalse(gate.resolve(approvalId.get(), true), "resolved request must not be reusable");
    }

    @Test
    void safeToolDoesNotRequestApproval() {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        gate.awaitRequiredApprovals(toolCall("safe"), List.of(new SimpleTool("safe")), callback -> {});
    }

    @Test
    void cancellationReleasesPendingApproval() throws Exception {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        Thread.ofVirtual().start(() -> {
            try {
                gate.awaitRequiredApprovals(
                        toolCall("execute_command"), List.of(sensitiveTool()), new AiStreamCallback() {
                            @Override public void onToken(String fragment) {}
                            @Override public void onToolApprovalRequired(
                                    String id, AiToolCall call, Instant expiresAt) {
                                requested.countDown();
                            }
                        });
            } catch (ChatToolApprovalGate.ToolApprovalException expected) {
                completed.countDown();
            }
        });

        assertTrue(requested.await(2, TimeUnit.SECONDS));
        gate.cancelPending();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
    }

    private static AssistantMessage toolCall(String name) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", name, "{\"command\":\"pwd\"}")))
                .build();
    }

    private static ApprovalRequiredToolCallback sensitiveTool() {
        ToolDefinition definition = definition("execute_command");
        return new ApprovalRequiredToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { return input; }
        };
    }

    private static ToolDefinition definition(String name) {
        return DefaultToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{\"type\":\"object\"}")
                .build();
    }

    private static final class SimpleTool implements org.springframework.ai.tool.ToolCallback {
        private final ToolDefinition definition;
        private SimpleTool(String name) { this.definition = definition(name); }
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String input) { return input; }
    }
}
