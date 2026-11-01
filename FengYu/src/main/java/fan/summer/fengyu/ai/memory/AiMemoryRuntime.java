package fan.summer.fengyu.ai.memory;

/**
 * Static bridge between the turn loop and the (Spring-managed) memory service — the
 * same holder pattern the MCP Apps runtime uses. The loop cannot take a bean reference
 * (drivers are constructed per turn inside transports), and memory must never be a hard
 * dependency: with the service absent (unit tests, unwired contexts) both calls are
 * no-ops, and with memory disabled the service itself declines.
 */
public final class AiMemoryRuntime {

    private static volatile AiMemoryService service;

    private AiMemoryRuntime() { }

    /** Called by the service bean's constructor; tests may attach their own instance. */
    static void attach(AiMemoryService attached) {
        service = attached;
    }

    /** The prompt-injection section; empty string when memory is off or empty. */
    public static String promptSection() {
        AiMemoryService current = service;
        try {
            return current == null ? "" : current.indexForPrompt();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Fire-and-forget background extraction of one finished exchange. */
    public static void extractAsync(String transcript) {
        AiMemoryService current = service;
        try {
            if (current != null) current.extractAsync(transcript);
        } catch (RuntimeException ignored) {
            // extraction must never disturb a completed turn's terminal path
        }
    }
}
