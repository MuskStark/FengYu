package fan.summer.zhiflow.api.host;

import fan.summer.zhiflow.api.PluginContext;
import fan.summer.zhiflow.api.ZhiFlowPlugin;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import javafx.application.Platform;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Default {@link TaskRunner} shared by the host application and the plugin
 * preview window. Each task runs on its own virtual thread with the plugin's
 * ClassLoader as TCCL (via {@link PluginContext#callWith}); callbacks are
 * dispatched through the configured executor — the JavaFX Application Thread
 * by default.
 *
 * @since 3.2.0
 */
public class SimpleTaskRunner implements TaskRunner {

    private static final PluginLogger log = LoggerFactory.getLogger(SimpleTaskRunner.class);

    private final ZhiFlowPlugin plugin;
    private final Executor callbackExecutor;
    private final AtomicInteger running = new AtomicInteger();
    private final Set<Handle> live = ConcurrentHashMap.newKeySet();

    /**
     * @param plugin the owning plugin (used for TCCL and thread naming)
     */
    public SimpleTaskRunner(ZhiFlowPlugin plugin) {
        this(plugin, Platform::runLater);
    }

    /**
     * Test seam: inject a synchronous executor so tests need no FX toolkit.
     *
     * @param plugin           the owning plugin
     * @param callbackExecutor executor for onSuccess/onError dispatch
     */
    public SimpleTaskRunner(ZhiFlowPlugin plugin, Executor callbackExecutor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    }

    @Override
    public TaskHandle submit(String name, Runnable work) {
        Objects.requireNonNull(work, "work");
        return submit(name, () -> { work.run(); return null; }, null, null);
    }

    @Override
    public <T> TaskHandle submit(String name, Callable<T> work,
                                 Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Objects.requireNonNull(work, "work");
        Handle handle = new Handle(name == null || name.isBlank() ? "unnamed" : name);
        running.incrementAndGet();
        live.add(handle);
        Thread thread = Thread.ofVirtual()
            .name("plugin-task-" + plugin.getId() + "-" + handle.name())
            .unstarted(() -> {
                T result = null;
                Throwable failure = null;
                try {
                    result = PluginContext.callWith(plugin, work);
                } catch (Throwable ex) {
                    failure = ex;
                }
                // Settle bookkeeping BEFORE dispatching callbacks: by the time a
                // callback observes completion, runningCount()/isRunning() are final.
                handle.done = true;
                live.remove(handle);
                running.decrementAndGet();
                if (failure == null) {
                    if (onSuccess != null) {
                        T r = result;
                        callbackExecutor.execute(() -> onSuccess.accept(r));
                    }
                } else if (onError != null) {
                    Throwable f = failure;
                    callbackExecutor.execute(() -> onError.accept(f));
                } else {
                    log.error("Task '{}' of plugin {} failed: {}",
                        handle.name(), plugin.getId(), failure.getMessage(), failure);
                }
            });
        handle.thread = thread;
        thread.start();
        return handle;
    }

    @Override
    public int runningCount() {
        return running.get();
    }

    @Override
    public void cancelAll() {
        for (Handle h : live) h.cancel();
    }

    private static final class Handle implements TaskHandle {
        private final String name;
        private volatile Thread thread;
        private volatile boolean done;

        private Handle(String name) { this.name = name; }

        @Override public String name() { return name; }
        @Override public boolean isRunning() { return !done; }

        @Override public void cancel() {
            Thread t = thread;
            if (t != null && !done) t.interrupt();
        }
    }
}
