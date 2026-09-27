package fan.summer.fengyu.ai.tools;

/**
 * Per-turn conversation identity, propagated into the chat backend's virtual worker thread
 * the same way {@code AiPermissionContext} is. The controller binds the conversation the
 * request names before the stream worker is spawned and clears afterwards, so
 * conversation-scoped services (todo list, session permission grants, workspace
 * checkpoints) can key their state without threading an id through every tool signature.
 *
 * <p>Null for legacy flow turns that carry no {@code conversationId}; conversation-scoped
 * tools treat null as "no state available" and degrade gracefully.</p>
 */
public final class ConversationContext {

    private static final InheritableThreadLocal<Long> CURRENT = new InheritableThreadLocal<>();

    private ConversationContext() {}

    public static void set(Long conversationId) {
        CURRENT.set(conversationId);
    }

    public static Long current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
