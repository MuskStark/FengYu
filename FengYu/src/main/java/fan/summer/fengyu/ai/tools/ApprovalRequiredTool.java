package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.FengYuTool;

/**
 * Marker for host tools whose execution is governed by the active permission profile.
 *
 * <p>Callbacks produced from these tools are guarded in both execution paths:
 * {@code AgentRunner} pauses an agent step, while ordinary chat uses
 * {@link ChatToolApprovalGate} and its confirmation card before invoking the callback.
 */
public interface ApprovalRequiredTool extends FengYuTool {
}
