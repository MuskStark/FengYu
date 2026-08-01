package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;

import java.util.List;

/**
 * Per-request bridge that lets the singleton plugin {@code ToolCallback}s read the current chat
 * turn's active file grants. The callbacks are built once at startup
 * ({@code AiToolDiscoveryConfig.aiToolCallbacks}) and cannot hold request-scoped state, so
 * {@code AiController} sets this ThreadLocal before {@code backend.chat(...)} and clears it in a
 * {@code finally}. Spring AI executes tool calls synchronously within the chat call chain, so the
 * value is visible for the entire tool-execution window.
 */
public final class ChatFileContext {

    private static final ThreadLocal<List<ActiveFileRef>> CURRENT = new ThreadLocal<>();

    private ChatFileContext() {}

    /** Stash the active file refs for the current chat turn; {@code null} is treated as empty. */
    public static void set(List<ActiveFileRef> refs) {
        CURRENT.set(refs == null ? List.of() : refs);
    }

    /** @return the active file refs for the current chat turn, never {@code null}. */
    public static List<ActiveFileRef> current() {
        List<ActiveFileRef> v = CURRENT.get();
        return v == null ? List.of() : v;
    }

    /** Remove the current thread's binding. Always call in a {@code finally}. */
    public static void clear() {
        CURRENT.remove();
    }

    /** A file grant active for one chat turn, scoped to the plugin whose tool may consume it. */
    public record ActiveFileRef(String pluginId, FileRef ref) {}
}
