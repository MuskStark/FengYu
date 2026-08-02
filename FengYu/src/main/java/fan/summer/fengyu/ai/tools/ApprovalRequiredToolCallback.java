package fan.summer.fengyu.ai.tools;

/**
 * A discovered command callback whose invocation is governed by the active permission profile.
 */
public interface ApprovalRequiredToolCallback extends AuditedToolCallback {
    @Override default ToolEffect effect() { return ToolEffect.COMMAND; }
}
