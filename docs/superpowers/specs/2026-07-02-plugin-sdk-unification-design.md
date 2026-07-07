# Plugin SDK 统一层设计规格(v3.2.0)

**日期:** 2026-07-02
**状态:** 已获用户批准的设计,待实施
**范围:** PluginHost 门面、PluginSettings、TaskRunner、preview 包 classloader 统一 —— 四项全部纳入 v3.2.0。

## 1. 目标与非目标

**目标**

1. 插件通过单一注入的 `PluginHost` 门面访问全部宿主能力(全量门面:含 i18n/theme/通知代理),取代分散的 6 套静态入口。
2. 插件获得官方设置持久化 API(`PluginSettings`),按插件 ID 命名空间隔离,宿主态落 H2,预览态落本地 properties 文件。
3. 插件获得官方后台任务 API(`TaskRunner`):自动 TCCL、回调保证 FX 线程、任务计数在宿主侧与 `hasRunningTasks()` **合并**——经 TaskRunner 提交的任务自动获得后台保活与状态点。
4. 预览窗口与真实宿主的插件加载语义统一:同一个 `ChildFirstResourceClassLoader`、同样的 `PluginContext.register`/`callWith`/`wrapEvents`、同样的 `init(PluginHost)` 注入。

**非目标(3.3.0+)**

- 跨插件通信/发现 API。
- 废弃现有静态入口(`I18n`/`ThemeService`/`LoggerFactory`/`AiServiceProvider` 保持原样可用;门面是推荐路径,不是唯一路径)。
- SwissKitJ-Api 发布到远程 Maven 仓库。

## 2. 总体架构

```
SwissKitJ-Api(接口 + 可复用实现;依赖仍仅 JavaFX/SLF4J-free,零 DB)
├─ fan.summer.api.host.PluginHost            门面接口
├─ fan.summer.api.host.PluginSettings        KV 接口
├─ fan.summer.api.host.TaskRunner            任务接口
├─ fan.summer.api.host.TaskHandle            任务句柄接口
├─ fan.summer.api.host.I18nFacade            i18n 子门面接口
├─ fan.summer.api.host.ThemeFacade           主题子门面接口
├─ fan.summer.api.host.NotificationFacade    通知子门面接口
├─ fan.summer.api.host.SimpleTaskRunner      TaskRunner 通用实现(宿主/预览共用)
├─ fan.summer.api.host.BasePluginHost        抽象基类:除 settings() 外全部实现
└─ fan.summer.api.loader.ChildFirstResourceClassLoader   从宿主原样下沉

SwissKit(宿主实现)
├─ fan.summer.plugin.host.DefaultPluginHost      extends BasePluginHost,settings→H2
├─ fan.summer.plugin.host.H2PluginSettings       缓存 + 虚拟线程异步写,模式镜像 SwissKitJSettingUi
├─ fan.summer.database.entity.PluginSettingEntity
├─ fan.summer.database.mapper.PluginSettingMapper(+ mapper XML)
├─ init.sql                                       新表 plugin_setting
├─ fan.summer.plugin.PluginRegistry               注入点 + 任务合并 + isBusy()
├─ fan.summer.plugin.PluginLoader                 改 import(classloader 下沉);卸载时清设置
└─ fan.summer.ui.MainWindow                       后退回收视图的判定改用 registry.isBusy()

SwissKitJ-Api preview 包(预览实现,均包私有)
├─ PreviewPluginHost        extends BasePluginHost,settings→properties 文件
└─ PropertiesPluginSettings ~/.swisskit/preview-settings/<sanitized-plugin-id>.properties
```

依赖方向不变:API 模块零 DB 依赖,`PluginHost` 是 API 定义接口、宿主/预览各自实现并注入。

## 3. 接口定义(权威签名)

### 3.1 SwissKitJPlugin 新增注入点

```java
/**
 * Called exactly once by the host, on the JavaFX Application Thread, after the
 * plugin is instantiated and before it becomes visible in the registry (and
 * before aiTools() registration). The plugin's ClassLoader is on the TCCL.
 * Store the reference; it stays valid for the plugin's whole lifetime.
 * The default implementation is a no-op — existing plugins need no change.
 *
 * @param host the host facade bound to this plugin instance
 * @since 3.2.0
 */
default void init(PluginHost host) {}
```

### 3.2 PluginHost

