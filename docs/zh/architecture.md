# 架构说明

SwissKitJ 是一个基于 JavaFX 21（JDK 21）构建的模块化、插件化桌面工具集。
宿主应用只依赖一个很小的插件接口；每一个具体工具——无论是内置的还是作为外部 JAR 发布的——
都实现同一个 `SwissKitJPlugin` 契约。

```
┌──────────────────────────────────────────────────────────────────┐
│  外部 JAR 插件（独立仓库，provided 依赖）                          │
│  实现 SwissKitJPlugin，放入 .swisskit/plugin/                     │
└───────────────────────────────┬──────────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│  SwissKitJ-Api  —  公共契约层（无业务逻辑）                        │
│  SwissKitJPlugin · ToolCategory/Type/IconStyle · PluginContext     │
│  AiService/AiTool/AiChatMessage · I18n · Themes · LoggerFactory    │
│  StepWizard · GlassNotification · UiUtils · preview 组件           │
└───────────────────────────────▲──────────────────────────────────┘
                                │ 打包进胖 JAR（provided → runtime）
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│  SwissKit  —  JavaFX 应用壳 + 内置工具                             │
│  UI 外壳 · 插件层（Loader/Registry/Context/Favorites）             │
│  AI 子系统（2 个后端 · 工具 · 本地推理）                            │
│  H2 + MyBatis · i18n · Logback · JsonHelper                        │
└──────────────────────────────────────────────────────────────────┘
```

## 模块结构

| 模块 | 用途 |
|------|------|
| `SwissKitJ-Api` | 共享插件接口（`SwissKitJPlugin`）、插件上下文与隔离（`PluginContext`）、可复用组件（`StepWizard`、`GlassNotification`、`UiUtils`）、主题、i18n、日志 API，以及 AI 服务契约（`ChatBackend`、`AiTool`、消息 record） |
| `SwissKit` | JavaFX 应用壳 —— UI、插件加载、收藏、AI 子系统，以及全部内置工具 |

