package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.security.ProcessSandbox;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Blocks ordinary-chat tool execution until the user resolves an approval request.
 *
 * <p>Rejections (optionally with the user's free-text feedback) do NOT abort the turn:
 * {@link #awaitRequiredApprovals} returns them as an {@link ApprovalBatch} and the caller
 * answers the round with synthesized tool results so the model can adjust and continue —
 * terminal coding-agent practice. Only timeouts, cancellations, and hook/rule denials
 * still throw {@link ToolApprovalException}.</p>
 */
@Component
public class ChatToolApprovalGate {

    static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);

    /** Synthetic ids for blank-id providers' tool calls; same-millis collisions would
     *  cross-match two calls, so a monotonic counter instead of a timestamp. */
    private static final java.util.concurrent.atomic.AtomicLong BLANK_ID_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();

    /** Optional layered guard (hooks + permission rules); null keeps the legacy mode-only policy. */
    private final ToolGuardService guard;

    public ChatToolApprovalGate() {
        this(null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatToolApprovalGate(ToolGuardService guard) {
        this.guard = guard;
    }

    /** One user-rejected call (feedback optional). */
    public record Rejection(String toolCallId, String name, String feedback) {}

    /** The rejections of one approval batch; empty when every call was approved. */
    public record ApprovalBatch(List<Rejection> rejections) {
        public ApprovalBatch {
            rejections = List.copyOf(rejections);
        }
        public boolean isEmpty() { return rejections.isEmpty(); }
    }

    /**
     * Requests approval for every sensitive call in a model response before any tool runs.
     * A configured deny (hook veto or permission rule) rejects the call outright with its
     * reason; an explicit allow skips the prompt. User rejections come back as the returned
     * batch instead of an exception.
     */
    public ApprovalBatch awaitRequiredApprovals(AssistantMessage assistantMessage,
                                                List<ToolCallback> availableTools,
                                                AiStreamCallback callback) {
        if (assistantMessage == null || !assistantMessage.hasToolCalls()) {
            return new ApprovalBatch(List.of());
        }
        List<Rejection> rejections = new ArrayList<>();
        for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
            ToolCallback tool = resolve(call, availableTools);
            if (guard != null) {
                ToolGuardService.GuardDecision decision = guard.decide(call.name(), tool,
                        call.arguments(), AiPermissionContext.current(), null);
                if (decision.verdict() == ToolGuardService.Verdict.DENY) {
                    throw new ToolApprovalException(decision.reason());
                }
                if (decision.verdict() == ToolGuardService.Verdict.ALLOW) continue;
                Rejection rejection = awaitApproval(call, callback, availableTools);
                if (rejection != null) rejections.add(rejection);
                continue;
            }
            if (!requiresApproval(call, availableTools)) continue;
            Rejection rejection = awaitApproval(call, callback, availableTools);
            if (rejection != null) rejections.add(rejection);
        }
        return new ApprovalBatch(rejections);
    }

    /**
     * Sandbox-escape approval: the OS fence denied a command mid-execution, and running
     * it OUTSIDE the sandbox needs the user's explicit one-time grant. Same card, same
     * resolve path, same timeout as an ordinary approval — the synthetic call carries
     * {@code escape: true} plus the command and the denial excerpt so the card shows
     * exactly what would run unfenced. "Always" is deliberately ignored (a session-wide
     * escape grant would be broader than the per-call approval philosophy here).
     *
     * @return the user's decision; {@link Decision#approved()} grants the escape,
     *         a rejection carries optional feedback for the model.
     */
    public Decision awaitEscapeApproval(String toolName, String command, String denialReason,
            AiStreamCallback callback) {
        String approvalId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(APPROVAL_TIMEOUT);
        PendingApproval request = new PendingApproval(
                new CountDownLatch(1), new AtomicReference<>(), new AtomicReference<>(), expiresAt,
                ConversationContext.current(), toolName, null, null);
        pending.put(approvalId, request);

        java.util.Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("escape", true);
        arguments.put("command", command);
        if (denialReason != null && !denialReason.isBlank()) {
            arguments.put("denialReason", denialReason);
        }
        callback.onToolApprovalRequired(
                approvalId, AiToolCall.of(approvalId, toolName, arguments), expiresAt);

        try {
            boolean resolved = request.latch().await(APPROVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!resolved) {
                throw new ToolApprovalException("Sandbox-escape approval timed out: " + command);
            }
            if (request.decision().get() == ApprovalOutcome.CANCELLED) {
                throw new ToolApprovalException("Sandbox-escape approval cancelled: " + command);
            }
            return request.decision().get() == ApprovalOutcome.APPROVED
                    ? Decision.approveOnce()
                    : Decision.rejectOnce(request.feedback().get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolApprovalException("Sandbox-escape approval interrupted: " + command);
        } finally {
            pending.remove(approvalId, request);
        }
    }

    /**
     * The layered guard's verdict for one (nested) call — the SAME first step
     * {@link #awaitRequiredApprovals} applies to model-issued batches: a configured deny
     * rejects outright, an explicit allow suppresses the prompt. Null when no guard is
     * wired (the legacy gate).
     */
    public ToolGuardService.GuardDecision guardDecision(String toolName, ToolCallback tool,
            String argumentsJson) {
        if (guard == null) return null;
        return guard.decide(toolName, tool, argumentsJson, AiPermissionContext.current(), null);
    }

    /**
     * Single-call approval for a NESTED tool call raised from inside a code-mode cell —
     * code mode is never an approval bypass: a nested call that the policy flags asks
     * exactly like a model-issued one would, through the same card and resolve path.
     */
    public Decision awaitSingleApproval(String toolName, String argumentsJson,
            AiStreamCallback callback) {
        String approvalId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(APPROVAL_TIMEOUT);
        // A session grant key, so the card's "always this conversation" is real for
        // nested calls too (null keeps it a one-shot — same as an unresolvable batch call).
        String grantKey = fan.summer.fengyu.ai.tools.ToolGuardService.grantKey(toolName, null,
                argumentsJson);
        PendingApproval request = new PendingApproval(
                new CountDownLatch(1), new AtomicReference<>(), new AtomicReference<>(), expiresAt,
                ConversationContext.current(), toolName, grantKey, null);
        pending.put(approvalId, request);
        callback.onToolApprovalRequired(approvalId,
                AiToolCall.of(approvalId, toolName, parseArguments(argumentsJson)), expiresAt);
        try {
            boolean resolved = request.latch().await(APPROVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!resolved) {
                throw new ToolApprovalException("Tool approval timed out: " + toolName);
            }
            if (request.decision().get() == ApprovalOutcome.CANCELLED) {
                throw new ToolApprovalException("Tool approval cancelled: " + toolName);
            }
            return request.decision().get() == ApprovalOutcome.APPROVED
                    ? Decision.approveOnce()
                    : Decision.rejectOnce(request.feedback().get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolApprovalException("Tool approval interrupted: " + toolName);
        } finally {
            pending.remove(approvalId, request);
        }
    }

    /** Legacy binary resolve (approve-once / reject-once without feedback). */
    public boolean resolve(String approvalId, boolean approved) {
        return resolve(approved ? Decision.approveOnce() : Decision.rejectOnce(null), approvalId);
    }

    /** Rich decision from the upgraded approval card: approve/reject × once/always + feedback. */
    public boolean resolve(Decision decision, String approvalId) {
        PendingApproval request = pending.get(approvalId);
        if (request == null || Instant.now().isAfter(request.expiresAt())) return false;
        ApprovalOutcome outcome = decision.approved()
                ? ApprovalOutcome.APPROVED : ApprovalOutcome.REJECTED;
        if (!request.decision().compareAndSet(null, outcome)) return false;
        if (decision.feedback() != null && !decision.feedback().isBlank()) {
            request.feedback().set(decision.feedback().trim());
        }
        // "Always allow" (this conversation) registers a session grant — command-effect
        // tools grant only their two-word command prefix (see ToolGuardService.grantKey).
        if (decision.approved() && decision.always() && guard != null && request.conversationId() != null) {
            guard.grantSessionTool(request.conversationId(), request.grantKey());
        }
        // "Always allow" on a workspace command ALSO persists an exec-policy rule (the
        // codex amend flow): the session grant covers this conversation, the rule file
        // covers the future. BANNED prefixes (shells, interpreters, sudo) are refused
        // inside the amend — they never become permanent grants.
        if (decision.approved() && decision.always() && request.amendTokens() != null
                && !request.amendTokens().isEmpty()) {
            if (fan.summer.fengyu.ai.sandbox.ExecPolicy.amend(
                    fan.summer.fengyu.ai.sandbox.ExecPolicy.DEFAULT_RULES_DIR,
                    fan.summer.fengyu.ai.sandbox.ExecPolicy.amendablePrefix(request.amendTokens()))) {
                WorkspaceExecTool.reloadExecPolicy();
            }
        }
        request.latch().countDown();
        return true;
    }

    /** The upgraded card's decision body. */
    public record Decision(boolean approved, boolean always, String feedback) {
        public static Decision approveOnce() { return new Decision(true, false, null); }
        public static Decision rejectOnce(String feedback) { return new Decision(false, false, feedback); }
    }

    /**
     * Cancels every outstanding request. FengYu permits only one active chat generation, so this
     * is used when that generation is cancelled or the backend is replaced.
     */
    public void cancelPending() {
        pending.forEach((id, request) -> {
            if (request.decision().compareAndSet(null, ApprovalOutcome.CANCELLED)) {
                request.latch().countDown();
            }
        });
    }

    /** @return the rejection when the user declined this call, null when approved. */
    private Rejection awaitApproval(AssistantMessage.ToolCall call, AiStreamCallback callback,
                                    List<ToolCallback> availableTools) {
        String approvalId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(APPROVAL_TIMEOUT);
        String toolCallId = call.id() == null || call.id().isBlank() ? approvalId : call.id();
        ToolCallback tool = resolve(call, availableTools);
        ToolEffect effect = tool instanceof AuditedToolCallback audited ? audited.effect() : null;
        String grantKey = ToolGuardService.grantKey(call.name(), effect, call.arguments());
        PendingApproval request = new PendingApproval(
                new CountDownLatch(1), new AtomicReference<>(), new AtomicReference<>(), expiresAt,
                ConversationContext.current(), call.name(), grantKey, amendTokensOf(call));
        pending.put(approvalId, request);

        callback.onToolApprovalRequired(
                approvalId,
                AiToolCall.of(toolCallId, call.name(), parseArguments(call.arguments())),
                expiresAt);

        try {
            boolean resolved = request.latch().await(APPROVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!resolved) {
                throw new ToolApprovalException("Tool approval timed out: " + call.name());
            }
            if (request.decision().get() == ApprovalOutcome.CANCELLED) {
                throw new ToolApprovalException("Tool approval cancelled: " + call.name());
            }
            if (request.decision().get() != ApprovalOutcome.APPROVED) {
                return new Rejection(toolCallId, call.name(), request.feedback().get());
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolApprovalException("Tool approval interrupted: " + call.name());
        } finally {
            pending.remove(approvalId, request);
        }
    }

    static boolean requiresApproval(AssistantMessage.ToolCall call, List<ToolCallback> tools) {
        ToolCallback tool = resolve(call, tools);
        return ToolApprovalPolicy.requiresApproval(
                tool, AiPermissionContext.current(), call.arguments());
    }

    private static ToolCallback resolve(AssistantMessage.ToolCall call, List<ToolCallback> tools) {
        if (tools == null) return null;
        return tools.stream()
            .filter(candidate -> candidate.getToolDefinition().name().equals(call.name()))
            .findFirst().orElse(null);
    }

    /**
     * Answers a round whose approval batch contains rejections: appends the assistant
     * tool-call message plus a synthesized {@link ToolResponseMessage} — rejected calls get
     * the user's verdict (and feedback), approved-but-unexecuted siblings get an honest
     * "skipped" note — mirrors everything into FengYu history, and fires the SSE tool
     * results. The model receives actionable guidance and continues the loop.
     */
    public static List<Message> appendRejectedResults(List<Message> conversation,
            List<AiChatMessage> history, AssistantMessage assistantMsg,
            ApprovalBatch batch, AiStreamCallback callback) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall call : assistantMsg.getToolCalls()) {
            String id = call.id() != null && !call.id().isEmpty()
                    ? call.id() : "tc_" + BLANK_ID_SEQ.incrementAndGet();
            Rejection rejection = batch.rejections().stream()
                    .filter(item -> id.equals(item.toolCallId()))
                    .findFirst().orElseGet(() -> {
                        // Blank-id providers: the gate keyed the rejection by its approvalId;
                        // fall back to a name match only when exactly one such rejection
                        // exists, so two same-tool calls can never cross-match.
                        List<Rejection> byName = batch.rejections().stream()
                                .filter(item -> call.name().equals(item.name())).toList();
                        return byName.size() == 1 ? byName.getFirst() : null;
                    });
            String text;
            if (rejection != null) {
                text = "The user REJECTED this tool call"
                        + (rejection.feedback() == null || rejection.feedback().isBlank()
                            ? ". Adjust the approach accordingly or ask how to proceed; do not retry the identical call."
                            : " with the feedback: \"" + rejection.feedback()
                              + "\". Follow that feedback; do not retry the identical call.");
            } else {
                text = "Skipped: another call in the same batch was rejected by the user, so "
                        + "nothing in this batch ran. If you still need this call, request it again.";
            }
            responses.add(new ToolResponseMessage.ToolResponse(id, call.name(), text));
            history.add(AiChatMessage.toolResult(id, call.name(), text));
            callback.onToolResult(id, AiToolResult.error(text));
        }
        List<Message> extended = new ArrayList<>(conversation);
        extended.add(assistantMsg);
        extended.add(ToolResponseMessage.builder().responses(responses).build());
        return extended;
    }

    public static boolean commandPotentiallyUnsafe(String arguments) {
        // Without an enforceable OS sandbox every AI-authored command is potentially unsafe.
        // The patterns below are only a usability optimisation when the native boundary exists.
        if (!ProcessSandbox.isNativeSandboxAvailable()) return true;
        Map<String, Object> args = parseArguments(arguments);
        if (Boolean.TRUE.equals(args.get("allowNetwork"))) return true;
        String command = args.get("command") instanceof String value ? value.toLowerCase(java.util.Locale.ROOT) : "";
        if (java.util.regex.Pattern.compile(
                "(^|[;&|\\s])(sudo|su|shutdown|reboot|mkfs|dd)([;&|\\s]|$)|"
                + "rm\\s+[^\\n]*(?:-[^\\n]*r|--recursive)|git\\s+(?:reset\\s+--hard|clean\\s+-)|"
                + "curl[^\\n]*\\|\\s*(?:sh|bash)|chmod\\s+[^\\n]*777")
                .matcher(command).find()) return true;
        Object raw = args.get("workingDirectory");
        if (!(raw instanceof String value) || value.isBlank()) return false;
        try {
            java.nio.file.Path workspace = java.nio.file.Path.of(System.getProperty("user.dir")).toRealPath();
            java.nio.file.Path workdir = java.nio.file.Path.of(value).toRealPath();
            return !workdir.startsWith(workspace);
        } catch (Exception ignored) {
            return true;
        }
    }

    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JsonHelper.parseObject(json);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private record PendingApproval(CountDownLatch latch,
                                   AtomicReference<ApprovalOutcome> decision,
                                   AtomicReference<String> feedback,
                                   Instant expiresAt,
                                   Long conversationId,
                                   String toolName,
                                   String grantKey,
                                   java.util.List<String> amendTokens) {
    }

    /** The command tokens an "always allow" would amend into the exec policy (exec tools only). */
    private static java.util.List<String> amendTokensOf(AssistantMessage.ToolCall call) {
        if (!"workspace_exec".equals(call.name())) return null;
        try {
            java.util.Map<String, Object> args = parseArguments(call.arguments());
            if (args.get("command") instanceof String command) {
                return java.util.Arrays.stream(command.trim().split("\\s+"))
                        .filter(token -> !token.isBlank())
                        .toList();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private enum ApprovalOutcome { APPROVED, REJECTED, CANCELLED }

    public static final class ToolApprovalException extends RuntimeException {
        public ToolApprovalException(String message) {
            super(message);
        }
    }
}
