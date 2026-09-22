package fan.summer.fengyu.ai.workspace;

import java.nio.file.Path;

/**
 * Per-turn workspace binding, propagated into the chat backend's virtual worker thread the
 * same way {@code AiPermissionContext} is: the controller binds before the stream worker is
 * spawned and clears afterwards, so the snapshot is visible for the whole tool-execution
 * window and never leaks into the next turn.
 *
 * <p>A binding exists only for conversations the user attached a workspace root to. It is the
 * single authority for the coding file tools: {@code AiToolRegistry} hides them from the tool
 * catalog while no binding is present, and every tool call re-validates that its target stays
 * inside the bound root.
 */
public final class WorkspaceContext {

    /** The canonical workspace root plus the conversation the root belongs to. */
    public record Binding(Path root, Long conversationId) {}

    private static final InheritableThreadLocal<Binding> CURRENT = new InheritableThreadLocal<>();

    private WorkspaceContext() {}

    public static void set(Binding binding) {
        CURRENT.set(binding);
    }

    public static Binding current() {
        return CURRENT.get();
    }

    /** True exactly while a chat turn executes with an attached workspace (see the registry filter). */
    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
