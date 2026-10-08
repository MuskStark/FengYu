package fan.summer.fengyu.ai.tools;

/**
 * The cancel scope of the CURRENT chat turn: the group key {@link ChatToolApprovalGate}
 * stamps on every approval/question it registers, so cancelling one turn releases
 * exactly that turn's pending entries while other conversations' turns keep waiting
 * (4.1.0 multi-conversation concurrency). The group is {@code "conversation-<id>"} for
 * conversation-bound turns and a unique {@code "turn-<uuid>"} for unbound (flow-panel)
 * turns — two unbound turns must never cancel each other either.
 *
 * <p><b>InheritableThreadLocal, not plain ThreadLocal:</b> the {@code ToolLoopDriver}
 * worker sets it once, and every thread the turn spawns (the batch executor's tool
 * virtual threads, code-mode cell threads) inherits the same group, so gate entries
 * registered from inside tools land in the owning turn's scope. Null (registered
 * outside any driver turn — direct tool invocations in tests) keeps the legacy
 * global-cancel behaviour.</p>
 */
public final class TurnScope {

    private static final InheritableThreadLocal<String> CURRENT = new InheritableThreadLocal<>();

    private TurnScope() {}

    public static void set(String scope) { CURRENT.set(scope); }

    /** The calling turn's cancel group; null outside a driver-driven turn. */
    public static String current() { return CURRENT.get(); }

    public static void clear() { CURRENT.remove(); }
}
