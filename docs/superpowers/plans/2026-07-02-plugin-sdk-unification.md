# Plugin SDK 统一层 — 实施计划(v3.2.0)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现规格 `docs/superpowers/specs/2026-07-02-plugin-sdk-unification-design.md`:PluginHost 全量门面 + PluginSettings + TaskRunner + preview 包 classloader 统一,全部落在 v3.2.0。

**Architecture:** API 模块新增 `fan.summer.zhiflow.api.host` 纯接口包与两个可复用实现(`SimpleTaskRunner`/`BasePluginHost`);宿主与预览各补一个 settings 实现并在插件加载单一漏斗(`PluginRegistry.addPlugins` / `PluginPreviewWindow.launch`)注入 `init(PluginHost)`;`ChildFirstResourceClassLoader` 原样下沉到 API 模块供两侧共用。

**Tech Stack:** Java 21(虚拟线程)/ JavaFX / MyBatis + H2 / JUnit 5。

## Global Constraints

- 工作分支:`v3.2.0`。**前置条件:修复计划 `2026-07-02-v3.2-consistency-fixes.md` 的 Task 8(SkNotification)与 Task 10(migration-3.2.md)已执行完毕**——本计划 Task 1 引用 `SkNotification`,Task 10 增补 migration 文档。若未执行,先完成那两个任务。
- **禁止在普通 shell 运行 `mvn`**。编译用 `mcp__idea__build_project`;`install`/`package` 经 IDEA 内置 Maven(见 CLAUDE.md「Build & Run」)。修改 `SwissKitJ-Api` 后、编译 `SwissKit` 前,必须先经 IDEA Maven 执行 `install -f SwissKitJ-Api/pom.xml -DskipTests`。
- 运行测试:在 IDEA 中运行测试类(`mcp__idea__execute_run_configuration` 或手动;另见 memory `maven-test-recipe`)。无法运行时至少保证编译通过并在 Task 11 冒烟覆盖。
- 每个任务只 `git add` 本任务「Files」列出的文件,**严禁 `git add -A`**(工作区可能有其他在途改动)。
- 提交信息:emoji + conventional commits。
- 所有新公共类型/方法标 `@since 3.2.0`。API 模块内**不得**引入 SLF4J/DB 依赖,日志一律用 `fan.summer.zhiflow.api.log.LoggerFactory`。
- 时序契约(规格 §3.1 为准):`init(PluginHost)` 每插件恰好一次、FX 线程、TCCL 已设、在插件加入可见列表和 `aiTools()` 注册**之前**。

---

### Task 1: API host 接口层 + SwissKitJPlugin.init()

**Files:**
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/host/PluginHost.java`
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/host/PluginSettings.java`
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/host/TaskRunner.java`
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/host/TaskHandle.java`
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/host/I18nFacade.java`
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/host/ThemeFacade.java`
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/host/NotificationFacade.java`
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`(`aiTools()` 之前插入 `init`)

**Interfaces:**
- Produces(后续所有任务依赖的权威签名): `PluginHost { pluginId(); logger(Class); settings(); tasks(); i18n(); theme(); notifications(); }`、`PluginSettings { get(String):Optional<String>; get(String,String):String; put(String,String); remove(String); }`、`TaskRunner { submit(String,Runnable):TaskHandle; submit(String,Callable,Consumer,Consumer):TaskHandle; runningCount():int; cancelAll(); }`、`TaskHandle { name(); isRunning(); cancel(); }`、`SwissKitJPlugin.init(PluginHost)`。
- Consumes: `PluginLogger`(已有)、`ThemeService.Theme`(已有)、`SkNotification.Type`(修复计划 Task 8 产物)。

- [ ] **Step 1: 创建 7 个接口文件**

`PluginHost.java`:
```java
package fan.summer.zhiflow.api.host;

import fan.summer.zhiflow.api.log.PluginLogger;

/**
 * Per-plugin facade giving a plugin access to every host capability through a
 * single injected object — logging, namespaced settings, TCCL-safe background
 * tasks, i18n, theming, and notifications.
 *
 * <p>Injected via {@link fan.summer.zhiflow.api.SwissKitJPlugin#init(PluginHost)} exactly
 * once, on the JavaFX Application Thread, before the plugin becomes visible in the
 * registry. Store the reference; it stays valid for the plugin's whole lifetime.</p>
 *
 * @since 3.2.0
 */
public interface PluginHost {

    /** @return the owning plugin's ID (same value as {@code SwissKitJPlugin.getId()}) */
    String pluginId();

    /**
     * @param cls the class requesting the logger
     * @return a logger routed into the host logging backbone
     */
    PluginLogger logger(Class<?> cls);

    /** @return key-value settings persisted by the host, namespaced by {@link #pluginId()} */
    PluginSettings settings();

    /**
     * @return TCCL-safe background task runner; its running count feeds the host's
     *         background-keepalive decision alongside {@code hasRunningTasks()}
     */
    TaskRunner tasks();

    /** @return i18n facade bound to this plugin's ClassLoader */
    I18nFacade i18n();

    /** @return theme facade (current theme, change listener, stylesheet application) */
    ThemeFacade theme();

    /**
     * Named {@code notifications()} rather than {@code notify()} — a zero-arg
     * {@code notify()} would clash with the final {@link Object#notify()}.
     *
     * @return notification facade delegating to {@code SkNotification}
     */
    NotificationFacade notifications();
}
```

`PluginSettings.java`:
```java
package fan.summer.zhiflow.api.host;

import java.util.Optional;

/**
 * Key-value settings store for a single plugin, namespaced by plugin ID.
 * Reads are cache-first (read-your-writes guaranteed). Keys must be non-null;
 * a null key throws {@link NullPointerException}.
 *
 * @since 3.2.0
 */
public interface PluginSettings {

    /**
     * @param key the setting key; must not be null
     * @return the stored value, or empty if absent
     */
    Optional<String> get(String key);

    /**
     * @param key          the setting key; must not be null
     * @param defaultValue returned when the key is absent
     * @return the stored value, or {@code defaultValue}
     */
    String get(String key, String defaultValue);

    /**
     * Stores a value. {@code value == null} is equivalent to {@link #remove(String)}.
     *
     * @param key   the setting key; must not be null
     * @param value the value to store, or null to remove
     */
    void put(String key, String value);

    /**
     * Removes the key; no-op if absent.
     *
     * @param key the setting key; must not be null
     */
    void remove(String key);
}
```

`TaskRunner.java`:
```java
package fan.summer.zhiflow.api.host;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Background task execution for a single plugin. Every task runs with the
 * plugin's ClassLoader as the thread-context ClassLoader — plugin authors need
 * no ClassLoader awareness. Tasks submitted here automatically keep the plugin
 * alive in the background (the host merges {@link #runningCount()} with
 * {@code SwissKitJPlugin.hasRunningTasks()}).
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
```

`TaskHandle.java`:
```java
package fan.summer.zhiflow.api.host;

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
```

