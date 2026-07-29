package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.api.ai.FengYuTool;

/**
 * Marker for host tools that must never run without an explicit user approval gate.
 *
 * <p>Callbacks produced from these tools are guarded in both execution paths:
 * {@code AgentRunner} pauses an agent step, while ordinary chat uses
 * {@link ChatToolApprovalGate} and its confirmation card before invoking the callback.
 */
public interface ApprovalRequiredTool extends FengYuTool {
}