```java
package fan.summer.api.host;

public interface PluginHost {
    /** The owning plugin's ID (same as SwissKitJPlugin.getId()). */
    String pluginId();
    /** Logger routed into the host logging backbone (delegates LoggerFactory). */
    PluginLogger logger(Class<?> cls);
    /** Key-value settings, namespaced by pluginId(). */
    PluginSettings settings();
    /** TCCL-safe background tasks; running count feeds hasRunningTasks() merging. */
    TaskRunner tasks();
    I18nFacade i18n();
    ThemeFacade theme();
    /** Named notifications() (not notify()) — a zero-arg notify() would clash with the final Object.notify(). */
    NotificationFacade notifications();
}
```

### 3.3 PluginSettings

```java
public interface PluginSettings {
    /** @return the stored value, or empty if absent. Key must be non-null. */
    Optional<String> get(String key);
    /** @return the stored value, or {@code defaultValue} if absent. */
    String get(String key, String defaultValue);
    /** Stores a value. {@code value == null} is equivalent to {@link #remove}. Read-your-writes guaranteed (cache-first). */
    void put(String key, String value);
    /** Removes the key; no-op if absent. */
    void remove(String key);
}
```

### 3.4 TaskRunner / TaskHandle

```java
public interface TaskRunner {
    /** Submits fire-and-forget work. Uncaught throwables are logged, never swallowed silently. */
    TaskHandle submit(String name, Runnable work);
    /**
     * Submits work with result callbacks. onSuccess/onError are ALWAYS invoked on the
     * JavaFX Application Thread. Either callback may be null. Cancellation (interrupt)
     * routes to onError with the InterruptedException.
     */
    <T> TaskHandle submit(String name, Callable<T> work,
                          Consumer<T> onSuccess, Consumer<Throwable> onError);
    /** Number of tasks currently running (submitted and not yet finished/cancelled). */
    int runningCount();
    /** Cancels (interrupts) all running tasks. Called by the host on plugin unload. */
    void cancelAll();
}

public interface TaskHandle {
    String name();
    boolean isRunning();
    /** Requests cancellation via thread interrupt. Idempotent. */
    void cancel();
}
```

### 3.5 子门面

```java
public interface I18nFacade {
    /** Delegates I18n.get(key, args). */
    String get(String key, Object... args);
    /** Delegates I18n.bind(property, key). */
    void bind(StringProperty property, String key);
    /**
     * Registers the plugin's message bundle using the PLUGIN'S OWN ClassLoader
     * automatically (resolved via PluginContext) — no ClassLoader parameter needed.
     * Replaces the error-prone I18n.registerPluginBundle(baseName, classLoader).
     */
    void registerBundle(String baseName);
    /** Delegates I18n.addListener(onLocaleChanged). */
    void addListener(Runnable onLocaleChanged);
}

public interface ThemeFacade {
    ThemeService.Theme current();
    void onChange(Consumer<ThemeService.Theme> listener);
    /** For plugin-owned Stages: loads common css + stamps active theme class (delegates Themes.applyTo). */
    void applyTo(Scene scene);
}

public interface NotificationFacade {
    void toast(Node context, SkNotification.Type type, String message);
    void notify(Node context, SkNotification.Type type, String message);
    boolean confirm(Node context, String title, String message);
}
```

> 依赖说明:`NotificationFacade` 引用 `SkNotification`(修复计划 Task 8 的产物)。**实施顺序上本规格依赖修复计划 Task 8 先完成**;若独立实施,临时用 `GlassNotification` 并在 Task 8 落地时随 sed 一并改名。

### 3.6 BasePluginHost(API 模块,抽象类)

除 `settings()` 外全部通用实现,宿主与预览各自只补存储:

```java
public abstract class BasePluginHost implements PluginHost {
    protected final SwissKitJPlugin plugin;
    private final TaskRunner tasks;

    protected BasePluginHost(SwissKitJPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.tasks = new SimpleTaskRunner(plugin);
    }
    // pluginId() → plugin.getId()
    // logger(cls) → LoggerFactory.getLogger(cls)
    // tasks() → tasks
    // i18n()  → 匿名/内部实现,registerBundle 用 PluginContext.getClassLoader(plugin)
    // theme() → 委托 ThemeService / Themes
    // notifications() → 委托 SkNotification
    // settings() 留 abstract
}
```

### 3.7 SimpleTaskRunner(API 模块,公共实现)