`I18nFacade.java`:
```java
package fan.summer.zhiflow.api.host;

import javafx.beans.property.StringProperty;

/**
 * i18n access bound to one plugin. The key improvement over the static
 * {@code I18n} entry points: {@link #registerBundle(String)} resolves the
 * plugin's own ClassLoader automatically.
 *
 * @since 3.2.0
 */
public interface I18nFacade {

    /**
     * @param key  the message key
     * @param args optional MessageFormat arguments
     * @return the localized message, or the key itself if unresolved
     */
    String get(String key, Object... args);

    /**
     * Binds a StringProperty to a message key; updates live on locale change.
     *
     * @param property the property to bind
     * @param key      the message key
     */
    void bind(StringProperty property, String key);

    /**
     * Registers the plugin's message bundle using the PLUGIN'S OWN ClassLoader,
     * resolved automatically — no ClassLoader parameter, no way to get it wrong.
     * Call once, typically at the top of {@code createView()}.
     *
     * @param baseName the bundle base name, e.g. {@code "i18n.messages"}
     */
    void registerBundle(String baseName);

    /**
     * @param onLocaleChanged invoked (on the FX thread) whenever the locale changes
     */
    void addListener(Runnable onLocaleChanged);
}
```

`ThemeFacade.java`:
```java
package fan.summer.zhiflow.api.host;

import fan.summer.zhiflow.api.theme.ThemeService;
import javafx.scene.Scene;

import java.util.function.Consumer;

/**
 * Theme access for plugins: query the active theme, react to switches, and
 * theme plugin-owned Stages.
 *
 * @since 3.2.0
 */
public interface ThemeFacade {

    /** @return the currently active theme */
    ThemeService.Theme current();

    /** @param listener invoked whenever the theme changes */
    void onChange(Consumer<ThemeService.Theme> listener);

    /**
     * For plugin-owned Stages: loads the common stylesheet and stamps the active
     * theme class on the scene root so {@code -sk-*} tokens resolve.
     *
     * @param scene the scene of a plugin-created Stage
     */
    void applyTo(Scene scene);
}
```

`NotificationFacade.java`:
```java
package fan.summer.zhiflow.api.host;

import fan.summer.zhiflow.api.component.SkNotification;
import javafx.scene.Node;

/**
 * Notification access for plugins, delegating to {@link SkNotification}.
 *
 * @since 3.2.0
 */
public interface NotificationFacade {

    /**
     * Non-modal toast that auto-dismisses after ~2.5 s.
     *
     * @param context a node used to locate the owner window; may be null
     * @param type    the visual style
     * @param message the message
     */
    void toast(Node context, SkNotification.Type type, String message);

    /**
     * Modal notification with an OK button.
     *
     * @param context a node used to locate the owner window; may be null
     * @param type    the visual style
     * @param message the message
     */
    void notify(Node context, SkNotification.Type type, String message);

    /**
     * Modal OK/Cancel confirmation. Safe to call from any thread.
     *
     * @param context a node used to locate the owner window; may be null
     * @param title   the confirmation title
     * @param message the body message
     * @return true if the user clicked OK
     */
    boolean confirm(Node context, String title, String message);
}
```

- [ ] **Step 2: SwissKitJPlugin 加 init()**

`SwissKitJPlugin.java` 在 `// ── AI tools ─────` 注释行之前插入:
```java
    // ── Host facade injection ─────────────────────────────

    /**
     * Called exactly once by the host, on the JavaFX Application Thread, after
     * the plugin is instantiated and before it becomes visible in the registry
     * (and before {@link #aiTools()} registration). The plugin's ClassLoader is
     * already on the thread-context ClassLoader. Store the reference — it stays
     * valid for the plugin's whole lifetime.
     *
     * <p>The default implementation is a no-op; existing plugins need no change.</p>
     *
     * @param host the host facade bound to this plugin instance
     * @since 3.2.0
     */
    default void init(fan.summer.zhiflow.api.host.PluginHost host) {}
```

- [ ] **Step 3: 编译验证**

经 IDEA Maven 执行:`install -f SwissKitJ-Api/pom.xml -DskipTests`
Expected: BUILD SUCCESS。(若 `SkNotification` 不存在报编译错,说明前置的修复计划 Task 8 未执行——先去执行它。)

- [ ] **Step 4: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/host SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java
git commit -m "✨ feat(api): PluginHost facade interfaces + SwissKitJPlugin.init() injection point"
```

---

### Task 2: SimpleTaskRunner(TDD)

**Files:**
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/host/SimpleTaskRunner.java`
- Test: `SwissKitJ-Api/src/test/java/fan/summer/api/host/SimpleTaskRunnerTest.java`

**Interfaces:**
- Consumes: Task 1 的 `TaskRunner`/`TaskHandle`;`PluginContext.callWith(plugin, callable)`(已有);`LoggerFactory.getLogger(Class)`(已有)。
- Produces: `public class SimpleTaskRunner implements TaskRunner`,构造器 `SimpleTaskRunner(SwissKitJPlugin)`(回调走 `Platform::runLater`)与 `SimpleTaskRunner(SwissKitJPlugin, Executor)`(测试注入同步 executor)。Task 3/8 依赖前者。

- [ ] **Step 1: 写失败测试**

`SimpleTaskRunnerTest.java`:
```java
package fan.summer.zhiflow.api.host;

import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SimpleTaskRunnerTest {

    /** 同步 executor:回调直接在提交线程执行,测试无需 FX Toolkit。 */
    private static final java.util.concurrent.Executor DIRECT = Runnable::run;

    private static SwissKitJPlugin stubPlugin() {
        return new SwissKitJPlugin() {
            public String getId() { return "test.plugin"; }
            public String getName() { return "Test"; }
            public String getDescription() { return ""; }
            public ToolCategory getCategory() { return ToolCategory.OTHER; }
            public String getVersion() { return "0"; }
            public String getMdiIcon() { return "star"; }
            public Node createView() { return null; }
        };
    }

    @Test
    void countsRunningTasksAndDecrementsWhenDone() throws Exception {
        SimpleTaskRunner runner = new SimpleTaskRunner(stubPlugin(), DIRECT);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        runner.submit("t", () -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            return null;
        }, r -> done.countDown(), e -> done.countDown());

        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(1, runner.runningCount());
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        // 实现契约:计数先结清、回调后派发 —— done 触发时必然已归零
        assertEquals(0, runner.runningCount());
    }

    @Test
    void successCallbackReceivesResultViaExecutor() throws Exception {
        SimpleTaskRunner runner = new SimpleTaskRunner(stubPlugin(), DIRECT);
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        runner.submit("t", () -> "hello", r -> { result.set(r); done.countDown(); }, e -> done.countDown());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("hello", result.get());
    }

    @Test
    void cancelInterruptsAndRoutesToOnError() throws Exception {
        SimpleTaskRunner runner = new SimpleTaskRunner(stubPlugin(), DIRECT);
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);

        TaskHandle handle = runner.submit("t", () -> {
            started.countDown();
            new CountDownLatch(1).await();   // 永久阻塞,等待中断
            return "never";
        }, r -> {}, e -> { error.set(e); failed.countDown(); });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        handle.cancel();
        assertTrue(failed.await(5, TimeUnit.SECONDS));
        assertInstanceOf(InterruptedException.class, error.get());
        assertFalse(handle.isRunning());
    }

    @Test
    void cancelAllStopsEverything() throws Exception {
        SimpleTaskRunner runner = new SimpleTaskRunner(stubPlugin(), DIRECT);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch failed = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            runner.submit("t" + i, () -> {
                started.countDown();
                new CountDownLatch(1).await();
                return null;
            }, r -> {}, e -> failed.countDown());
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));
        runner.cancelAll();
        assertTrue(failed.await(5, TimeUnit.SECONDS));
        assertEquals(0, runner.runningCount());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

在 IDEA 运行 `SimpleTaskRunnerTest`。
Expected: 编译失败 "SimpleTaskRunner 不存在"(即测试先行成立)。

- [ ] **Step 3: 实现 SimpleTaskRunner**

`SimpleTaskRunner.java`:
```java
package fan.summer.zhiflow.api.host;

