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
    /** Registry tool names withheld from THIS turn's surface (e.g. ask_user in flow panels). */
    private static final InheritableThreadLocal<List<String>> HIDDEN = new InheritableThreadLocal<>();

    private BoundToolsContext() {
    }

    public static void set(List<ToolCallback> callbacks) {
        BOUND.set(callbacks == null ? List.of() : List.copyOf(callbacks));
    }

    /** Registry tools to hide from this turn's merged surface; bound tools are never hidden. */
    public static void setHidden(List<String> toolNames) {
        HIDDEN.set(toolNames == null ? List.of() : List.copyOf(toolNames));
    }

    public static List<ToolCallback> current() {
        List<ToolCallback> callbacks = BOUND.get();
        return callbacks == null ? List.of() : callbacks;
    }

    public static void clear() {
        BOUND.remove();
        HIDDEN.remove();
    }

    /**
     * The registry snapshot with this turn's bound tools prepended and its hidden tools
     * removed. Bound tools are listed FIRST so the model sees the conversation-bound flow
     * (e.g. {@code run_current_flow}) as the most relevant tool for the turn; a same-named
     * registry duplicate would make Spring AI's tool resolution ambiguous, so bound names
     * win. Hidden names apply to the registry side only — they exist for surfaces that
     * cannot host an interaction (the flow chat panel renders no question cards, so
     * {@code ask_user} would block the turn on its timeout with nobody able to answer).
     */
    public static List<ToolCallback> mergeWith(List<ToolCallback> registry) {
        List<ToolCallback> bound = current();
        List<String> hidden = HIDDEN.get();
        if (hidden != null && !hidden.isEmpty() && registry != null && !registry.isEmpty()) {
            registry = registry.stream()
                    .filter(callback -> !hidden.contains(callback.getToolDefinition().name()))
                    .toList();
        }
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
