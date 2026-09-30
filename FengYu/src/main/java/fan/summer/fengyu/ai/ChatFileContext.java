package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;

import java.util.List;

/**
 * Per-request bridge that lets the singleton plugin {@code ToolCallback}s read the current chat
 * turn's active file grants. The callbacks are built once at startup
 * ({@code AiToolDiscoveryConfig.aiToolCallbacks}) and cannot hold request-scoped state, so
 * {@code AiController} sets this ThreadLocal before {@code backend.chat(...)} and clears it in a
 * {@code finally}.
 *
 * <p><b>InheritableThreadLocal, not plain ThreadLocal:</b> the chat backends run the model + tool
 * loop on a separate virtual thread spawned inside {@code startChat} (which returns immediately),
 * while the {@code finally} clears on the request thread. A plain {@code ThreadLocal} is NOT
 * inherited by that child thread, so route B (the {@code ToolCallback.call()} injection) would
 * silently read an empty list. {@link InheritableThreadLocal} gives the child a copy taken at the
 * moment {@code Thread.ofVirtual().start(...)} runs — which is after {@code set(...)} and before
 * the parent's {@code clear()}, so the value is visible for the whole tool-execution window and
 * the parent's {@code clear()} cannot disturb the child's copy.
 */
public final class ChatFileContext {

    private static final ThreadLocal<List<ActiveFileRef>> CURRENT = new InheritableThreadLocal<>();

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
