package fan.summer.fengyu.ai.tools;

/**
 * Stable identity for one logical agent step invocation. The same id is reused when an
 * interrupted run resumes, so idempotent tools and plugin workers can deduplicate effects via
 * their JSON-RPC call id instead of guessing whether a retry is new work.
 */
public final class ToolInvocationContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ToolInvocationContext() {}

    public static void set(String invocationId) { CURRENT.set(invocationId); }
    public static String current() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}

