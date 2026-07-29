package fan.summer.fengyu.ai.tools;

import org.springframework.ai.tool.ToolCallback;

/**
 * A discovered tool callback whose invocation must be guarded by explicit user approval.
 */
public interface ApprovalRequiredToolCallback extends ToolCallback {
}