- 执行:每任务一个虚拟线程(`Thread.ofVirtual().name("plugin-task-" + pluginId + "-" + name)`)。
- TCCL:任务体包在 `PluginContext.runWith(plugin, work)` 中。
- 计数:`AtomicInteger`,任务启动 `incrementAndGet`,finally 中 `decrementAndGet`。
- 回调:经可注入的 `Executor callbackExecutor` 派发,默认 `Platform::runLater`;测试用构造器 `SimpleTaskRunner(SwissKitJPlugin plugin, Executor callbackExecutor)` 注入直接执行器,无需 FX Toolkit。
- 取消:`TaskHandle.cancel()` 中断执行线程;`InterruptedException`/中断态路由到 `onError`;`cancelAll()` 遍历在跑句柄逐个 cancel。
- 无回调重载的未捕获异常:用 `LoggerFactory.getLogger(SimpleTaskRunner.class)` 记 error,不静默。

## 4. 宿主侧集成

### 4.1 注入点 —— PluginRegistry.addPlugins(单一漏斗)

`addPlugins()` 已经是内置工具、外部 JAR、热重载三条路径的共同入口且在 FX 线程运行。改造:

```java
private final Map<SwissKitJPlugin, PluginHost> hostsByPlugin = new HashMap<>();
private Function<SwissKitJPlugin, PluginHost> hostFactory = DefaultPluginHost::new; // 包私有 setter 作测试缝

public void addPlugins(List<SwissKitJPlugin> toAdd) {
    plugins.addAll(toAdd);
    for (SwissKitJPlugin p : toAdd) {
        PluginHost host = hostFactory.apply(p);
        hostsByPlugin.put(p, host);
        try {
            PluginContext.runWith(p, () -> p.init(host));
        } catch (Exception e) {
            log.warn("Plugin {} threw on init(): {}", p.getId(), e.getMessage(), e);
            // 插件照常保留:init 失败不阻断加载(与 onActivate 容错一致)
        }
        registerPluginTools(p);
    }
}
```

时序契约:`init` 在 `aiTools()` 注册**之前**、每插件恰好一次。`removePlugin()`:`hostsByPlugin.remove(plugin)` 并对其 `tasks().cancelAll()`。

### 4.2 任务合并 —— registry.isBusy()

```java
/** True if the plugin itself reports running tasks OR its TaskRunner has running tasks. */
public boolean isBusy(SwissKitJPlugin plugin) {
    if (plugin.hasRunningTasks()) return true;
    PluginHost host = hostsByPlugin.get(plugin);
    return host != null && host.tasks().runningCount() > 0;
}
```

替换两处现有判定:

1. `PluginRegistry.deactivate()`:`activePlugin.hasRunningTasks()` → `isBusy(activePlugin)`(决定进后台还是真停用)。
2. `MainWindow` 后退回调:`current != null && !current.hasRunningTasks()` → `current != null && !registry.isBusy(current)`(决定是否回收缓存视图)。

ToolCard 后台状态点走 `registry.isBackground(plugin)`,不需改——backgrounding 判定已在 deactivate() 合并。

### 4.3 H2 存储

`init.sql` 追加:

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

`H2PluginSettings`(每插件一实例):

- 首次访问时懒加载该 plugin_id 的全部行进 `ConcurrentHashMap` 缓存;`get` 只读缓存(read-your-writes)。
- `put`:先写缓存,再起虚拟线程(命名 `plugin-settings-save`)执行 H2 `MERGE INTO`(upsert);失败记 error 日志,缓存保留(下次 put 重试覆盖)。
- `remove`:删缓存 + 异步 DELETE。
- Mapper 方法:`selectByPluginId(pluginId)`、`upsert(entity)`、`deleteByPluginIdAndKey(pluginId, key)`、`deleteByPluginId(pluginId)`。

**设置清理时机:仅显式卸载删数据。**`PluginLoader.uninstallPlugin()` 成功删除 JAR 后调用 `deleteByPluginId`;热重载的 `removePlugin` **不**删(重载后设置保留)。

## 5. 预览侧集成

### 5.1 classloader 统一

- `ChildFirstResourceClassLoader` 从 `fan.summer.plugin`(SwissKit)**原样移动**到 `fan.summer.api.loader`(SwissKitJ-Api)。宿主内部类、无第三方引用,不留兼容别名;`PluginLoader` 改 import。API 模块日志改用 `fan.summer.api.log.LoggerFactory`(该类现用 SLF4J,移动时同步替换,保持 API 模块无 SLF4J 硬依赖)。
- `PluginPreviewWindow.launch()` 的 `new URLClassLoader(...)` 换成 `new ChildFirstResourceClassLoader(...)`。

### 5.2 与真实宿主对齐的加载语义

`launch()` 中,对每个加载出的插件:

