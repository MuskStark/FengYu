package fan.summer.api.host;

/**
 * Handle to a background task submitted via {@link TaskRunner}.
 *
 * @since 3.2.0
 */
public interface TaskHandle {

    /** @return the task name given at submission (never null; "unnamed" if none) */
    String name();

    /** @return true while the task has not finished or been cancelled */
    boolean isRunning();

    /** Requests cancellation via thread interrupt. Idempotent. */
    void cancel();
}
