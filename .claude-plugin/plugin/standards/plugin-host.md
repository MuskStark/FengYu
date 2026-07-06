# PluginHost — 宿主门面(v3.2.0+)

宿主在插件加载时注入一个 `PluginHost`,插件经它访问全部宿主能力——不再需要
记忆分散的静态入口。旧插件不实现 `init()` 也完全兼容;静态入口继续可用,
`PluginHost` 是推荐路径。

## 获取方式

```java
public class MyPlugin implements ZhiFlowPlugin {

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
