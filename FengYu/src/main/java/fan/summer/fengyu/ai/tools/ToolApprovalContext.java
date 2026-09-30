package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.AiStreamCallback;

/**
 * Tool-side bridge to the chat approval gate and stream callback — the seam a tool uses
 * to request a MID-EXECUTION approval (the sandbox escape flow: the fence denied the
 * command, and running it unfenced needs the user's explicit grant). The loop driver
 * installs the pair around the tool batch exactly like {@code WorkspaceContext} /
 * {@code ConversationContext}: inheritable across the batch executor's virtual threads,
 * cleared when the batch returns.
 */
public final class ToolApprovalContext {

    private static final InheritableThreadLocal<Bridge> CURRENT = new InheritableThreadLocal<>();

    private ToolApprovalContext() {}

    /** The gate + callback + rollout recorder a tool may use; null outside a batch. */
    record Bridge(ChatToolApprovalGate gate, AiStreamCallback callback,
            fan.summer.fengyu.ai.session.AiRolloutService.Recorder rollout) {}

    public static void set(ChatToolApprovalGate gate, AiStreamCallback callback) {
        set(gate, callback, null);
    }

    public static void set(ChatToolApprovalGate gate, AiStreamCallback callback,
            fan.summer.fengyu.ai.session.AiRolloutService.Recorder rollout) {
        CURRENT.set(gate == null || callback == null ? null : new Bridge(gate, callback, rollout));
    }

    public static ChatToolApprovalGate gate() {
        Bridge bridge = CURRENT.get();
        return bridge == null ? null : bridge.gate();
    }

    public static AiStreamCallback callback() {
        Bridge bridge = CURRENT.get();
        return bridge == null ? null : bridge.callback();
    }

    /** The turn's rollout recorder; null outside a tool batch (or when recording is off). */
    public static fan.summer.fengyu.ai.session.AiRolloutService.Recorder rollout() {
        Bridge bridge = CURRENT.get();
        return bridge == null ? null : bridge.rollout();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