import fan.summer.zhiflow.api.PluginContext;
import fan.summer.zhiflow.api.SwissKitJPlugin;
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

    private final SwissKitJPlugin plugin;
    private final Executor callbackExecutor;
    private final AtomicInteger running = new AtomicInteger();
    private final Set<Handle> live = ConcurrentHashMap.newKeySet();

    /**
     * @param plugin the owning plugin (used for TCCL and thread naming)
     */
    public SimpleTaskRunner(SwissKitJPlugin plugin) {
        this(plugin, Platform::runLater);
    }

    /**
     * Test seam: inject a synchronous executor so tests need no FX toolkit.
     *
     * @param plugin           the owning plugin
     * @param callbackExecutor executor for onSuccess/onError dispatch
     */
    public SimpleTaskRunner(SwissKitJPlugin plugin, Executor callbackExecutor) {
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
```

- [ ] **Step 4: 运行测试确认通过**

在 IDEA 运行 `SimpleTaskRunnerTest`。
Expected: 4 个测试全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/host/SimpleTaskRunner.java SwissKitJ-Api/src/test/java/fan/summer/api/host/SimpleTaskRunnerTest.java
git commit -m "✨ feat(api): SimpleTaskRunner — TCCL-safe virtual-thread task execution with FX callbacks"
```

---

### Task 3: BasePluginHost(TDD)

**Files:**
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/host/BasePluginHost.java`
- Test: `SwissKitJ-Api/src/test/java/fan/summer/api/host/BasePluginHostTest.java`

**Interfaces:**
- Consumes: Task 1 全部接口;Task 2 `SimpleTaskRunner(SwissKitJPlugin)`;静态委托目标 `I18n`/`ThemeService`/`Themes`/`SkNotification`/`LoggerFactory`/`PluginContext.getClassLoader`。
- Produces: `public abstract class BasePluginHost implements PluginHost`,构造器 `protected BasePluginHost(SwissKitJPlugin plugin)`,仅 `settings()` 留 abstract。Task 5(DefaultPluginHost)与 Task 8(PreviewPluginHost)继承它。

- [ ] **Step 1: 写失败测试**

`BasePluginHostTest.java`:
```java
package fan.summer.zhiflow.api.host;

import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BasePluginHostTest {

    private static SwissKitJPlugin stubPlugin() {
        return new SwissKitJPlugin() {
            public String getId() { return "test.host.plugin"; }
            public String getName() { return "Test"; }
            public String getDescription() { return ""; }
            public ToolCategory getCategory() { return ToolCategory.OTHER; }
            public String getVersion() { return "0"; }
            public String getMdiIcon() { return "star"; }
            public Node createView() { return null; }
        };
    }

    /** 具体化:settings 用内存 Map。 */
    private static BasePluginHost host(SwissKitJPlugin p) {
        return new BasePluginHost(p) {
            private final Map<String, String> map = new HashMap<>();
            private final PluginSettings settings = new PluginSettings() {
                public Optional<String> get(String key) { return Optional.ofNullable(map.get(key)); }
                public String get(String key, String def) { return map.getOrDefault(key, def); }
                public void put(String key, String value) { if (value == null) map.remove(key); else map.put(key, value); }
                public void remove(String key) { map.remove(key); }
            };
            @Override public PluginSettings settings() { return settings; }
        };
    }

    @Test
    void pluginIdMirrorsPlugin() {
        assertEquals("test.host.plugin", host(stubPlugin()).pluginId());
    }

    @Test
    void facadesAreNonNullAndStable() {
        BasePluginHost h = host(stubPlugin());
        assertNotNull(h.logger(BasePluginHostTest.class));
        assertNotNull(h.i18n());
        assertNotNull(h.theme());
        assertNotNull(h.notifications());
        assertSame(h.tasks(), h.tasks());   // TaskRunner 是每 host 单例
        assertSame(h.i18n(), h.i18n());
    }

    @Test
    void i18nGetFallsBackToKey() {
        // I18n.get 未命中时返回 key 本身 —— 门面必须保持该语义
        assertEquals("no.such.key.xyz", host(stubPlugin()).i18n().get("no.such.key.xyz"));
    }

    @Test
    void nullPluginRejected() {
        assertThrows(NullPointerException.class, () -> host(null));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Expected: 编译失败 "BasePluginHost 不存在"。

- [ ] **Step 3: 实现 BasePluginHost**

`BasePluginHost.java`:
```java
package fan.summer.zhiflow.api.host;

import fan.summer.zhiflow.api.PluginContext;
import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.component.SkNotification;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.zhiflow.api.theme.ThemeService;
import fan.summer.zhiflow.api.theme.Themes;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.Scene;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Skeleton {@link PluginHost}: implements every facade except {@link #settings()},
 * which differs between the real host (H2-backed) and the preview window
 * (properties-file-backed).
 *
 * @since 3.2.0
 */
public abstract class BasePluginHost implements PluginHost {

    protected final SwissKitJPlugin plugin;
    private final TaskRunner tasks;

    private final I18nFacade i18n = new I18nFacade() {
        @Override public String get(String key, Object... args) {
            return (args == null || args.length == 0) ? I18n.get(key) : I18n.get(key, args);
        }
        @Override public void bind(StringProperty property, String key) {
            I18n.bind(property, key);
        }
        @Override public void registerBundle(String baseName) {
            I18n.registerPluginBundle(baseName, PluginContext.getClassLoader(plugin));
        }
        @Override public void addListener(Runnable onLocaleChanged) {
            I18n.addListener(onLocaleChanged);
        }
    };

    private final ThemeFacade theme = new ThemeFacade() {
        @Override public ThemeService.Theme current() { return ThemeService.current(); }
        @Override public void onChange(Consumer<ThemeService.Theme> listener) { ThemeService.onChange(listener); }
        @Override public void applyTo(Scene scene) { Themes.applyTo(scene); }
    };

    private final NotificationFacade notifications = new NotificationFacade() {
        @Override public void toast(Node context, SkNotification.Type type, String message) {
            SkNotification.toast(context, type, message);
        }
        @Override public void notify(Node context, SkNotification.Type type, String message) {
            SkNotification.notify(context, type, message);
        }
        @Override public boolean confirm(Node context, String title, String message) {
            return SkNotification.confirm(context, title, message);
        }
    };

    /**
     * @param plugin the plugin this host is bound to; must not be null
     */
    protected BasePluginHost(SwissKitJPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.tasks = new SimpleTaskRunner(plugin);
    }

    @Override public String pluginId() { return plugin.getId(); }
    @Override public PluginLogger logger(Class<?> cls) { return LoggerFactory.getLogger(cls); }
    @Override public TaskRunner tasks() { return tasks; }
    @Override public I18nFacade i18n() { return i18n; }
    @Override public ThemeFacade theme() { return theme; }
    @Override public NotificationFacade notifications() { return notifications; }
}
```

- [ ] **Step 4: 运行测试确认通过**

Expected: `BasePluginHostTest` 4 个测试 PASS(`SimpleTaskRunnerTest` 保持 PASS)。

- [ ] **Step 5: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/host/BasePluginHost.java SwissKitJ-Api/src/test/java/fan/summer/api/host/BasePluginHostTest.java
git commit -m "✨ feat(api): BasePluginHost — shared facade implementation, settings() left abstract"
```

---

### Task 4: ChildFirstResourceClassLoader 下沉到 API 模块

**Files:**
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/loader/ChildFirstResourceClassLoader.java`(移动)
- Delete: `SwissKit/src/main/java/fan/summer/plugin/ChildFirstResourceClassLoader.java`
- Modify: `SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java`(加 import)

**Interfaces:**
- Produces: `fan.summer.zhiflow.api.loader.ChildFirstResourceClassLoader extends URLClassLoader`,构造器 `(URL[] urls, ClassLoader parent)`,行为与原类完全一致。Task 9(preview)与宿主 `PluginLoader` 共用。

- [ ] **Step 1: git mv 移动文件**

```bash
mkdir -p SwissKitJ-Api/src/main/java/fan/summer/api/loader
git mv SwissKit/src/main/java/fan/summer/plugin/ChildFirstResourceClassLoader.java SwissKitJ-Api/src/main/java/fan/summer/api/loader/ChildFirstResourceClassLoader.java
```

- [ ] **Step 2: 改包名与日志(API 模块无 SLF4J)**

对移动后的文件做 3 处替换:

替换 1:
```java
package fan.summer.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```
→
```java
package fan.summer.zhiflow.api.loader;

import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
```

替换 2:
```java
    private static final Logger log = LoggerFactory.getLogger(ChildFirstResourceClassLoader.class);
```
→
```java
    private static final PluginLogger log = LoggerFactory.getLogger(ChildFirstResourceClassLoader.class);
```

替换 3(javadoc 中指向宿主类的引用失效,改为文字):
```java
 * @see PluginLoader
 */
```
→
```java
 * @since 3.2.0 (moved from the host module so the preview window shares identical loading semantics)
 */
```

- [ ] **Step 3: PluginLoader 补 import**

`PluginLoader.java` import 区加入(原同包无需 import,移动后需要):
```java
import fan.summer.zhiflow.api.loader.ChildFirstResourceClassLoader;
```

- [ ] **Step 4: 双模块编译验证**

1. IDEA Maven:`install -f SwissKitJ-Api/pom.xml -DskipTests` — Expected: BUILD SUCCESS
2. `mcp__idea__build_project` — Expected: BUILD SUCCESS(SwissKit 引用解析到新包)

- [ ] **Step 5: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/loader/ChildFirstResourceClassLoader.java SwissKit/src/main/java/fan/summer/plugin/ChildFirstResourceClassLoader.java SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java
git commit -m "♻️ refactor(api): move ChildFirstResourceClassLoader into SwissKitJ-Api for host/preview parity"
```

---

### Task 5: H2 存储层 + DefaultPluginHost

**Files:**
- Modify: `SwissKit/src/main/resources/init.sql`(末尾追加建表)
- Create: `SwissKit/src/main/java/fan/summer/database/entity/PluginSettingEntity.java`
- Create: `SwissKit/src/main/java/fan/summer/database/mapper/PluginSettingMapper.java`
- Create: `SwissKit/src/main/resources/mapper/PluginSettingMapper.xml`
- Modify: `SwissKit/src/main/resources/mybatis-config.xml`(注册 mapper)
- Create: `SwissKit/src/main/java/fan/summer/plugin/host/H2PluginSettings.java`
- Create: `SwissKit/src/main/java/fan/summer/plugin/host/DefaultPluginHost.java`

**Interfaces:**
- Consumes: Task 1 `PluginSettings`;Task 3 `BasePluginHost(SwissKitJPlugin)`;`DatabaseInit.getSqlSession()`(已有,用法同 `FavoriteService`)。
- Produces: `public class DefaultPluginHost extends BasePluginHost`,构造器 `DefaultPluginHost(SwissKitJPlugin)`(Task 6 的 hostFactory 默认值);`H2PluginSettings.purge(String pluginId)` 静态方法(Task 7 卸载清理用)。
- 测试说明:本任务无独立 DB 单测(规格 §7 允许)——`H2PluginSettings` 依赖 `DatabaseInit` 静态初始化,由 Task 6 的 fake-factory 测试隔离、Task 11 冒烟实测落库。

- [ ] **Step 1: init.sql 建表**

`SwissKit/src/main/resources/init.sql` 文件末尾追加:
```sql

CREATE TABLE IF NOT EXISTS plugin_setting
(
    id            INTEGER PRIMARY KEY AUTO_INCREMENT,
    plugin_id     VARCHAR(255) NOT NULL,
    setting_key   VARCHAR(255) NOT NULL,
    setting_value TEXT,
    UNIQUE (plugin_id, setting_key)
);
```

- [ ] **Step 2: 实体类**

`PluginSettingEntity.java`:
```java
package fan.summer.database.entity;

import lombok.Data;

/**
 * Entity for one plugin setting row: a key-value pair namespaced by plugin ID.
 *
 * @since 3.2.0
 */
@Data
public class PluginSettingEntity {
    /** Primary key, auto-generated by the database. */
    private Integer id;

    /** The owning plugin's ID ({@code SwissKitJPlugin.getId()}). */
    private String pluginId;

    /** The setting key, unique within one plugin. */
    private String settingKey;

    /** The setting value; may be null. */
    private String settingValue;
}
```

- [ ] **Step 3: Mapper 接口 + XML + 注册**

`PluginSettingMapper.java`:
```java
package fan.summer.database.mapper;

import fan.summer.database.entity.PluginSettingEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for {@link PluginSettingEntity} persistence.
 *
 * @since 3.2.0
 * @see PluginSettingEntity
 */
public interface PluginSettingMapper {

    /**
     * @param pluginId the plugin whose settings to load
     * @return all setting rows for the plugin; may be empty
     */
    List<PluginSettingEntity> selectByPluginId(@Param("pluginId") String pluginId);

    /**
     * Inserts or updates one setting (H2 MERGE on the (plugin_id, setting_key) key).
     *
     * @param entity the setting to upsert
     */
    void upsert(PluginSettingEntity entity);

    /**
     * @param pluginId   the owning plugin
     * @param settingKey the key to delete
     */
    void deleteByPluginIdAndKey(@Param("pluginId") String pluginId, @Param("settingKey") String settingKey);

    /**
     * Deletes every setting of the plugin (explicit uninstall only).
     *
     * @param pluginId the plugin to purge
     */
    void deleteByPluginId(@Param("pluginId") String pluginId);
}
```

`SwissKit/src/main/resources/mapper/PluginSettingMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper
        PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="fan.summer.database.mapper.PluginSettingMapper">

    <select id="selectByPluginId" resultType="fan.summer.database.entity.PluginSettingEntity">
        SELECT id, plugin_id AS pluginId, setting_key AS settingKey, setting_value AS settingValue
        FROM plugin_setting
        WHERE plugin_id = #{pluginId}
    </select>

    <insert id="upsert" parameterType="fan.summer.database.entity.PluginSettingEntity">
        MERGE INTO plugin_setting (plugin_id, setting_key, setting_value)
        KEY (plugin_id, setting_key)
        VALUES (#{pluginId}, #{settingKey}, #{settingValue})
    </insert>

    <delete id="deleteByPluginIdAndKey">
        DELETE FROM plugin_setting WHERE plugin_id = #{pluginId} AND setting_key = #{settingKey}
    </delete>

    <delete id="deleteByPluginId">
        DELETE FROM plugin_setting WHERE plugin_id = #{pluginId}
    </delete>

</mapper>
```

`mybatis-config.xml` 中,在这行之后:
```xml
        <mapper resource="mapper/PluginFavoriteMapper.xml"/>
```
插入:
```xml
        <mapper resource="mapper/PluginSettingMapper.xml"/>
```

- [ ] **Step 4: H2PluginSettings**

`H2PluginSettings.java`:
```java
package fan.summer.plugin.host;

import fan.summer.zhiflow.api.host.PluginSettings;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.PluginSettingEntity;
import fan.summer.database.mapper.PluginSettingMapper;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * H2-backed {@link PluginSettings}: cache-first reads (read-your-writes),
 * asynchronous persistence on a virtual thread — the same pattern as the host's
 * own settings cache in SwissKitJSettingUi.
 *
 * @since 3.2.0
 */
public class H2PluginSettings implements PluginSettings {

    private static final Logger log = LoggerFactory.getLogger(H2PluginSettings.class);

    private final String pluginId;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    /**
     * @param pluginId the namespace for every key in this store; must not be null
     */
    public H2PluginSettings(String pluginId) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
    }

    /** Lazily loads all rows of this plugin into the cache on first access. */
    private void ensureLoaded() {
        if (!loaded.compareAndSet(false, true)) return;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginSettingMapper mapper = session.getMapper(PluginSettingMapper.class);
            for (PluginSettingEntity e : mapper.selectByPluginId(pluginId)) {
                if (e.getSettingValue() != null) cache.put(e.getSettingKey(), e.getSettingValue());
            }
        } catch (Exception e) {
            log.error("Failed to load settings for plugin {}", pluginId, e);
        }
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        ensureLoaded();
        return Optional.ofNullable(cache.get(key));
    }

    @Override
    public String get(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    @Override
    public void put(String key, String value) {
        Objects.requireNonNull(key, "key");
        if (value == null) {
            remove(key);
            return;
        }
        ensureLoaded();
        cache.put(key, value);
        Thread.ofVirtual().name("plugin-settings-save").start(() -> {
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                PluginSettingMapper mapper = session.getMapper(PluginSettingMapper.class);
                PluginSettingEntity entity = new PluginSettingEntity();
                entity.setPluginId(pluginId);
                entity.setSettingKey(key);
                entity.setSettingValue(value);
                mapper.upsert(entity);
                session.commit();
            } catch (Exception e) {
                log.error("Failed to persist setting '{}' for plugin {}", key, pluginId, e);
            }
        });
    }

    @Override
    public void remove(String key) {
        Objects.requireNonNull(key, "key");
        ensureLoaded();
        cache.remove(key);
        Thread.ofVirtual().name("plugin-settings-save").start(() -> {
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                PluginSettingMapper mapper = session.getMapper(PluginSettingMapper.class);
                mapper.deleteByPluginIdAndKey(pluginId, key);
                session.commit();
            } catch (Exception e) {
                log.error("Failed to delete setting '{}' for plugin {}", key, pluginId, e);
            }
        });
    }

    /**
     * Purges every stored setting of the plugin. Called by PluginLoader on
     * EXPLICIT uninstall only — hot-reload keeps settings.
     *
     * @param pluginId the plugin to purge
     */
    public static void purge(String pluginId) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginSettingMapper mapper = session.getMapper(PluginSettingMapper.class);
            mapper.deleteByPluginId(pluginId);
            session.commit();
        } catch (Exception e) {
            log.error("Failed to purge settings for plugin {}", pluginId, e);
        }
    }
}
```

- [ ] **Step 5: DefaultPluginHost**

`DefaultPluginHost.java`:
```java
package fan.summer.plugin.host;

