package fan.summer.fengyu.ai.tools;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Per-chat-request dynamic-tool-loading state: the activation set plus the deferred catalog the
 * {@code search_tools} loader searches. The backend binds it before the tool loop starts
 * (virtual-thread workers inherit the value, the same lifecycle {@code BoundToolsContext} uses)
 * and clears it afterwards.
 */
public final class ToolActivationContext {

    /** What the loader needs for one chat: mutable activation state + read-only deferred catalog. */
    public record Activation(ToolActivationState state, List<ToolCallback> deferred) {}

    private static final InheritableThreadLocal<Activation> CURRENT = new InheritableThreadLocal<>();

    private ToolActivationContext() {
    }

    public static void set(ToolActivationState state, List<ToolCallback> deferredTools) {
        CURRENT.set(new Activation(state, List.copyOf(deferredTools == null ? List.of() : deferredTools)));
    }

    public static Activation current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
