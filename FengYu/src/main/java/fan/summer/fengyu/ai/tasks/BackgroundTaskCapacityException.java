package fan.summer.fengyu.ai.tasks;

/**
 * Transient load-shedding signal raised when the bounded background-task queue is full.
 * HTTP and model-tool callers can distinguish this from an internal runtime failure and retry
 * after the suggested delay.
 */
public class BackgroundTaskCapacityException extends IllegalStateException {

    public enum Scope { GLOBAL, OWNER, GLOBAL_PRIORITY, OWNER_PRIORITY }

    private final int retryAfterSeconds;
    private final Scope scope;
    private final String capacityPriority;

    public BackgroundTaskCapacityException(int runningLimit, int queueLimit,
                                           int retryAfterSeconds) {
        super("Background task queue is full (" + runningLimit + " running + "
                + queueLimit + " queued); retry later");
        this.retryAfterSeconds = retryAfterSeconds;
        this.scope = Scope.GLOBAL;
        this.capacityPriority = null;
    }

    public BackgroundTaskCapacityException(int ownerQueueLimit, int retryAfterSeconds) {
        super("Background task owner queue is full (" + ownerQueueLimit
                + " queued for this owner); retry later");
        this.retryAfterSeconds = retryAfterSeconds;
        this.scope = Scope.OWNER;
        this.capacityPriority = null;
    }

    public BackgroundTaskCapacityException(String priority, int priorityQueueLimit,
                                           int retryAfterSeconds) {
        this(Scope.OWNER_PRIORITY, priority, priorityQueueLimit, retryAfterSeconds);
    }

    public static BackgroundTaskCapacityException globalPriority(
            String priority, int priorityQueueLimit, int retryAfterSeconds) {
        return new BackgroundTaskCapacityException(
                Scope.GLOBAL_PRIORITY, priority, priorityQueueLimit, retryAfterSeconds);
    }

    private BackgroundTaskCapacityException(Scope scope, String priority,
                                            int priorityQueueLimit, int retryAfterSeconds) {
        super("Background task " + priority + " admission reserve is exhausted (limit "
                + priorityQueueLimit + " at "
                + (scope == Scope.GLOBAL_PRIORITY ? "global" : "owner")
                + " scope); retry later");
        this.retryAfterSeconds = retryAfterSeconds;
        this.scope = scope;
        this.capacityPriority = priority;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String capacityScope() {
        return scope.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public String capacityPriority() {
        return capacityPriority;
    }
}