import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.host.BasePluginHost;
import fan.summer.zhiflow.api.host.PluginSettings;

/**
 * Host-side {@link fan.summer.zhiflow.api.host.PluginHost}: {@link BasePluginHost}
 * plus H2-backed settings.
 *
 * @since 3.2.0
 */
public class DefaultPluginHost extends BasePluginHost {

    private final PluginSettings settings;

    /**
     * @param plugin the plugin this host serves
     */
    public DefaultPluginHost(SwissKitJPlugin plugin) {
        super(plugin);
        this.settings = new H2PluginSettings(plugin.getId());
    }

    @Override
    public PluginSettings settings() {
        return settings;
    }
}
```

- [ ] **Step 6: 编译验证 + Commit**

Run: `mcp__idea__build_project` — Expected: BUILD SUCCESS。

```bash
git add SwissKit/src/main/resources/init.sql SwissKit/src/main/java/fan/summer/database/entity/PluginSettingEntity.java SwissKit/src/main/java/fan/summer/database/mapper/PluginSettingMapper.java SwissKit/src/main/resources/mapper/PluginSettingMapper.xml SwissKit/src/main/resources/mybatis-config.xml SwissKit/src/main/java/fan/summer/plugin/host
git commit -m "✨ feat(host): H2-backed PluginSettings + DefaultPluginHost"
```

---

### Task 6: PluginRegistry 注入 init() + isBusy 任务合并(TDD)

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/plugin/PluginRegistry.java`
- Test: `SwissKit/src/test/java/fan/summer/plugin/PluginRegistryHostTest.java`

