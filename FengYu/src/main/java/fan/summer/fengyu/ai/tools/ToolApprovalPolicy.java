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
        if (effectiveMode == AiPermissionMode.FULL_ACCESS) return false;
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
