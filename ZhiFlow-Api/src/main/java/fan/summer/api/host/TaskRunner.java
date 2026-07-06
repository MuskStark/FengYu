package fan.summer.api.host;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Background task execution for a single plugin. Every task runs with the
 * plugin's ClassLoader as the thread-context ClassLoader — plugin authors need
 * no ClassLoader awareness. Tasks submitted here automatically keep the plugin
 * alive in the background (the host merges {@link #runningCount()} with
 * {@code ZhiFlowPlugin.hasRunningTasks()}).
 *
 * @since 3.2.0
 */
public interface TaskRunner {

    /**
     * Submits fire-and-forget work. Uncaught throwables are logged, never
     * silently swallowed.
     *
     * @param name a short task name for logging/diagnostics; may be null
     * @param work the work to run on a background thread; must not be null
     * @return a handle for cancellation and status queries
     */
    TaskHandle submit(String name, Runnable work);

    /**
     * Submits work with result callbacks. {@code onSuccess}/{@code onError} are
     * ALWAYS invoked on the JavaFX Application Thread. Either callback may be
     * null. Cancellation (interrupt) routes to {@code onError} with the
     * {@link InterruptedException}.
     *
     * @param name      a short task name; may be null
     * @param work      the work producing a result; must not be null
     * @param onSuccess invoked with the result on the FX thread; may be null
     * @param onError   invoked with the failure on the FX thread; may be null
     *                  (failures are then logged instead)
     * @param <T>       the result type
     * @return a handle for cancellation and status queries
     */
    <T> TaskHandle submit(String name, Callable<T> work,
                          Consumer<T> onSuccess, Consumer<Throwable> onError);

    /** @return the number of tasks currently running */
    int runningCount();

    /** Cancels (interrupts) all running tasks. Called by the host on plugin unload. */
    void cancelAll();
}