**Interfaces:**
- Consumes: Task 1 `PluginHost`/`TaskRunner`;Task 5 `DefaultPluginHost::new`。
- Produces: `public boolean isBusy(SwissKitJPlugin)`(Task 7 的 MainWindow 依赖);包私有 `void setHostFactoryForTest(Function<SwissKitJPlugin, PluginHost>)`。
- 时序契约(全局约束):init 在 `plugins.addAll` 与 `registerPluginTools` **之前**。

- [ ] **Step 1: 写失败测试**

`PluginRegistryHostTest.java`(构造模式照抄同包 `PluginRegistryAiToolsTest`):
```java
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
```

- [ ] **Step 2: 运行测试确认失败**

Expected: 编译失败(`setHostFactoryForTest`/`isBusy` 不存在)。

- [ ] **Step 3: 改造 PluginRegistry**

import 区加入:
```java
import fan.summer.zhiflow.api.host.PluginHost;
import fan.summer.plugin.host.DefaultPluginHost;
import java.util.function.Function;
```

字段区(`toolsByPlugin` 之后)加入:
```java
    /** Per-plugin host facades, created in addPlugins and disposed in removePlugin. */
    private final Map<SwissKitJPlugin, PluginHost> hostsByPlugin = new HashMap<>();

    /** How hosts are created; replaceable so tests can inject a fake. */
    private Function<SwissKitJPlugin, PluginHost> hostFactory = DefaultPluginHost::new;

    /** Test seam — lets tests inject a fake host factory. */
    void setHostFactoryForTest(Function<SwissKitJPlugin, PluginHost> factory) {
        this.hostFactory = factory;
    }
```

`addPlugins` 方法体替换(init 先于可见性与 AI 工具注册——时序契约):
```java
    public void addPlugins(List<SwissKitJPlugin> toAdd) {
        log.debug("Adding {} plugin(s) to registry", toAdd.size());
        for (SwissKitJPlugin p : toAdd) {
            PluginHost host = hostFactory.apply(p);
            hostsByPlugin.put(p, host);
            try {
                PluginContext.runWith(p, () -> p.init(host));
            } catch (Exception e) {
                log.warn("Plugin {} threw on init(): {}", p.getId(), e.getMessage(), e);
            }
        }
        plugins.addAll(toAdd);
        for (SwissKitJPlugin p : toAdd) registerPluginTools(p);
    }
```

`removePlugin` 方法体开头(`unregisterPluginTools(plugin);` 之前)加入:
```java
        PluginHost host = hostsByPlugin.remove(plugin);
        if (host != null) {
            try {
                host.tasks().cancelAll();
            } catch (Exception e) {
                log.warn("Plugin {} task cancellation failed: {}", plugin.getId(), e.getMessage(), e);
            }
        }
```

