package fan.summer.fengyu.ai.tools;

/**
 * Per-run identity for tool calls, set by the {@code AgentRunner} around every step (and
 * inherited by any thread the step spawns). The host injects it into plugin tool calls as
 * a {@code sessionId} argument so stateful plugins (Excel's analyze→configure→execute)
 * can scope their session PER RUN — concurrent agent runs and background workflows then
 * keep independent state instead of sharing one global session (P1-3). Chat calls run
 * without a run id and share the plugin's default session.
 */
public final class AiRunContext {
    private static final ThreadLocal<String> CURRENT = new InheritableThreadLocal<>();

    private AiRunContext() {}

    public static void set(String runId) { CURRENT.set(runId); }
    public static String current() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
