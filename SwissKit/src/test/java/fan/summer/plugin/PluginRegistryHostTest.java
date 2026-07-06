package fan.summer.plugin;

import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.ToolType;
import fan.summer.zhiflow.api.host.*;
import fan.summer.zhiflow.api.log.PluginLogger;
import javafx.scene.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryHostTest {

    private PluginRegistry registry;

    /** Fake TaskRunner:计数可设,记录 cancelAll 调用。 */
    static final class FakeTasks implements TaskRunner {
        int running;
        boolean cancelled;
        public TaskHandle submit(String name, Runnable work) { throw new UnsupportedOperationException(); }
        public <T> TaskHandle submit(String name, Callable<T> work, Consumer<T> ok, Consumer<Throwable> err) { throw new UnsupportedOperationException(); }
        public int runningCount() { return running; }
        public void cancelAll() { cancelled = true; }
    }

    /** Fake PluginHost:只带 FakeTasks,其余抛异常(测试不该触碰)。 */
    static final class FakeHost implements PluginHost {
        final String id;
        final FakeTasks tasks = new FakeTasks();
        FakeHost(String id) { this.id = id; }
        public String pluginId() { return id; }
        public PluginLogger logger(Class<?> cls) { throw new UnsupportedOperationException(); }
        public PluginSettings settings() { throw new UnsupportedOperationException(); }
        public TaskRunner tasks() { return tasks; }
        public I18nFacade i18n() { throw new UnsupportedOperationException(); }
        public ThemeFacade theme() { throw new UnsupportedOperationException(); }
        public NotificationFacade notifications() { throw new UnsupportedOperationException(); }
    }

    static SwissKitJPlugin plugin(String id, Consumer<PluginHost> onInit) {
        return new SwissKitJPlugin() {
            public String getId() { return id; }
            public String getName() { return id; }
            public String getDescription() { return ""; }
            public ToolCategory getCategory() { return ToolCategory.OTHER; }
            public String getVersion() { return "0"; }
            public String getMdiIcon() { return "circle"; }
            public Node createView() { return null; }
            public ToolType getType() { return ToolType.PLUGIN; }
            public void init(PluginHost host) { if (onInit != null) onInit.accept(host); }
        };
    }

    @BeforeEach
    void setup() {
        registry = new PluginRegistry(new PluginLoader(null));
        PluginRegistry.setInstanceForTest(registry);
    }

    @AfterEach
    void teardown() {
        PluginRegistry.setInstanceForTest(null);
    }

    @Test
    void initCalledExactlyOnceWithBoundHost() {
        AtomicInteger calls = new AtomicInteger();
        var seenHost = new java.util.concurrent.atomic.AtomicReference<PluginHost>();
        SwissKitJPlugin p = plugin("p1", h -> { calls.incrementAndGet(); seenHost.set(h); });
        registry.setHostFactoryForTest(pl -> new FakeHost(pl.getId()));

        registry.addPlugins(List.of(p));

        assertEquals(1, calls.get());
        assertEquals("p1", seenHost.get().pluginId());
    }

    @Test
    void initThrowingDoesNotBlockLoading() {
        SwissKitJPlugin bad = plugin("bad", h -> { throw new IllegalStateException("boom"); });
        registry.setHostFactoryForTest(pl -> new FakeHost(pl.getId()));

        registry.addPlugins(List.of(bad));

        assertTrue(registry.findPlugin("bad").isPresent());
    }

    @Test
    void isBusyMergesTaskRunnerCount() {
        SwissKitJPlugin p = plugin("p1", null);
        FakeHost host = new FakeHost("p1");
        registry.setHostFactoryForTest(pl -> host);
        registry.addPlugins(List.of(p));

        assertFalse(registry.isBusy(p));          // 无任务、hasRunningTasks 默认 false
        host.tasks.running = 2;
        assertTrue(registry.isBusy(p));           // TaskRunner 计数被合并
        host.tasks.running = 0;
        assertFalse(registry.isBusy(p));
    }

    @Test
    void removePluginCancelsRemainingTasks() {
        SwissKitJPlugin p = plugin("p1", null);
        FakeHost host = new FakeHost("p1");
        registry.setHostFactoryForTest(pl -> host);
        registry.addPlugins(List.of(p));

        registry.removePlugin(p);

        assertTrue(host.tasks.cancelled);
        assertFalse(registry.isBusy(p));          // host 映射已清理
    }
}