`getActivePlugin()` 方法之后加入:
```java
    /**
     * Returns whether the plugin is busy: it reports running tasks itself OR its
     * host TaskRunner has running tasks. Used to decide background keepalive and
     * cached-view retention.
     *
     * @param plugin the plugin to check
     * @return true if the plugin should be kept alive in the background
     * @since 3.2.0
     */
    public boolean isBusy(SwissKitJPlugin plugin) {
        if (plugin.hasRunningTasks()) return true;
        PluginHost host = hostsByPlugin.get(plugin);
        return host != null && host.tasks().runningCount() > 0;
    }
```

`deactivate()` 中替换:
```java
            if (activePlugin.hasRunningTasks()) {
```
→
```java
            if (isBusy(activePlugin)) {
```

- [ ] **Step 4: 运行测试确认通过 + 回归**

运行 `PluginRegistryHostTest`(4 个 PASS)与既有 `PluginRegistryAiToolsTest`(保持 PASS——stub 插件的 `init` 是默认空操作,默认 hostFactory 构造 `DefaultPluginHost` 不触碰 DB/FX)。

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/plugin/PluginRegistry.java SwissKit/src/test/java/fan/summer/plugin/PluginRegistryHostTest.java
git commit -m "✨ feat(host): inject PluginHost via init() in addPlugins, merge TaskRunner into isBusy()"
```

---

### Task 7: MainWindow 判定切换 + 卸载清理设置

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/MainWindow.java`(后退回调)
- Modify: `SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java`(`uninstallPlugin`)

**Interfaces:**
- Consumes: Task 6 `registry.isBusy(plugin)`;Task 5 `H2PluginSettings.purge(String)`。

- [ ] **Step 1: MainWindow 后退判定用 isBusy**

`MainWindow.java` 替换:
```java
            SwissKitJPlugin current = registry.getActivePlugin();
            if (current != null && !current.hasRunningTasks()) {
                cachedViews.remove(current);
            }
```
→
```java
            SwissKitJPlugin current = registry.getActivePlugin();
            if (current != null && !registry.isBusy(current)) {
                cachedViews.remove(current);
            }
```

- [ ] **Step 2: 卸载时清空该插件设置**

`PluginLoader.java` import 区加入:
```java
import fan.summer.plugin.host.H2PluginSettings;
```
`uninstallPlugin` 中替换:
```java
        log.info("Uninstalling plugin: id={}, jar={}", plugin.getId(), jar.getFileName());
        unloadJar(jar);
```
→
```java
        log.info("Uninstalling plugin: id={}, jar={}", plugin.getId(), jar.getFileName());
        unloadJar(jar);

        // Explicit uninstall wipes the plugin's persisted settings; hot-reload keeps them.
        H2PluginSettings.purge(plugin.getId());
```

- [ ] **Step 3: 编译验证 + Commit**

Run: `mcp__idea__build_project` — Expected: BUILD SUCCESS。

```bash
git add SwissKit/src/main/java/fan/summer/ui/MainWindow.java SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java
git commit -m "✨ feat(host): back-navigation uses isBusy(); uninstall purges plugin settings"
```

---

### Task 8: PropertiesPluginSettings + PreviewPluginHost(TDD)

**Files:**
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/preview/PropertiesPluginSettings.java`(包私有)
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewPluginHost.java`(包私有)
- Test: `SwissKitJ-Api/src/test/java/fan/summer/api/preview/PropertiesPluginSettingsTest.java`

**Interfaces:**
- Consumes: Task 1 `PluginSettings`;Task 3 `BasePluginHost`。
- Produces: 包私有 `PreviewPluginHost(SwissKitJPlugin)`(Task 9 在同包的 `PluginPreviewWindow` 中使用);包私有 `PropertiesPluginSettings(Path baseDir, String pluginId)` 测试缝。

- [ ] **Step 1: 写失败测试**

`PropertiesPluginSettingsTest.java`:
```java
package fan.summer.zhiflow.api.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesPluginSettingsTest {

    @TempDir
    Path dir;

    @Test
    void putGetRemoveRoundTrip() {
        PropertiesPluginSettings s = new PropertiesPluginSettings(dir, "com.example.tool");
        assertTrue(s.get("k").isEmpty());
        s.put("k", "v");
        assertEquals("v", s.get("k").orElseThrow());
        assertEquals("v", s.get("k", "def"));
        s.remove("k");
        assertTrue(s.get("k").isEmpty());
        assertEquals("def", s.get("k", "def"));
    }

    @Test
    void persistsAcrossInstances() {
        new PropertiesPluginSettings(dir, "com.example.tool").put("lang", "zh");
        PropertiesPluginSettings reloaded = new PropertiesPluginSettings(dir, "com.example.tool");
        assertEquals("zh", reloaded.get("lang").orElseThrow());
        assertTrue(Files.exists(dir.resolve("com.example.tool.properties")));
    }

    @Test
    void nullValueMeansRemove() {
        PropertiesPluginSettings s = new PropertiesPluginSettings(dir, "p");
        s.put("k", "v");
        s.put("k", null);
        assertTrue(s.get("k").isEmpty());
    }

    @Test
    void nullKeyRejected() {
        PropertiesPluginSettings s = new PropertiesPluginSettings(dir, "p");
        assertThrows(NullPointerException.class, () -> s.get(null));
        assertThrows(NullPointerException.class, () -> s.put(null, "v"));
        assertThrows(NullPointerException.class, () -> s.remove(null));
    }

    @Test
    void pluginIdIsSanitizedForFileName() {
        assertEquals("a_b_c.d-e_f", PropertiesPluginSettings.sanitize("a/b:c.d-e f"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Expected: 编译失败 "PropertiesPluginSettings 不存在"。

- [ ] **Step 3: 实现两个类**

`PropertiesPluginSettings.java`:
```java
package fan.summer.zhiflow.api.preview;

import fan.summer.zhiflow.api.host.PluginSettings;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Preview-mode {@link PluginSettings} backed by a per-plugin properties file
 * under {@code ~/.swisskit/preview-settings/}. Write-through: every mutation
 * stores the file immediately (preview writes are low-frequency).
 *
 * @since 3.2.0
 */
class PropertiesPluginSettings implements PluginSettings {

    private static final PluginLogger log = LoggerFactory.getLogger(PropertiesPluginSettings.class);

    private final Path file;
    private final Properties props = new Properties();

    PropertiesPluginSettings(String pluginId) {
        this(Path.of(System.getProperty("user.home"), ".swisskit", "preview-settings"), pluginId);
    }

    /** Test seam: explicit base directory. */
    PropertiesPluginSettings(Path baseDir, String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        this.file = baseDir.resolve(sanitize(pluginId) + ".properties");
        load();
    }

    /** File-name safety: anything outside [a-zA-Z0-9._-] becomes '_'. */
    static String sanitize(String pluginId) {
        return pluginId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Failed to load preview settings {}: {}", file, e.getMessage());
        }
    }

    private synchronized void store() {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "SwissKitJ preview settings");
            }
        } catch (IOException e) {
            log.warn("Failed to store preview settings {}: {}", file, e.getMessage());
        }
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(props.getProperty(key));
    }

    @Override
    public String get(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    @Override
    public void put(String key, String value) {
        Objects.requireNonNull(key, "key");
        if (value == null) {
            remove(key);
            return;
        }
        props.setProperty(key, value);
        store();
    }

    @Override
    public void remove(String key) {
        Objects.requireNonNull(key, "key");
        props.remove(key);
        store();
    }
}
```

`PreviewPluginHost.java`:
```java
package fan.summer.zhiflow.api.preview;

