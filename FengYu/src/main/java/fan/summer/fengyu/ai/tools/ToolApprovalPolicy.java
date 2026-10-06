package fan.summer.fengyu.ai.tools;

import org.springframework.ai.tool.ToolCallback;

/** Single approval decision shared by ordinary chat and Plan-and-Execute. */
public final class ToolApprovalPolicy {

    private ToolApprovalPolicy() {
    }

    public static boolean requiresApproval(ToolCallback tool, AiPermissionMode mode,
                                           String arguments) {
        if (!(tool instanceof AuditedToolCallback audited)) return false;
        AiPermissionMode effectiveMode = mode == null ? AiPermissionMode.ASK_FOR_APPROVAL : mode;
        String toolName = tool.getToolDefinition() == null ? null
                : tool.getToolDefinition().name();
        // A workspace_exec invocation on the readonly whitelist is a jailed, side-effect-free
        // inspection (ls/git status/…) — it auto-runs in every mode, exactly like read_file.
        if ("workspace_exec".equals(toolName)
                && WorkspaceExecTool.isReadonlyInvocation(arguments)) {
            return false;
        }
        // FULL_ACCESS runs everything without prompting — the codex `AskForApproval::Never`
        // contract: auto-approve or reject-with-reason, never hand the decision back to the
        // user. The guard's catastrophic-command hard floor and any configured hook veto /
        // deny rules still fire BEFORE this point, so destruction stays denied; asking here
        // anyway (the old CQ-01 blanket) made "full control" modes prompt on every heredoc,
        // $(…), or quoted command the parser could not structurally verify.
        if (effectiveMode == AiPermissionMode.FULL_ACCESS) return false;
        // Command text the rule parser cannot see through (newlines, command substitution,
        // variable expansion, subshells, unbalanced quotes) always needs a human decision —
        // no allow rule, session grant, or remaining mode may auto-run it (CQ-01).
        if (audited.effect() == ToolEffect.COMMAND
                && ToolPermissionRules.isUnverifiableCommand(
                        ToolPermissionRules.commandFromArguments(arguments))) {
            return true;
        }
        if (effectiveMode == AiPermissionMode.PLAN) {
            // Plan mode keeps READ tools free; everything else still asks, so approving the
            // card remains the user's explicit escape hatch out of read-only investigation.
            return audited.effect() != ToolEffect.READ;
        }
        if (effectiveMode == AiPermissionMode.ASK_FOR_APPROVAL) {
            return audited.effect() != ToolEffect.READ;
        }
        return switch (audited.effect()) {
            case READ, WRITE -> false;
            case EXTERNAL -> true;
            case COMMAND -> ChatToolApprovalGate.commandPotentiallyUnsafe(arguments);
        };
    }
}