官方插件位于[单独的仓库](https://github.com/MuskStark/SwissKiJ-Plugin)。它们独立构建，在运行时作为 JAR 放入 `.swisskit/plugin/` 目录。所有插件将 `SwissKitJ-Api` 声明为 `provided` 依赖；主应用通过胖 JAR 在运行时提供它。

## 启动序列

`fan.summer.Launcher`（胖 JAR 清单入口点）先准备好日志目录，再委托给
`fan.summer.app.SwissKitJApp`（JavaFX `Application`）。Launcher 是一个独立的、非 `Application` 的类，
这样应用能以 classpath 模式运行（兼容胖 JAR 布局与 JavaFX 模块系统）。

在 `SwissKitJApp.start()` 中：

1. 安装插件日志 binder（`LoggerBinder.bind`），使插件日志路由进共享的 SLF4J/Logback 主干
2. 通过 MyBatis 初始化 H2 数据库（`DatabaseInit.init`）
3. 注册 i18n bundle 并应用已保存的语言偏好
4. 解析插件目录（`<user.dir>/.swisskit/plugin/`）
5. 创建 `PluginLoader` + `PluginRegistry`（注册表将自己绑定到 loader）
6. 创建 `FavoriteService`（从数据库加载收藏的插件 ID）
7. 通过 `BuiltinToolRegistrar` 注册内置工具 —— 列表会经 `PluginRegistry.addPlugins` 统一注册，同时把每个插件的 `aiTools()` 自动注册到 `AiServiceProvider`
8. 若已保存的模式为 `openai`/`anthropic`，则初始化云端 AI 后端；**local 模式延迟到首次打开 AI 工具时再初始化**
9. 构建并显示 `MainWindow`
10. 挂载 `WindowResizeHelper` 实现边缘/角落拖拽缩放
11. 启动 `PluginLoader`（扫描插件目录并监听变化）

> 自 v3.1.0 起不再有独立的 AI 工具注册步骤。插件通过 `SwissKitJPlugin.aiTools()`
> 自带其 AI 工具，注册表在插件加入时自动注册、在插件移除（含热重载）时自动注销。
> 旧的 `BuiltinAiToolRegistrar` 类已被删除。

## UI 结构

| 组件 | 职责 |
|------|------|
| `MainWindow` | 根 `StackPane`；拥有 `TitleBar`、`Sidebar`、`ContentArea`、状态栏；组合动画背景光球 |
| `Sidebar` | 带搜索栏的分类导航；分类：全部 / 文本 / 图片 / 开发者 / 网络 / 其他 / 收藏 |
| `ContentArea` | 显示 `ToolCard` 网格或活动工具视图；管理 `DetailPanel` 覆盖层与返回栏 |
| `DetailPanel` | 滑入面板，显示插件元数据，带启动、收藏切换、卸载（仅外部插件）按钮 |
| `TitleBar` | 自定义窗口装饰（窗口为 `StageStyle.TRANSPARENT`） |

### 导航流程

点击 `ToolCard` → `DetailPanel.show()` → 点击启动 → `registry.activate(plugin)` +
`contentArea.showPage(plugin.createView(), title)`。返回的 `Node` 只创建一次并被 `MainWindow` 缓存。
返回栏在返回时调用 `registry.deactivate()`；若插件报告有正在运行的后台任务，则将其移入后台集合，
而非完全 deactivate。

## 插件系统

### 接口

```java
public interface SwissKitJPlugin {
    String getId();                       // 反向域名 ID
    String getName();
    String getDescription();
    ToolCategory getCategory();           // DEV / TEXT / IMAGE / NET / OTHER
    String getVersion();
    String getMdiIcon();                  // Material Design 图标名，如 "file-excel"
    default IconStyle getIconStyle() { return IconStyle.BLUE; }   // 映射到 ic-* CSS 类
    default ToolType getType()     { return ToolType.PLUGIN; }    // PLUGIN / BUILTIN

    Node createView();                    // 只调用一次；结果被缓存并复用
    default void onActivate()   {}
    default void onDeactivate() {}
    default void onUnload()     {}

    // 后台任务生命周期（默认为空操作）：
    default boolean hasRunningTasks() { return false; }
    default void onBackground() {}      // 有运行中任务时进入后台
    default void onForeground() {}      // 从后台恢复
}
```

`ToolCategory`、`ToolType`、`IconStyle` 都是枚举（每个值带一个用于序列化与样式的小写
`id`/CSS 类）。`createView()` 每个插件只调用一次；宿主缓存返回的 `Node`。

### 注册方式

- **内置工具**：通过 `BuiltinToolRegistrar` 直接注册（共 10 个：AI 聊天、
  JSON/Base64/Hash 开发者工具、Excel 拆分、颜色转换、Markdown 编辑器、邮件、
  邮件归档、PDF）。不涉及 SPI。
- **外部插件**：实现 `SwissKitJPlugin`，在 `META-INF/services/fan.summer.api.SwissKitJPlugin`
  中声明，并将 JAR 放入 `.swisskit/plugin/`。通过目录监听器支持热重载。

### 插件加载与类隔离

每个外部 JAR 由专用的 `ChildFirstResourceClassLoader` 加载，它是一个
`URLClassLoader`：**资源子优先**，但**类加载仍是父优先**：

- *资源*（`mybatis-config.xml`、`init.sql`、`mapper/*.xml`、i18n bundle）优先取自插件 JAR，
  因此同名宿主资源不会遮蔽插件自带的资源。
- *类*仍是父优先解析，因此 `SwissKitJPlugin` 等共享 API 类型解析到与宿主相同的 `Class`
  对象——保证 `ServiceLoader`、强制转换和 `instanceof` 正常工作。

在打开 `URLClassLoader` 之前，加载器先把 JAR 复制到一个临时文件。`ClassLoader`
绑定到该临时副本，因此**原始 JAR 永远不会被文件锁占用**（在 Windows 上尤为关键），
卸载时可立即删除。

热重载经过去抖（1.5 秒），并在一个专用调度线程上执行，使密集的
`ENTRY_MODIFY` 事件合并，且监听线程永远不会被阻塞。启动时的 `cleanupStaleTempCopies()`
会清理上次崩溃残留的临时 JAR。`uninstallPlugin(plugin)` 会卸载 JAR 并删除原始文件。

### 插件上下文（线程上下文 ClassLoader）

每个已加载的插件会注册到 `fan.summer.api.PluginContext`，它将插件实例与其
`ClassLoader` 关联（通过 `WeakReference` 持有，因此即便漏掉 `unregister()` 也能被 GC）。
宿主对插件的每一次调用都会做包装，确保正确的 ClassLoader 位于线程上下文 ClassLoader（TCCL）上：

```java
Node view = PluginContext.callWith(plugin, plugin::createView);   // 设置并恢复 TCCL
PluginContext.wrapEvents(plugin, view);                           // 包装 EventDispatcher
```

`wrapEvents` 替换节点的 `EventDispatcher`，使从事件处理器派生的后台线程继承正确的 TCCL
——让插件代码无需任何 ClassLoader 意识即可使用 `ServiceLoader`、MyBatis 和资源 bundle 查找。
生命周期回调（`onUnload`）同样通过 `PluginContext.runWith` 调用。

### 收藏

`FavoriteService` 在内存中持有收藏插件 ID 的 `ObservableSet<String>`，在启动时从
`plugin_favorites` 表加载。所有变更（`toggle`/`add`/`remove`）立即持久化到数据库，并触发
变更回调（在 JavaFX 线程上）。UI 组件可响应式地观察该集合。它是单例
（`FavoriteService.getInstance()`）。

### 插件日志

插件使用 `fan.summer.api.log.LoggerFactory`：宿主运行时路由到 SLF4J/Logback
（滚动文件位于 `.swisskit/logs/swisskit.log`，按天轮转，保留 7 天），测试中返回静默空操作日志器。
使用 SLF4J 风格的 `{}` 占位符。

## AI 子系统

### 服务抽象

`ChatBackend`（`SwissKitJ-Api`）是推理契约：`loadModel`/`unloadModel`/`isReady`、
流式 `chat(history, callback)`、生成控制（`cancelGeneration`/`isGenerating`）。
工具注册改为全局 —— 通过 `AiServiceProvider` 暴露，后端接口本身不再含工具方法。
`AiServiceProvider` 是持有活跃后端、当前模式标签、状态变更监听器和全局工具注册表的
**静态单例**。模式切换通过 `switchMode(mode, service)` 完成：先卸载旧后端，再装入新后端并通知监听器。

共有两个后端，都实现 `ChatBackend` 接口：

| 后端 | 时机 | 说明 |
|------|------|------|
| `LocalChatBackend` | local 模式 | GGUF 推理。native 路径在**子 JVM 进程**（`NativeWorkerClient`）中运行，使 native 崩溃不会杀死宿主；崩溃 ≤3 次自动重启，≥3 次降级到纯 Java 引擎。通过 `ai.local.backend` 配置为 `java` 或 `native`。**懒加载**：首次打开 AI 工具时才加载。 |
| `CloudChatBackend` | openai / anthropic 模式 | 统一的云端后端，覆盖两个 provider。通过 `CloudChatBackend.openAi(...)` 或 `.anthropic(...)` 静态工厂构造。底层基于 LangChain4j 的 `OpenAiStreamingChatModel` / `AnthropicStreamingChatModel`。HTTP/SSE、工具循环、流桥接全部委托给 LangChain4j；宿主只负责 `AiChatMessage`↔LC4j 消息映射和多轮工具循环驱动。 |

AI 设置通过 `AiConfigService`（直接读数据库，无 UI 依赖）读取，因此启动路径与 AI 服务
永远不依赖设置 UI 类。

### 工具调用

模型可在生成过程中调用工具。每个 `AiTool` 声明名称、描述、参数列表和一个
`execute(Map) → AiToolResult`。`ToolExecutor` 分发调用并把结果喂回会话历史；
多轮循环在每个后端中以上限 `MAX_TOOL_ROUNDS = 5` 约束。Schema 生成分为两路：云端后端
（OpenAI / Anthropic）用 `AiToolToToolSpecification` 构造 LangChain4j 的
`ToolSpecification`（结构化地透传给 API），本地模式用 `ToolSchemaBuilder` 把工具定义作为
markdown 段落注入系统提示词。本地模型以文本形式发出工具调用，由 `ToolCallParser` 解析
（Qwen 分隔符模式与通用 JSON 模式）。回调（`onToken`、`onToolCall`、`onToolResult`、
`onComplete`、`onError`）始终在 JavaFX Application Thread 上投递。

### 斜杠命令

`SlashCommandHandler` 让 AI 聊天运行一次引导式、单工具的调用：它调用
`AiServiceProvider.setConstrainedTool(name)`，使 `getTools()` **只返回该工具**，
让小模型专注于用户指定的那个工具。该约束是一个全局 `volatile` 字段
（推理在虚拟线程上运行，不继承线程局部状态），并且**必须在** `onComplete`/`onError`
回调中清除。

### 浏览器自动化规划器（重要不变量）

`browser_automate` 工具使用配置的 AI 服务作为规划器，运行 观察→思考→行动 循环。
规划器调用**刻意绕过 `AiService.chat()`**，改为发起一次直接、不带工具的 HTTP 请求
（`SynchronousChatHelper`）。如果走正常 chat 路径，规划器会把 `browser_automate` 自身
视为已注册的工具并递归调用。由于这种直接调用方式，规划器当前**仅支持 OpenAI 兼容后端**。

## CSS 主题

三层玻璃态深色主题：

| 文件 | 模块 | 作用域 |
|------|------|--------|
| `css/swisskit-common.css` | `SwissKitJ-Api` | 共享变量、滚动条、进度条、`.glass-*` 工具类、`.section-title`/`.section-header` |
| `css/shell.css` | `SwissKit` | 应用外壳 —— 标题栏、侧边栏、搜索栏、工具卡片、详情面板、状态栏、`.ic-*` 图标类 |
| `css/builtin.css` | `SwissKit` | 内置工具样式 |

嵌入主 Scene 的插件通过场景图传播自动继承全部三个样式表。打开独立 `Stage`/`Scene`
的插件应调用 `Themes.applyTo(scene)`。

侧边栏图标使用内嵌的 Material Design 图标 webfont（`mdi-codemap.properties` +
`MdiIconUtil`），使字形在各平台（尤其是 Windows）上保持一致渲染。

## 数据库

H2 文件数据库位于工作目录下的 `.swisskit/swisskit.db`（`AUTO_SERVER` 模式）。
Schema 从 `init.sql` 初始化，通过 MyBatis 访问，XML mapper 位于
`src/main/resources/mapper/`。主要表：

| 表 | 用途 |
|----|------|
| `app_setting` | 键值存储（语言、AI 配置、模型路径……） |
| `plugin_manager` | 已安装的外部插件（版本、禁用标志、更新 URL） |
| `plugin_favorites` | 收藏的插件 ID |
| `complex_split_config` | Excel 复杂拆分任务配置 |
| `swiss_kit_setting_email` | 邮件发送/IMAP 账号设置 |
| `email_address_book` / `email_tag` | 通讯录与标签 |
| `email_mass_sent_config` / `email_sent_log` | 群发配置与审计日志 |
| `email_archive` | 已归档的收件 |
| `menu_order` | 拖拽排序 |

`DatabaseInit` 在启动时通过 `Properties` 占位符把动态数据库 URL 注入 `mybatis-config.xml`。

## 构建

```bash
mvn install -f SwissKitJ-Api/pom.xml -DskipTests
mvn clean package -f SwissKit/pom.xml -DskipTests
java -jar SwissKit/target/SwissKitJ-3.1.0.jar
```

胖 JAR 由 `maven-shade-plugin` 构建（主类 `fan.summer.Launcher`），并捆绑所有平台的
JavaFX 原生库（`.dll`/`.so`/`.dylib`）。在 Windows 上 `windows-exe` profile 会自动激活，
并通过 Launch4j 额外产出 `SwissKit.exe`。三个 POM 都是**独立**的（无 parent）；
跨平台发布构建由 GitHub Actions 处理。

## 关键不变量

后续改造不可破坏以下契约：

- **`SwissKitJPlugin` 是与外部插件仓库之间的 ABI 边界** —— 新增方法必须带 `default` 实现。
- **`SwissKitJ-Api` 保持无业务依赖**（仅 `javafx`，`provided`）；宿主类不得泄露进 API 模块。
- **对插件的每一次调用都经过 `PluginContext`**（`callWith`/`runWith`，`createView` 后再加
  `wrapEvents`），使插件库能定位到自己的资源。
- **类加载保持父优先**，即便资源加载是子优先 —— 若让类也子优先，会复制出两份
  `SwissKitJPlugin` 并破坏 SPI/强制转换。
- **AI 流式回调在 JavaFX Application Thread 上投递。**
- **浏览器规划器必须绕过 `AiService.chat()`**，以避免递归式工具调用。
- **已设置的 `constrainedTool` 必须始终在完成/错误回调中清除。**
