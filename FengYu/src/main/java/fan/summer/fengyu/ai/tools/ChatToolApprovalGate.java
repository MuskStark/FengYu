package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.security.ProcessSandbox;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Blocks ordinary-chat tool execution until the user resolves an approval request.
 */
@Component
public class ChatToolApprovalGate {

    static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);

    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();

    /**
     * Requests approval for every sensitive call in a model response before any tool runs.
     */
    public void awaitRequiredApprovals(AssistantMessage assistantMessage,
                                       List<ToolCallback> availableTools,
                                       AiStreamCallback callback) {
        if (assistantMessage == null || !assistantMessage.hasToolCalls()) return;
        for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
            if (!requiresApproval(call, availableTools)) continue;
            awaitApproval(call, callback);
        }
    }

    public boolean resolve(String approvalId, boolean approved) {
        PendingApproval request = pending.get(approvalId);
        if (request == null || Instant.now().isAfter(request.expiresAt())) return false;
        if (!request.decision().compareAndSet(null, approved)) return false;
        request.latch().countDown();
        return true;
    }

    /**
     * Rejects every outstanding request. FengYu permits only one active chat generation, so this
     * is used when that generation is cancelled or the backend is replaced.
     */
    public void cancelPending() {
        pending.forEach((id, request) -> {
            if (request.decision().compareAndSet(null, false)) {
                request.latch().countDown();
            }
        });
    }

    private void awaitApproval(AssistantMessage.ToolCall call, AiStreamCallback callback) {
        String approvalId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(APPROVAL_TIMEOUT);
        PendingApproval request = new PendingApproval(
                new CountDownLatch(1), new AtomicReference<>(), expiresAt);
        pending.put(approvalId, request);

        String toolCallId = call.id() == null || call.id().isBlank() ? approvalId : call.id();
        callback.onToolApprovalRequired(
                approvalId,
                AiToolCall.of(toolCallId, call.name(), parseArguments(call.arguments())),
                expiresAt);

        try {
            boolean resolved = request.latch().await(APPROVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!resolved) {
                throw new ToolApprovalException("Tool approval timed out: " + call.name());
            }
            if (!Boolean.TRUE.equals(request.decision().get())) {
                throw new ToolApprovalException("Tool execution rejected: " + call.name());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolApprovalException("Tool approval interrupted: " + call.name());
        } finally {
            pending.remove(approvalId, request);
        }
    }

    static boolean requiresApproval(AssistantMessage.ToolCall call, List<ToolCallback> tools) {
        if (tools == null) return false;
        ToolCallback tool = tools.stream()
            .filter(candidate -> candidate.getToolDefinition().name().equals(call.name()))
            .findFirst().orElse(null);
        if (!(tool instanceof AuditedToolCallback audited)) return false;

        AiPermissionMode mode = AiPermissionContext.current();
        if (mode == AiPermissionMode.FULL_ACCESS) return false;
        if (mode == AiPermissionMode.ASK_FOR_APPROVAL) return audited.effect() != ToolEffect.READ;
        return switch (audited.effect()) {
            case READ, WRITE -> false;
            case EXTERNAL -> true;
            case COMMAND -> commandPotentiallyUnsafe(call.arguments());
        };
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
                                   AtomicReference<Boolean> decision,
                                   Instant expiresAt) {
    }

    public static final class ToolApprovalException extends RuntimeException {
        public ToolApprovalException(String message) {
            super(message);
        }
    }
}
