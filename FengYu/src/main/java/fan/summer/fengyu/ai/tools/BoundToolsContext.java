package fan.summer.fengyu.ai.tools;

import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-turn extra tool callbacks bound to ONE chat request (Flowise's "chat with this flow"):
 * the chat pipeline composes them with the global {@code AiToolRegistry} snapshot inside the
 * same tool-call loop, so bound tools go through the identical approval gate, permission
 * mode, and SSE tool events as every other tool call.
 *
 * Lifecycle mirrors {@link AiPermissionContext}: the controller binds before spawning the
 * stream worker (virtual threads inherit the value at creation) and clears afterwards —
 * the worker keeps its own snapshot, so the clear never races an in-flight generation.
 */
public final class BoundToolsContext {

    private static final InheritableThreadLocal<List<ToolCallback>> BOUND = new InheritableThreadLocal<>();

    private BoundToolsContext() {
    }

    public static void set(List<ToolCallback> callbacks) {
        BOUND.set(callbacks == null ? List.of() : List.copyOf(callbacks));
    }

    public static List<ToolCallback> current() {
        List<ToolCallback> callbacks = BOUND.get();
        return callbacks == null ? List.of() : callbacks;
    }

    public static void clear() {
        BOUND.remove();
    }

    /**
     * The registry snapshot with this turn's bound tools prepended. Bound tools are listed
     * FIRST so the model sees the conversation-bound flow (e.g. {@code run_current_flow})
     * as the most relevant tool for the turn; a same-named registry duplicate would make
     * Spring AI's tool resolution ambiguous, so bound names win.
     */
    public static List<ToolCallback> mergeWith(List<ToolCallback> registry) {
        List<ToolCallback> bound = current();
        if (bound.isEmpty()) return registry;
        List<String> boundNames = bound.stream()
                .map(callback -> callback.getToolDefinition().name()).toList();
        List<ToolCallback> merged = new ArrayList<>(bound.size() + registry.size());
        merged.addAll(bound);
        for (ToolCallback callback : registry) {
            if (!boundNames.contains(callback.getToolDefinition().name())) merged.add(callback);
        }
        return List.copyOf(merged);
    }
}