1. `withJar` 路径(classLoader != null):`PluginContext.register(plugin, classLoader)`;`withPlugin` 路径不注册(回退到插件自身 classloader,行为等价)。
2. 构造 `PreviewPluginHost` 并 `PluginContext.runWith(p, () -> p.init(host))`(try-catch 容错,同宿主)。
3. `PreviewShell` 创建插件视图处改为 `PluginContext.callWith(plugin, plugin::createView)` + `PluginContext.wrapEvents(plugin, view)`(当前直接调用,与宿主不一致——本条为修复)。
4. 窗口关闭回调中:各插件 `tasks().cancelAll()`,再关 classloader(现有逻辑)。

### 5.3 预览态设置存储

`PropertiesPluginSettings`(preview 包,包私有):

- 文件:`System.getProperty("user.home")/.swisskit/preview-settings/<sanitized-plugin-id>.properties`;插件 ID 消毒规则 `[^a-zA-Z0-9._-] → _`。
- 读:构造时加载文件进内存 `Properties`(文件不存在则空)。
- 写:write-through——更新内存后同步 `store()` 回文件(预览场景低频写,无需异步);IO 失败记 warn 日志,内存值保留。
- 目录不存在时自动 `mkdirs`。

## 6. 错误处理与线程契约汇总

| 场景 | 行为 |
|---|---|
| `init()` 抛异常 | 记 warn 日志,插件照常加载,PluginHost 仍已绑定 |
| TaskRunner 任务体抛异常 | 有 onError → FX 线程回调;无回调 → error 日志 |
| TaskRunner 任务被 cancel | 中断线程;`InterruptedException` 路由 onError |
| H2 设置写失败 | error 日志,缓存保留(读一致性不破坏) |
| properties 设置写失败 | warn 日志,内存值保留 |
| `PluginSettings.get/put(null key)` | NPE(`Objects.requireNonNull`,快速失败) |
| `put(key, null)` | 等价 `remove(key)` |
| 回调线程 | onSuccess/onError 一律 FX 线程(经可注入 executor,默认 `Platform::runLater`) |
| `init()` 调用线程 | FX 线程(addPlugins 契约),TCCL 已设为插件 classloader |

## 7. 测试策略

| 单元 | 测试点 | 位置 |
|---|---|---|
| `SimpleTaskRunner` | 计数增减、cancel 中断路由 onError、回调经注入 executor、cancelAll | `SwissKitJ-Api/src/test/.../host/SimpleTaskRunnerTest.java`(注入同步 executor,无 FX 依赖) |
| `PropertiesPluginSettings` | put/get/remove/持久化重载、ID 消毒、null 语义 | `SwissKitJ-Api/src/test/.../preview/`(临时目录) |
| `BasePluginHost` | pluginId/logger/i18n registerBundle 用对 classloader | API 模块测试 |
| `PluginRegistry` 注入 | init 恰好一次、init 抛异常不阻断、isBusy 合并逻辑、removePlugin cancelAll | `SwissKit/src/test/.../PluginRegistryHostTest.java`(hostFactory 测试缝注入 fake) |
| `H2PluginSettings` / mapper | upsert/select/delete 往返 | SwissKit 测试(临时 user.dir 的 H2,复用 DatabaseInit 模式;若环境不允许则至少 mapper XML 语法经启动冒烟覆盖) |

## 8. 文档与迁移

- 新增 `docs/plugins/plugin-host.md` + `docs/zh/plugins/plugin-host.md`:PluginHost 全 API、init 时序、TaskRunner 与后台状态点的关系、设置命名空间。
- `docs/migration-3.2.md`(修复计划 Task 10 创建)增补「New: PluginHost / PluginSettings / TaskRunner」一节。
- `docs/plugins/pitfalls.md`:i18n classloader 坑的条目改为推荐 `host.i18n().registerBundle(...)`。
- `CLAUDE.md`:Plugin Development 一节补 init(PluginHost) 与 aiTools 的时序说明。

## 9. 兼容性

- 全部为 API **新增**(接口新增 default 方法 + 新类型),二进制/源码向后兼容;旧插件零改动运行。
- `ChildFirstResourceClassLoader` 移动仅影响宿主内部 import,不影响任何第三方。
- `@since 3.2.0` 标注所有新公共类型与方法。

## 10. 实施顺序依赖

1. 本规格依赖修复计划(`docs/superpowers/plans/2026-07-02-v3.2-consistency-fixes.md`)**Task 8(SkNotification)先行**——`NotificationFacade` 引用新类名。
2. 其余与修复计划无交叉,可并行。
3. 建议顺序:API 接口层 → SimpleTaskRunner/BasePluginHost → classloader 下沉 → 宿主实现与注入 → 预览实现 → 文档。
