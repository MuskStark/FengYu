package fan.summer.fengyu.ai.tools;

/** Inheritable per-turn permission profile, propagated into the backend's virtual chat thread. */
public final class AiPermissionContext {
    private static final ThreadLocal<AiPermissionMode> CURRENT = new InheritableThreadLocal<>();
    private AiPermissionContext() {}
    public static void set(AiPermissionMode mode) { CURRENT.set(mode == null ? AiPermissionMode.ASK_FOR_APPROVAL : mode); }
    public static AiPermissionMode current() {
        AiPermissionMode mode = CURRENT.get();
        return mode == null ? AiPermissionMode.ASK_FOR_APPROVAL : mode;
    }
    /** True while an agent run executes a step (the runner owns per-step hook firing there). */
    public static boolean isBound() { return CURRENT.get() != null; }
    public static void clear() { CURRENT.remove(); }
}
