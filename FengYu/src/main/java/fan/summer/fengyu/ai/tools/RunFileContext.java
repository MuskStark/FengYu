package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;

import java.util.List;
import java.util.Map;

/**
 * Run-scoped file grants for workflow runs, set by the {@code AgentRunner} around every step
 * (mirroring {@link AiRunContext}). The run dialog resolves file-class workflow inputs into
 * per-plugin {@code FileRef} grants before the run starts; step args carry them as
 * {@code @file:<inputName>} placeholders that the plugin tool callback swaps for the current
 * plugin's FileRef right before dispatch — the host then resolves the opaque reference to a
 * real path for the worker. Chat turns run without run file grants and keep using
 * {@code ChatFileContext} transparent injection instead.
 */
public final class RunFileContext {
    private static final ThreadLocal<Map<String, List<ActiveFileRef>>> CURRENT = new InheritableThreadLocal<>();

    private RunFileContext() {}

    public static void set(Map<String, List<ActiveFileRef>> refs) { CURRENT.set(refs); }
    public static Map<String, List<ActiveFileRef>> current() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