import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.host.BasePluginHost;
import fan.summer.zhiflow.api.host.PluginSettings;

/**
 * Preview-side {@link fan.summer.zhiflow.api.host.PluginHost}: {@link BasePluginHost}
 * plus properties-file settings, so plugins behave the same in the preview
 * window as inside the real host.
 *
 * @since 3.2.0
 */
class PreviewPluginHost extends BasePluginHost {

    private final PluginSettings settings;

    PreviewPluginHost(SwissKitJPlugin plugin) {
        super(plugin);
        this.settings = new PropertiesPluginSettings(plugin.getId());
    }

    @Override
    public PluginSettings settings() {
        return settings;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Expected: `PropertiesPluginSettingsTest` 5 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/preview/PropertiesPluginSettings.java SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewPluginHost.java SwissKitJ-Api/src/test/java/fan/summer/api/preview/PropertiesPluginSettingsTest.java
git commit -m "✨ feat(preview): PreviewPluginHost with properties-file settings"
```

---

### Task 9: PluginPreviewWindow / PreviewShell 与宿主语义对齐

**注意:** `PluginPreviewWindow.java` 与 `PreviewShell.java` 当前有**未提交的在途改动**——本任务在其现状之上继续修改,提交时一并纳入属预期。

**Files:**
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/preview/PluginPreviewWindow.java`
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewShell.java`

**Interfaces:**
- Consumes: Task 4 `fan.summer.zhiflow.api.loader.ChildFirstResourceClassLoader(URL[], ClassLoader)`;Task 8 `PreviewPluginHost(SwissKitJPlugin)`;`PluginContext.register/runWith/callWith/wrapEvents`(已有)。

- [ ] **Step 1: 换 classloader 并注册 TCCL**

`PluginPreviewWindow.java` import 区加入:
```java
import fan.summer.zhiflow.api.PluginContext;
import fan.summer.zhiflow.api.loader.ChildFirstResourceClassLoader;
```
`launch()` 中替换:
```java
                classLoader = new URLClassLoader(
                    new java.net.URL[]{jarPath.toUri().toURL()},
                    getClass().getClassLoader()
                );
                ServiceLoader<SwissKitJPlugin> sl = ServiceLoader.load(SwissKitJPlugin.class, classLoader);
                for (SwissKitJPlugin p : sl) {
                    loadedPlugins.add(p);
                }
```
→
```java
                classLoader = new ChildFirstResourceClassLoader(
                    new java.net.URL[]{jarPath.toUri().toURL()},
                    getClass().getClassLoader()
                );
                ServiceLoader<SwissKitJPlugin> sl = ServiceLoader.load(SwissKitJPlugin.class, classLoader);
                for (SwissKitJPlugin p : sl) {
                    loadedPlugins.add(p);
                    PluginContext.register(p, classLoader);
                }
```

- [ ] **Step 2: 注入 PreviewPluginHost + 关窗取消任务**

`launch()` 中替换:
```java
        // Build the window
        final URLClassLoader finalCl = classLoader;
        final List<SwissKitJPlugin> finalPlugins = List.copyOf(loadedPlugins);
```
→
```java
        // Build the window
        final URLClassLoader finalCl = classLoader;
        final List<SwissKitJPlugin> finalPlugins = List.copyOf(loadedPlugins);

        // Inject PluginHost exactly like the real host does (before the plugin
        // becomes visible in the shell; init failures must not block the preview).
        final List<PreviewPluginHost> hosts = new ArrayList<>();
        for (SwissKitJPlugin p : finalPlugins) {
            PreviewPluginHost host = new PreviewPluginHost(p);
            hosts.add(host);
            try {
                PluginContext.runWith(p, () -> p.init(host));
            } catch (Exception e) {
                System.err.println("[preview] plugin " + p.getId() + " threw on init(): " + e.getMessage());
            }
        }
```
并替换关闭回调:
```java
            () -> {
                if (finalCl != null) {
                    try { finalCl.close(); } catch (Exception ignored) {}
                }
            }
```
→
```java
            () -> {
                for (PreviewPluginHost h : hosts) {
                    try { h.tasks().cancelAll(); } catch (Exception ignored) {}
                }
                if (finalCl != null) {
                    try { finalCl.close(); } catch (Exception ignored) {}
                }
            }
```

- [ ] **Step 3: PreviewShell 视图创建补 wrapEvents**

`PreviewShell.java` 中替换:
```java
                view = PluginContext.callWith(plugin, plugin::createView);
                cachedViews.put(plugin, view);
```
→
```java
                view = PluginContext.callWith(plugin, plugin::createView);
                if (view != null) PluginContext.wrapEvents(plugin, view);
                cachedViews.put(plugin, view);
```

- [ ] **Step 4: 编译验证 + Commit**

1. IDEA Maven:`install -f SwissKitJ-Api/pom.xml -DskipTests` — Expected: BUILD SUCCESS
2. `mcp__idea__build_project` — Expected: BUILD SUCCESS

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/preview/PluginPreviewWindow.java SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewShell.java
git commit -m "✨ feat(preview): align preview loading with host — child-first CL, TCCL registration, PluginHost injection, wrapEvents"
```

---

### Task 10: 文档 — plugin-host.md + migration 增补 + pitfalls + CLAUDE.md

**Files:**
- Create: `docs/plugins/plugin-host.md`
- Create: `docs/zh/plugins/plugin-host.md`
- Modify: `docs/migration-3.2.md` 与 `docs/zh/migration-3.2.md`(增补一节;若文件不存在,先执行修复计划 Task 10)
- Modify: `docs/plugins/pitfalls.md` 与 `docs/zh/plugins/pitfalls.md`(i18n classloader 条目)
- Modify: `docs/plugins/_sidebar.md` 与 `docs/zh/plugins/_sidebar.md`(如列出了各文档条目,加 plugin-host 链接)
- Modify: `CLAUDE.md`(Plugin Development 一节)

- [ ] **Step 1: 创建 docs/plugins/plugin-host.md**

```markdown
# PluginHost — 宿主门面(v3.2.0+)

宿主在插件加载时注入一个 `PluginHost`,插件经它访问全部宿主能力——不再需要
记忆分散的静态入口。旧插件不实现 `init()` 也完全兼容;静态入口继续可用,
`PluginHost` 是推荐路径。

## 获取方式

```java
public class MyPlugin implements SwissKitJPlugin {

    private PluginHost host;

    @Override
    public void init(PluginHost host) {
        this.host = host;   // 保存引用,整个生命周期有效
    }
}
```

时序契约:`init()` 每插件恰好调用一次,在 JavaFX Application Thread 上、
插件进入注册表可见列表和 `aiTools()` 注册之前;调用时 TCCL 已设为插件自己
的 ClassLoader。

## 能力总览

| 方法 | 用途 |
|---|---|
| `pluginId()` | 本插件 ID |
| `logger(Class)` | 接入宿主日志管线(等价 `LoggerFactory.getLogger`) |
| `settings()` | 键值设置,按插件 ID 命名空间隔离,宿主落 H2、预览窗口落本地文件 |
| `tasks()` | 后台任务:自动 TCCL、回调保证 FX 线程、自动后台保活 |
| `i18n()` | `registerBundle("i18n.messages")` 自动用插件自己的 ClassLoader |
| `theme()` | 当前主题 / 变更监听 / `applyTo(scene)` 给自建 Stage 上主题 |
| `notifications()` | toast / notify / confirm(委托 SkNotification) |

## 设置持久化

```java
// 读(缓存优先,写后读一致)
String lastDir = host.settings().get("last.dir", System.getProperty("user.home"));

// 写(异步落库;null 值等价删除)
host.settings().put("last.dir", chosenDir);
```

数据按 `pluginId()` 隔离,插件之间互不可见。**显式卸载**插件时宿主清空其全部
设置;热重载(替换 JAR)保留设置。

## 后台任务

```java
TaskHandle handle = host.tasks().submit("export-excel",
    () -> doHeavyExport(file),                 // 后台虚拟线程,TCCL 已设
    result -> statusLabel.setText("Done"),     // FX 线程
    error  -> statusLabel.setText("Failed: " + error.getMessage()));  // FX 线程
```

经 `tasks()` 提交的任务在运行期间自动让宿主把插件保活在后台(工具卡片出现
运行状态点)——**无需**再 override `hasRunningTasks()`;两种机制可共存,宿主
取二者的逻辑或。插件卸载时宿主对剩余任务统一 `cancel()`(线程中断),长任务
应正确响应中断。

## i18n

```java
@Override
public Node createView() {
    host.i18n().registerBundle("i18n.messages");   // 无需传 ClassLoader
    Label title = new Label(host.i18n().get("my.title"));
    host.i18n().bind(title.textProperty(), "my.title");  // 随语言切换自动更新
    ...
}
```
```

- [ ] **Step 2: 创建 docs/zh/plugins/plugin-host.md**

Step 1 内容本就是中文,直接复制同一份到 `docs/zh/plugins/plugin-host.md`(该目录与 `docs/plugins/` 当前内容一致,均为中文,保持既有惯例)。

- [ ] **Step 3: migration-3.2.md 增补**

`docs/migration-3.2.md` 在 `## Checklist` 节之前插入:
```markdown
## New: PluginHost / PluginSettings / TaskRunner

Plugins can now override `init(PluginHost host)` to receive a per-plugin host
facade: namespaced persistent settings (`host.settings()`), TCCL-safe
background tasks with automatic background keepalive (`host.tasks()`),
ClassLoader-free i18n bundle registration (`host.i18n().registerBundle(...)`),
plus theme and notification access. Existing plugins need no change — the
static entry points keep working. See `plugins/plugin-host.md` for the full
guide.

The preview window (`PluginPreviewWindow`) now loads plugins with the exact
same semantics as the real host: child-first resource ClassLoader, TCCL
registration, and `init(PluginHost)` injection (settings persist under
`~/.swisskit/preview-settings/`).
```
`docs/zh/migration-3.2.md` 在对应位置插入其中文翻译(标题「新增:PluginHost / PluginSettings / TaskRunner」,内容一一对应)。同时在两个文件的 Checklist 列表末尾各追加一行:
```markdown
- [ ] (Optional) adopt `init(PluginHost)` for settings/tasks/i18n instead of static entry points
```

- [ ] **Step 4: pitfalls.md 更新 i18n 条目**

在 `docs/plugins/pitfalls.md` 与 `docs/zh/plugins/pitfalls.md` 中搜索 `registerPluginBundle`,在包含它的小节末尾追加:
```markdown
> **v3.2.0+:** 推荐改用 `host.i18n().registerBundle("i18n.messages")`(`PluginHost`
> 经 `init()` 注入)——它自动使用插件自己的 ClassLoader,从根上避免本坑。
```

- [ ] **Step 5: 侧栏链接**

打开 `docs/plugins/_sidebar.md` 与 `docs/zh/plugins/_sidebar.md`:若其中列出 i18n/entry-point 等文档条目,在 i18n 条目之后按相邻条目的路径格式添加 plugin-host.md 链接(文字:`PluginHost 宿主门面`);若无此类列表则跳过。

- [ ] **Step 6: CLAUDE.md 更新**

`CLAUDE.md` 的 Plugin Development 接口代码块中,替换:
```java
    default ToolType getType()     { return ToolType.PLUGIN; }    // PLUGIN / BUILTIN
```
→
```java
    default ToolType getType()     { return ToolType.PLUGIN; }    // PLUGIN / BUILTIN
    default void init(PluginHost host) {}  // v3.2.0+: host facade (settings/tasks/i18n/theme/notifications)
```
并在该代码块之后、`**External plugins**` 之前插入:
```markdown
**PluginHost (v3.2.0+)**: injected via `init(PluginHost)` exactly once (FX thread, before the
plugin is visible in the registry and before `aiTools()` registration). Provides `settings()`
(namespaced KV, H2-backed; preview mode uses `~/.swisskit/preview-settings/`), `tasks()`
(TCCL-safe background tasks — running tasks automatically keep the plugin backgrounded, merged
with `hasRunningTasks()` via `PluginRegistry.isBusy`), `i18n()` (`registerBundle` without a
ClassLoader parameter), `theme()`, `notifications()`, `logger()`. Old static entry points remain
valid. See `docs/plugins/plugin-host.md`.
```

- [ ] **Step 7: Commit**

```bash
git add docs/plugins/plugin-host.md docs/zh/plugins/plugin-host.md docs/migration-3.2.md docs/zh/migration-3.2.md docs/plugins/pitfalls.md docs/zh/plugins/pitfalls.md docs/plugins/_sidebar.md docs/zh/plugins/_sidebar.md CLAUDE.md
git commit -m "📝 docs(plugins): PluginHost guide, migration/pitfalls/CLAUDE.md updates"
```

---

### Task 11: 全量构建 + 冒烟验证

**Files:** 无新改动;端到端验证 Task 1–10。

- [ ] **Step 1: 全部测试**

在 IDEA 中运行两个模块的全部测试(至少:`SimpleTaskRunnerTest`、`BasePluginHostTest`、`PropertiesPluginSettingsTest`、`PluginRegistryHostTest`、`PluginRegistryAiToolsTest`、`AiChatMessageTest`)。
Expected: 全部 PASS。

- [ ] **Step 2: 全量构建**

1. IDEA Maven:`install -f SwissKitJ-Api/pom.xml -DskipTests` — Expected: BUILD SUCCESS
2. IDEA Maven:`clean package -f SwissKit/pom.xml -DskipTests` — Expected: BUILD SUCCESS

- [ ] **Step 3: 启动冒烟**

```bash
java -jar SwissKit/target/SwissKitJ-3.2.0.jar
```
逐项检查:
1. 应用正常启动,日志无 `plugin_setting` 建表错误、无 MyBatis mapper 解析错误(看 `.swisskit/logs/swisskit.log`)。
2. 所有内置工具正常打开(init 注入对存量插件是无操作,不得引入回归)。
3. 若有测试插件 JAR:放入 `.swisskit/plugin/` 热加载正常;卸载后重装,确认其设置已被清空(H2 文件 `.swisskit/swisskit.db` 中 `plugin_setting` 无该插件行——可经 IDEA Database 工具查看,**查看前先关闭应用避免 H2 文件锁**,参见 memory `h2-lock-no-window-startup-hang`)。
4. 工具进入后台的行为不回归:打开一个有后台任务的工具(如邮件群发)、返回主页,工具卡片状态点仍亮。

- [ ] **Step 4: 若有失败项**

回到对应任务修复后重跑本任务;全部通过则计划完成(不自动 push)。

---

## 与修复计划的关系

- 前置:修复计划 Task 8(SkNotification)、Task 10(migration-3.2.md)必须先完成。
- 本计划完成后,v3.2.0 的两条工作线(一致性修复批次 + Plugin SDK 统一层)全部落地;发布前统一跑一次修复计划 Task 11 + 本计划 Task 11 的冒烟清单。
