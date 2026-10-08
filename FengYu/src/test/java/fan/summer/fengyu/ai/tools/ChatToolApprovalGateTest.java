package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.security.ProcessSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatToolApprovalGateTest {

    @AfterEach void clearPermissionMode() { AiPermissionContext.clear(); }

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
    void rejectionReturnsBatchInsteadOfAborting() throws Exception {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        AtomicReference<String> approvalId = new AtomicReference<>();
        CountDownLatch requested = new CountDownLatch(1);
        AtomicReference<ChatToolApprovalGate.ApprovalBatch> batch = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        Thread.ofVirtual().start(() -> {
            try {
                batch.set(gate.awaitRequiredApprovals(
                        toolCall("execute_command"), List.of(sensitiveTool()), new AiStreamCallback() {
                            @Override public void onToken(String fragment) {}
                            @Override public void onToolApprovalRequired(
                                    String id, AiToolCall call, Instant expiresAt) {
                                approvalId.set(id);
                                requested.countDown();
                            }
                        }));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                completed.countDown();
            }
        });

        assertTrue(requested.await(2, TimeUnit.SECONDS));
        // Rejection WITH feedback: the turn keeps going and the model sees the feedback.
        assertTrue(gate.resolve(ChatToolApprovalGate.Decision.rejectOnce("use a safer command"),
                approvalId.get()));
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertTrue(failure.get() == null, "a rejection must not abort the turn");
        ChatToolApprovalGate.ApprovalBatch result = batch.get();
        assertTrue(result != null && result.rejections().size() == 1);
        assertTrue(result.rejections().get(0).feedback().contains("safer command"));
        assertFalse(gate.resolve(approvalId.get(), true), "resolved request must not be reusable");
    }

    @Test
    void safeToolDoesNotRequestApproval() {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        gate.awaitRequiredApprovals(toolCall("safe"), List.of(new SimpleTool("safe")), callback -> {});
    }

    @Test
    void approveForMeRunsSafeCommandsButReviewsNetworkEscalation() {
        // Pin a full-sandbox platform (Linux bwrap) so commandPotentiallyUnsafe falls back to the
        // pattern/network heuristics rather than blanket-flagging every command. On a reduced-or-none
        // platform (macOS deny-sensitive, Windows Job Object, NONE) every command needs approval —
        // that's tested implicitly by the gate, but this test pins the full-sandbox branch so the
        // "safe command runs, network command is reviewed" contract is deterministic.
        try (var mocked = org.mockito.Mockito.mockStatic(ProcessSandbox.class)) {
            mocked.when(ProcessSandbox::isNativeSandboxAvailable).thenReturn(true);
            AiPermissionContext.set(AiPermissionMode.APPROVE_FOR_ME);
            assertFalse(ChatToolApprovalGate.requiresApproval(
                toolCall("execute_command").getToolCalls().getFirst(), List.of(sensitiveTool())));
            AssistantMessage network = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-2", "function", "execute_command",
                    "{\"command\":\"curl example.com\",\"allowNetwork\":true}"))).build();
            assertTrue(ChatToolApprovalGate.requiresApproval(
                network.getToolCalls().getFirst(), List.of(sensitiveTool())));
        }
    }

    /**
     * Regression (P0-2/P0-3): on a reduced-or-no-isolation platform every AI-authored command must
     * require approval — there is no enforceable OS boundary to make a command "safe". macOS is now
     * honestly reported as reduced (not full), so on a macOS host commandPotentiallyUnsafe is true.
     */
    @Test
    void everyCommandNeedsApprovalWithoutFullIsolation() {
        try (var mocked = org.mockito.Mockito.mockStatic(ProcessSandbox.class)) {
            mocked.when(ProcessSandbox::isNativeSandboxAvailable).thenReturn(false);
            AiPermissionContext.set(AiPermissionMode.APPROVE_FOR_ME);
            assertTrue(ChatToolApprovalGate.requiresApproval(
                toolCall("execute_command").getToolCalls().getFirst(), List.of(sensitiveTool())),
                "on a reduced/no-isolation platform every command must require approval");
        }
    }

    @Test
    void documentEffectsFollowPermissionProfile() {
        AiPermissionContext.set(AiPermissionMode.ASK_FOR_APPROVAL);
        assertFalse(ChatToolApprovalGate.requiresApproval(
            toolCall("read_document").getToolCalls().getFirst(), List.of(audited("read_document", ToolEffect.READ))));
        assertTrue(ChatToolApprovalGate.requiresApproval(
            toolCall("write_document").getToolCalls().getFirst(), List.of(audited("write_document", ToolEffect.WRITE))));
        AiPermissionContext.set(AiPermissionMode.FULL_ACCESS);
        assertFalse(ChatToolApprovalGate.requiresApproval(
            toolCall("write_document").getToolCalls().getFirst(), List.of(audited("write_document", ToolEffect.WRITE))));
    }

    @Test
    void cancellationReleasesPendingApproval() throws Exception {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> message = new AtomicReference<>();

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
                message.set(expected.getMessage());
                completed.countDown();
            }
        });

        assertTrue(requested.await(2, TimeUnit.SECONDS));
        gate.cancelPending();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertTrue(message.get().contains("cancelled"));
    }

    /**
     * 4.1.0 per-turn cancellation: {@code cancelPendingFor} releases only its own turn's
     * pending approvals — a parallel conversation's card must keep waiting untouched.
     */
    @Test
    void scopedCancellationReleasesOnlyItsOwnTurnsApprovals() throws Exception {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        CountDownLatch requestedA = new CountDownLatch(1);
        CountDownLatch requestedB = new CountDownLatch(1);
        CountDownLatch doneA = new CountDownLatch(1);
        CountDownLatch doneB = new CountDownLatch(1);
        AtomicReference<String> messageA = new AtomicReference<>();

        Thread.ofVirtual().start(() -> awaitApprovalInScope("conversation-1", gate,
                requestedA, doneA, messageA));
        Thread.ofVirtual().start(() -> awaitApprovalInScope("conversation-2", gate,
                requestedB, doneB, new AtomicReference<>()));

        assertTrue(requestedA.await(2, TimeUnit.SECONDS));
        assertTrue(requestedB.await(2, TimeUnit.SECONDS));
        gate.cancelPendingFor("conversation-1");
        assertTrue(doneA.await(2, TimeUnit.SECONDS));
        assertTrue(messageA.get().contains("cancelled"));
        assertFalse(doneB.await(150, TimeUnit.MILLISECONDS),
                "conversation 2's approval must survive conversation 1's cancellation");

        // The global sweep (backend unload) still releases everything, B included.
        gate.cancelPending();
        assertTrue(doneB.await(2, TimeUnit.SECONDS));
    }

    /** Same scoping contract for ask_user questions: cancelling one turn's question
     *  never aborts another conversation's pending question. */
    @Test
    void scopedCancellationReleasesOnlyItsOwnTurnsQuestions() throws Exception {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        CountDownLatch askedA = new CountDownLatch(1);
        CountDownLatch askedB = new CountDownLatch(1);
        CountDownLatch doneA = new CountDownLatch(1);
        CountDownLatch doneB = new CountDownLatch(1);
        AtomicReference<String> messageA = new AtomicReference<>();

        Thread.ofVirtual().start(() -> {
            TurnScope.set("conversation-1");
            try {
                gate.awaitQuestion(Map.of("questions", List.of()), new AiStreamCallback() {
                    @Override public void onToken(String fragment) { }
                    @Override public void onQuestionRequired(
                            String id, Map<String, Object> payload, Instant expiresAt) {
                        askedA.countDown();
                    }
                });
            } catch (ChatToolApprovalGate.ToolApprovalException expected) {
                messageA.set(expected.getMessage());
                doneA.countDown();
            } finally { TurnScope.clear(); }
        });
        Thread.ofVirtual().start(() -> {
            TurnScope.set("conversation-2");
            try {
                gate.awaitQuestion(Map.of("questions", List.of()), new AiStreamCallback() {
                    @Override public void onToken(String fragment) { }
                    @Override public void onQuestionRequired(
                            String id, Map<String, Object> payload, Instant expiresAt) {
                        askedB.countDown();
                    }
                });
            } catch (ChatToolApprovalGate.ToolApprovalException unexpected) {
                doneB.countDown();
            } finally { TurnScope.clear(); }
        });

        assertTrue(askedA.await(2, TimeUnit.SECONDS));
        assertTrue(askedB.await(2, TimeUnit.SECONDS));
        gate.cancelPendingFor("conversation-1");
        assertTrue(doneA.await(2, TimeUnit.SECONDS));
        assertTrue(messageA.get().contains("cancelled"));
        assertFalse(doneB.await(150, TimeUnit.MILLISECONDS),
                "conversation 2's question must survive conversation 1's cancellation");

        gate.cancelPending();
        assertTrue(doneB.await(2, TimeUnit.SECONDS));
    }

    /**
     * Subtree semantics: a parent group's cancel releases entries registered under its
     * CHILD groups ({@code parent/turn-<uuid>}, how nested subagent drivers stamp their
     * approvals) but never another conversation's — while a child's own cancel stays
     * leaf-scoped (pinned by the sibling tests above).
     */
    @Test
    void cancellingAParentGroupReleasesItsNestedTurnsButNotOtherConversations() throws Exception {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        CountDownLatch requestedChildA = new CountDownLatch(1);
        CountDownLatch requestedChildB = new CountDownLatch(1);
        CountDownLatch requestedOther = new CountDownLatch(1);
        CountDownLatch doneChildA = new CountDownLatch(1);
        CountDownLatch doneChildB = new CountDownLatch(1);
        CountDownLatch doneOther = new CountDownLatch(1);

        Thread.ofVirtual().start(() -> awaitApprovalInScope("conversation-1/turn-a", gate,
                requestedChildA, doneChildA, new AtomicReference<>()));
        Thread.ofVirtual().start(() -> awaitApprovalInScope("conversation-1/turn-b", gate,
                requestedChildB, doneChildB, new AtomicReference<>()));
        Thread.ofVirtual().start(() -> awaitApprovalInScope("conversation-2", gate,
                requestedOther, doneOther, new AtomicReference<>()));

        assertTrue(requestedChildA.await(2, TimeUnit.SECONDS));
        assertTrue(requestedChildB.await(2, TimeUnit.SECONDS));
        assertTrue(requestedOther.await(2, TimeUnit.SECONDS));

        gate.cancelPendingFor("conversation-1");
        assertTrue(doneChildA.await(2, TimeUnit.SECONDS), "nested turn a is cancelled with its parent");
        assertTrue(doneChildB.await(2, TimeUnit.SECONDS), "nested turn b is cancelled with its parent");
        assertFalse(doneOther.await(150, TimeUnit.MILLISECONDS),
                "conversation 2's card survives the sibling conversation's subtree sweep");

        gate.cancelPending();
        assertTrue(doneOther.await(2, TimeUnit.SECONDS));
    }

    /** One approval-awaiting turn under a {@link TurnScope} group; shared by the scoped tests. */
    private static void awaitApprovalInScope(String scope, ChatToolApprovalGate gate,
            CountDownLatch requested, CountDownLatch done, AtomicReference<String> message) {
        TurnScope.set(scope);
        try {
            gate.awaitRequiredApprovals(
                    toolCall("execute_command"), List.of(sensitiveTool()), new AiStreamCallback() {
                        @Override public void onToken(String fragment) { }
                        @Override public void onToolApprovalRequired(
                                String id, AiToolCall call, Instant expiresAt) {
                            requested.countDown();
                        }
                    });
        } catch (ChatToolApprovalGate.ToolApprovalException expected) {
            message.set(expected.getMessage());
            done.countDown();
        } finally { TurnScope.clear(); }
    }

    private static AssistantMessage toolCall(String name) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", name, "{\"command\":\"pwd\"}")))
                .build();
    }

    /**
     * {@code awaitSingleApproval} must derive the session-grant key from the callback's
     * REAL effect: with the old null effect the key was a dead bare tool name — the
     * session check for a COMMAND call computes {@code workspace_exec make}, the bare
     * key never matched, and the card's "always this conversation" silently did nothing
     * for nested code-mode calls.
     */
    @Test
    void nestedAlwaysApprovalRegistersAUsableCommandScopedGrantKey() throws Exception {
        ToolGuardService guard = new ToolGuardService(
                new fan.summer.fengyu.ai.hooks.HookDispatcher(), "{}", null);
        ChatToolApprovalGate gate = new ChatToolApprovalGate(guard);
        ConversationContext.set(314L);
        try {
            CountDownLatch requested = new CountDownLatch(1);
            CountDownLatch decided = new CountDownLatch(1);
            AtomicReference<ChatToolApprovalGate.Decision> outcome = new AtomicReference<>();
            AtomicReference<String> approvalId = new AtomicReference<>();
            Thread.ofVirtual().start(() -> {
                outcome.set(gate.awaitSingleApproval("workspace_exec",
                        "{\"command\":\"make build\"}", new AiStreamCallback() {
                            @Override public void onToken(String fragment) {}
                            @Override public void onToolApprovalRequired(
                                    String id, AiToolCall call, Instant expiresAt) {
                                approvalId.set(id);
                                requested.countDown();
                            }
                        }, ToolEffect.COMMAND));
                decided.countDown();
            });

            assertTrue(requested.await(2, TimeUnit.SECONDS));
            assertTrue(gate.resolve(
                    new ChatToolApprovalGate.Decision(true, true, null), approvalId.get()));
            assertTrue(decided.await(2, TimeUnit.SECONDS));
            assertTrue(outcome.get().approved());

            // The registered grant actually covers the approved command — and only it.
            AuditedToolCallback exec = audited("workspace_exec", ToolEffect.COMMAND);
            assertEquals(ToolGuardService.Verdict.ALLOW, guard.decide("workspace_exec", exec,
                    "{\"command\":\"make build\"}", AiPermissionMode.ASK_FOR_APPROVAL, null)
                    .verdict(), "the always-grant keys on the command prefix");
            assertEquals(ToolGuardService.Verdict.ASK, guard.decide("workspace_exec", exec,
                    "{\"command\":\"rm -rf src\"}", AiPermissionMode.ASK_FOR_APPROVAL, null)
                    .verdict(), "a different command still asks");
        } finally {
            ConversationContext.clear();
        }
    }

    /**
     * Parallel tool execution (ToolBatchExecutor) only parallelizes the EXECUTION phase;
     * this pins the gate's contract for that world: approvals are requested strictly in
     * tool-call order, READ calls never interrupt the sequence, and the batch comes back
     * empty once every card is approved.
     */
    @Test
    void approvalsAreRequestedInToolCallOrderAcrossAMixedBatch() {
        AiPermissionContext.set(AiPermissionMode.ASK_FOR_APPROVAL);
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        AssistantMessage batch = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "read_file", "{}"),
                        new AssistantMessage.ToolCall("c2", "function", "write_a", "{}"),
                        new AssistantMessage.ToolCall("c3", "function", "grep", "{}"),
                        new AssistantMessage.ToolCall("c4", "function", "write_b", "{}")))
                .build();
        List<String> approvalOrder = new java.util.concurrent.CopyOnWriteArrayList<>();

        ChatToolApprovalGate.ApprovalBatch result = gate.awaitRequiredApprovals(batch,
                List.of(audited("read_file", ToolEffect.READ),
                        audited("write_a", ToolEffect.WRITE),
                        audited("grep", ToolEffect.READ),
                        audited("write_b", ToolEffect.WRITE)),
                new AiStreamCallback() {
                    @Override public void onToken(String fragment) {}
                    @Override public void onToolApprovalRequired(
                            String approvalId, AiToolCall toolCall, Instant expiresAt) {
                        approvalOrder.add(toolCall.name());
                        // Resolve inside the callback: the latch is already armed, so the
                        // gate proceeds to the next call in order deterministically.
                        gate.resolve(approvalId, true);
                    }
                });

        assertTrue(result.isEmpty(), "approved calls must not come back as rejections");
        assertEquals(List.of("write_a", "write_b"), approvalOrder,
                "only non-READ calls ask, in tool-call order");
    }

    private static ApprovalRequiredToolCallback sensitiveTool() {
        ToolDefinition definition = definition("execute_command");
        return new ApprovalRequiredToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { return input; }
        };
    }

    private static AuditedToolCallback audited(String name, ToolEffect effect) {
        ToolDefinition definition = definition(name);
        return new AuditedToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolEffect effect() { return effect; }
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
