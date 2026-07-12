# 更新日志

FengYu 的所有重要变更。格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)。

---

## [3.1.0] — LangChain4j + ChatBackend 统一

**v3.1.0** — 2026-06-21

本版本在 LangChain4j 上重建 AI 子系统，并把两个云端 provider（OpenAI + Anthropic）合并为单个 `CloudChatBackend` 类，对外暴露新的 `ChatBackend` 接口。本地模式（进程内 GGUF）改名为 `LocalChatBackend`，行为不变。

### ⚠️ 破坏性变更

- **`AiService` 接口删除** —— 替换为 `ChatBackend` 接口。外部插件调用 `AiServiceProvider.getService()` 需把返回类型从 `AiService` 改成 `ChatBackend`。详见 [迁移指南](migration-3.1.md)。
- **`OpenAiService` 和 `AnthropicService` 具体类删除** —— 替换为单个 `CloudChatBackend` 类，通过 `openAi(...)` / `anthropic(...)` 静态工厂构造。
- **`CloudAiConfigProvider` 和独立的 `StreamingResponseHandlerBridge` 删除** —— 逻辑整合进 `CloudChatBackend`。
- **`AiServiceImpl` 改名为 `LocalChatBackend`** —— 纯改名，行为不变。

### ♻️ 变更

- **统一的 `ChatBackend` 接口** 位于 `FengYu-Api` —— 非密封（Java 禁止跨模块密封许可）；两个已知实现（`CloudChatBackend`、`LocalChatBackend`）。UI 使用 `instanceof` 区分后端。
- **`CloudChatBackend` 合并 OpenAI + Anthropic** 到一个类（约 450 行）。HTTP/SSE、工具循环逻辑、流式桥接全部委托给 LangChain4j 的 `OpenAiStreamingChatModel` / `AnthropicStreamingChatModel`。Provider 差异隔离在内部的 `buildStreamingModel(...)` switch 上（基于 `Provider` 枚举）。
- `SynchronousChatHelper`（浏览器规划器）重写，直接通过 `CloudChatBackend` 配置访问器使用 LC4j 同步 `OpenAiChatModel`。
- `AiServiceProvider` 全面暴露 `ChatBackend`，不再暴露 `AiService`。方法名不变。
- 采样参数（temperature / topP / maxTokens）按调用生效，不再缓存到模型实例里。

### ✨ 新增

- 新的 `ChatBackend` 接口
- 新的 `CloudChatBackend` 类，带 `openAi(...)` / `anthropic(...)` 工厂
- `LocalChatBackend`（从 `AiServiceImpl` 改名）
- `AiToolCall.of(id, name, arguments)` 重载，用于在 LangChain4j 桥接时保留服务端签发的工具调用 ID
- `CloudChatBackendTest`（11 个测试）+ `ChatMessageMapper` / `AiToolToToolSpecification` 的适配器测试
- 迁移指南 [migration-3.1.md](migration-3.1.md)（中英文）

### 🐛 修复

- **macOS 上 `testConnection()` 空消息 bug**：在 macOS JDK 上，无法连接的端点抛出的 `ConnectException` 携带 `null` 消息，导致 `testConnection()` 返回 `null`（被设置 UI 误判为成功）。现在回退到 `e.getClass().getSimpleName() + ": " + e`。
- **Anthropic 多轮工具调用**：服务端签发的 `tool_use_id` 现在在 `AiToolCall → LangChain4j → AiToolCall` 往返中保留 —— 之前用本地伪造 ID 导致第 2 轮工具调用 HTTP 400。
- **多轮对话上下文连续性**：assistant 的最终回复现在在 service 返回前追加到 `history`。
- **OpenAI 工具轮消息顺序**：`ToolExecutor.executeAndFeed` 之前先追加 assistant-with-tools 消息，满足 API 约束（`tool` 消息必须跟在带 `tool_calls` 的 `assistant` 后面）。
- `testConnection()` 的 `HttpClient` 改为 try-with-resources（Java 21 `AutoCloseable`）。
- 加固云端流处理器的线程安全（`StringBuffer` 累加器、`volatile` 字段）。

### ⬆️ 依赖

- `dev.langchain4j:langchain4j-open-ai:1.2.0`
- `dev.langchain4j:langchain4j-anthropic:1.2.0`
- （最初锁了 1.0.1，但 `langchain4j-anthropic` 从未在该版本发布；升到两个模块同时存在的最低 GA 版本）

### ⚠️ 已知行为变化

- 云端后端的 `cancelGeneration()` 现在是尽力而为（LangChain4j 1.x 不暴露流式模型的中途取消）；进行中标志仍会清除。本地模式不受影响。
- 中途 SSE 错误现在通过 JavaFX Application Thread 上的 `callback.onError` 上报，与本地模式行为一致。

### 📉 净代码变化

- 删除：约 1000 行（5 个废弃类 + 1 个废弃测试）
- 新增：约 1100 行（新统一类型 + 测试 + 迁移指南）
- 净：行数基本持平，但云端代码从两个并行实现合并成一个统一类。

---

## [3.0.1] — FunctionGemma 离线适配

**v3.0.1** — 2026-06-21

### ✨ 新增

- **FunctionGemma 多轮工具循环**：为 FunctionGemma-270m-it 本地模型实现宿主驱动的 `analyze → configure → execute` 循环；调用轮次期间抑制工具调用 token，仅将最终回复转发至 UI
- **离线中→英关键词归一化**：`OfflineNlNormalizer` 在本地模型解析前将中文工具名关键词重写为英文，无需联网（基于资源文件 `nl-normalizer.properties`）
- **工具参数 enum 约束**：`AiToolParam` 新增 `enumValues` 字段；工具声明现在向 FunctionGemma、OpenAI、Anthropic 三种后端输出 `enum:[...]` 约束 —— 显著提升小模型取参可靠性
- 增强 Excel AI 工具描述，为 `mode`/`action` 参数添加 enum 约束

### 🐛 修复

- 加固 `FunctionGemmaAdapter` 解析器：使用 🪙（U+1FA99）字符串分隔符，正确处理值中含逗号、花括号以及单次响应中的多次工具调用
- 卸载时尽力 `unmap` 释放 `GGUFModel` 的 mmap
- 加固 `GGUFReader` 对损坏或截断模型文件的容错
- 将 `PluginLoader` JAR 加载/卸载串行化到单线程调度器
- 修复取消发生在 prefill 阶段时 `LlamaRunner` 不能干净结束生成的问题
- 将 `TokenBatcher` 的刷新移出 FX 线程
- 强杀前让 native AI worker 优雅退出
- 修复 `ExcelUtil` 中即使复制/写入抛出也关闭目标 POI `Workbook`
- 低优先级稳定性清理（MDI 字体日志、守护线程 UI）

---

## [3.0.0] — JavaFX 迁移

**v3.0.0** — 2026-06-12

- 更新 v3.0.0 发布图标
- 解决代码库静态分析警告（Qodana）

**v3.0.0-rc.3** — 2026-06-10

### ✨ 新功能
- **斜杠命令**：在 AI 聊天中输入 `/` 列出可用工具、查看特定工具帮助，或直接调用工具而无需模型推理——支持直接执行和引导式模型参数提取
- **插件资源隔离**：外部插件使用子优先 `ClassLoader`，确保插件资源优先从插件 JAR 解析；`PluginContext` 在每次插件生命周期调用和事件分发时提供线程上下文 ClassLoader 切换
- **插件商店重设计**：在线插件商店改为可搜索、可筛选的卡片网格，带安装状态指示和版本比较
- **AI 配置服务**：提取 `AiConfigService` 集中管理 AI 配置访问，解耦与 UI 设置代码的依赖
- **邮件归档**：新增 `email_archive` 表、实体类和 Mapper，用于邮件归档存储

### 🔧 修复
- 修复 Windows 上侧边栏图标不显示——从 JavaFX `Font` 图标切换为 MDI 网页字体
- 修复邮件设置保存始终失败；现在显示缺失的必填字段名
- 修复 Excel 复杂拆分第三阶段损坏预先存在的输出文件——仅合并拆分操作期间创建的文件
- 修复 POI 跨工作簿单元格样式克隆时数据格式字符串为 null 导致 `NullPointerException`
- Excel 拆分器进度回调增加空值保护

### ♻️ 变更
- 从 `OnlineStorePane` 提取 `StorePlugin` 和 `StorePluginLogic`，并添加单元测试
- 新增 GPLv3 许可证文件
- 为 `FengYu` 模块添加 JUnit 5 测试依赖

---

**v3.0.0-rc.2** — 2026-06-05

### ✨ 新功能
- **工具收藏**：通过工具卡片或详情面板的星标切换收藏工具；收藏数据通过 H2 数据库持久化，可从侧边栏"收藏"分类筛选
- **AI 后端延迟加载**：本地 AI 后端（原生/Java）初始化推迟到首次打开 AI 工具时，提升启动性能；AI 设置中新增 Java/Native 推理引擎切换
- **插件卸载**：从详情面板卸载外部插件，带确认对话框；关闭 ClassLoader、删除 JAR 文件并从注册表清理
- **安装成功通知**：从在线商店或本地 JAR 安装插件后显示成功 Toast 通知
- **令牌批处理**：AI 输出令牌以 50ms 间隔批量刷新，减少高速生成时的 FX 线程压力

### 🔧 修复
- **崩溃速率限制**：原生工作进程自动重启遵循时间窗口（5 分钟内 3 次崩溃），防止重启风暴
- **设置缓存**：应用设置缓存在内存中，防抖写入数据库（300ms），减少快速 UI 交互时的数据库负载
- 修复加固 Linux 发行版（UOS/Deepin/Kylin）上原生库加载失败（未签名的 `.so` 文件抛出 `SecurityException`）
- 修复邮件群发时共享收件人列表被意外修改
- 修复在线商店插件目录解析——用手写的字符串切片替换为基于 Gson 的 `JsonHelper`
- 修复 `WindowResizeHelper` 重复挂载导致事件过滤器重复
- 线程安全加固：`PluginLoader`、`PluginRegistry` 和 `MainWindow` 使用 `ConcurrentHashMap`、`volatile`、`synchronizedSet`
- 工具卡片入场动画设置交错上限（最多 30 个），避免创建数百个 `PauseTransition` 实例
- 修复 Windows 上插件 JAR 删除失败——增加重试机制（含 `System.gc()` 提示），文件仍被锁定时降级为 `deleteOnExit()`
- 修复卸载插件 JAR 时未触发 `onUnload()` 生命周期回调
- 修复卸载非活跃插件时缓存视图未清除，导致插件类无法被 GC 回收
- 修复中文系统下切换英语界面仍返回中文——`ResourceBundle` 不再回退到 JVM 默认 locale
- 修复 Windows 无 JRE 发行包冗余包含 fat JAR（Launch4j exe 已内嵌该 JAR）

---

**v3.0.0-rc.1** — 2026-06-04

- **浏览器自动化**：AI 可调用的 `browser_automate` 工具，通过自然语言自动化 Web 浏览器；使用 Playwright 驱动系统已安装的 Chrome/Edge/Chromium（无需额外下载浏览器）；观察-思考-行动循环，包含页面 DOM 快照、CSS 选择器定位和规划器 LLM
- **窗口自由调整大小**：为无装饰的 `StageStyle.TRANSPARENT` 窗口添加边缘和角落拖拽缩放（`WindowResizeHelper`）；使用屏幕坐标确保 macOS 兼容性
- **响应式布局**：`FlowPane` 换行长度动态绑定到视口宽度；`windowPane` 和 `ContentArea` 通过 `setMaxWidth/Height(Double.MAX_VALUE)` 正确填充父容器
- **纯 Java PDF 转 DOCX**：`PdfBoxToDocxConverter` 使用 PDFBox 提取内容、Apache POI 生成 DOCX —— 无需安装外部 Office 软件；三级页面处理策略（文本 → 提取图片 → 全页渲染回退）
- **原生后端健康追踪**：`NativeLoader.FailureReason` 枚举提供结构化故障诊断；原生加速不可用时在 AI 聊天中显示降级模式横幅
- 修复 macOS 上窗口缩放不生效（`StageStyle.TRANSPARENT` 下 `stage.isMaximized()` 返回值不可靠）
- 修复工具卡片网格不随窗口宽度自适应
- 修复 Playwright 运行时尝试下载浏览器驱动
- 修复 AI 浏览器规划器通过工具注入循环递归调用 `browser_automate`

---

**v3.0.0-beta.2** — 2026-05-26

### ✨ 新功能

- **AI 远程后端**：通过 AI 设置中的全局选择器，支持在本地 GGUF、OpenAI Chat Completions 和 Anthropic Messages API 之间切换；所有后端均支持 SSE 流式响应（逐令牌输出）和工具调用
- **AI 原生工作进程**：通过 `NativeWorkerClient`/`NativeWorkerMain` 子进程实现进程外 JNI 推理，提供崩溃隔离和线程安全；`GenerateCallback.onDone` 新增统计回调重载
- **FunctionGemma 适配器**：为 FunctionGemma 模型实现原生工具调用协议适配器，包含自定义停止序列和 `AiServiceImpl` 中的单轮工具循环集成
- **AI 内置工具**：Base64 编解码、哈希计算（MD5/SHA-256/SHA-512）、JSON 格式化/验证和颜色转换（HEX/RGB/HSL）—— 全部通过 `BuiltinAiToolRegistrar` 注册，配合 `ToolExecutor` 和 `ToolSchemaBuilder` 工具
- **AI Markdown 渲染**：AI 响应通过 `WebView` 渲染 Markdown，使用深色主题（#1e1e2e）并自动调整高度以适应内容
- **AI→Excel 工具**：分析、配置、执行、取消、查询和复杂配置 AI 工具，使 AI 聊天可以操作 Excel 拆分器；包括拖放/选择文件时自动分析及取消支持
- **AI 自动初始化**：配置的 AI 后端（包括远程 API 模式）在启动时自动激活，无需手动重新配置
- **PDF 工具**：支持拆分、合并和转 Word（通过 WPS 或 documents4j），配备 `OfficeDetector` 自动检测；3 标签页 UI 注册为内置工具；所有三项 PDF 操作均有 AI 工具支持
- **邮件归档**：内置邮件归档工具，支持 IMAP（`EmailArchivePlugin`、`EmailArchiveService`），新增地址簿面板并扩展群发服务
- **插件后台执行**：插件可在后台运行任务，支持视图缓存和 ToolCard 运行状态指示器
- **插件预览窗口**：为第三方插件开发者提供的独立预览套件，包含 `PreviewTitleBar`、`PreviewSidebar`、`PreviewToolCard`、`PreviewDetailPanel` 和 `fengyu-preview.css`
- **玻璃通知组件**：玻璃拟态风格的通知组件，替换所有 `Alert` 弹窗
- **应用图标**：为 macOS（.icns）、Windows（.ico）和 Linux（.png）提供原生分辨率的应用图标
- **内置工具**：Base64 编解码器、哈希计算器、JSON 格式化器、颜色转换器和 Markdown 编辑器插件，全部注册为内置工具
- **国际化框架**：FengYu-Api 中的核心 `I18n` 类，支持数据库持久化语言设置、插件资源包注册/注销，以及所有 UI 组件（TitleBar、MainWindow、Sidebar、ContentArea、ToolCard、DetailPanel、Settings）的实时语言切换
- **设置界面**：重新设计的设置页面，包含 AI、邮件和地址簿标签页
- **三层 CSS 架构**：`fengyu-common.css`（共享变量和 glass-* 工具类）、`shell.css`（应用外壳）和 `builtin.css`（内置工具），支持场景图继承
- **类型安全枚举**：FengYu-Api 中 `ToolCategory`、`ToolType` 和 `IconStyle` 枚举替代基于字符串的元数据
- **GGUFZ 支持**：模型文件选择器支持 `*.ggufz` 压缩模型文件
- **Gson/JsonHelper**：`JsonHelper` 工具类（基于 Gson）替代 `JsonBuilder`/`JsonParser`；`ToolCallParser` 和所有服务均使用 Gson
- **双语文档**：中英文文档，配合 docsify-flexible-i18n；所有公共 API 提供完整的英文 Javadoc

### 🔧 修复

- 修复 AI 后端在重启后不生效的问题 — `MainWindow` 中的残留初始化覆盖了已配置的服务
- 修复 `NativeWorkerClient` 线程安全问题，修正崩溃计数器在成功生成时而非模型加载时重置
- 修复插件 i18n 资源包因 ClassLoader 父级委托返回主机翻译的问题
- 修复 ToolCard 后台指示器不显示及预览 i18n 不工作的问题
- 修复 ExcelSplitterPlugin 缺少 `hasRunningTasks` 实现的问题
- 修复 AI 消息气泡中 WebView 白色背景 —— 使用深色主题 #1e1e2e 并添加圆角
- 修复 AI 消息气泡高度 —— WebView 自动调整高度以适应内容，替代过大的默认尺寸
- 修复工具参数中 JSON Schema 数组类型的处理
- 修复邮件标签页字段行中 VBox→HBox 类型不匹配
- 修复邮件编辑器 —— 扩展 WebView 高度并允许从 Word 粘贴富文本
- 修复设置界面语言切换后不更新 —— 语言切换时重建界面
- 修复插件存储路径 —— 移至 `.fengyu/plugin/` 并修复先安装后加载失败的问题
- 修复 CI 中 Windows JAR 发现和发布产物路径问题
- 修复跨平台 JavaFX 原生库在 Fat JAR 中的打包

### ♻️ 变更

- 将 JNI 推理提取到进程外 `NativeWorkerClient` 以实现崩溃隔离
- 重构 AI 服务（`OpenAiService`、`AnthropicService`、`AiServiceImpl`）使用共享工具注册表、Gson 和 `JsonHelper`
- 删除 `JsonBuilder` 和 `JsonParser`，全面替换为 Gson/`JsonHelper`
- 将工具注册表迁移至 `AiServiceProvider`，删除独立的 `ToolRegistry`
- 将所有模块 POM 解耦为独立构建（无父级继承）
- 优化插件日志 API、元数据和共享组件
- 将官方插件迁移至独立的 `SwissKiJ-Plugin` 仓库
- 集中依赖管理并添加 PDFBox、documents4j 依赖
- 为推理指标统计在 `GenerateCallback.onDone` 中新增重载
- GitHub Actions 升级至 v5 以兼容 Node.js 24

---

**v3.0.0-beta.1** — 2026-05-24

### ✨ 新功能

- **国际化框架**：FengYu-Api 中的核心 `I18n` 类，支持数据库持久化语言设置、插件资源包注册/注销和实时语言切换

### ♻️ 变更

- 将所有 UI 组件（标题栏、主窗口、侧边栏、内容区、工具卡片、详情面板）转换为使用 I18n
- 完成 Settings UI（AI、邮件、通讯录选项卡）的国际化转换
- 完成所有内置工具和插件商店 UI 的国际化

### 🔧 修复

- 修复邮件选项卡中 VBox→HBox 类型不匹配的问题
- 修复邮件编辑器 — 扩展 WebView 高度并允许从 Word 粘贴富文本
- 语言切换后重建 Settings UI 以实现实时语言切换

---

**v3.0.0-alpha.2** — 2026-05-21

### ✨ 新功能

- **AI 聊天**：用于与本地 GGUF 模型对话的内置工具；支持 Q3_K/Q5_0/Q4_0/Q8_0/IQ4_NL 反量化、流式推理、工具调用和聊天会话管理
- **JNI 原生推理**：C++ `llama_jni` 原生层，含 `GenerateCallback`、`LlamaContext`、`ModelParams`、`GenerateParams` 绑定；捆绑 macOS 的 `libllama_jni-aarch64.dylib`
- **工具调用 API**：`FengYu-Api` 模块中的 `AiTool`、`AiToolCall`、`AiToolParam`、`AiToolResult`；主机中的 `ToolCallParser` 和 `ToolRegistry`
- **聊天会话**：`ChatSession` 类管理消息历史和上下文

---

**v3.0.0-alpha.1** — 2026-05-19

### ✨ 亮点

从 Swing/FlatLaf **完整迁移到 JavaFX 21**，采用玻璃态深色主题、重构的插件 API、重建的 Excel 拆分器。

### ✨ 新功能

- **JavaFX UI**：自定义窗口装饰（`StageStyle.TRANSPARENT`）、玻璃态侧边栏、带发光效果的 ToolCards、动画步骤向导
- **插件 API v3**：`SwissKitJPlugin` 接口替代 `KitPage`；插件返回 JavaFX `Node`
- **插件日志器**：`LoggerFactory` 搭配 SLF4J/Logback 后端；测试中安全使用空操作日志器
- **StepWizard**：可复用的多步骤向导，带点导航、滑入过渡、验证
- **插件商店**：在线目录 + 本地 JAR 安装，支持热重载
- **三层 CSS**：`fengyu-common.css`、`shell.css`、`builtin.css`，通过场景图继承
- **类型安全枚举**：`ToolCategory`、`ToolType`、`IconStyle` 替代基于字符串的元数据
- **Excel 拆分器向导**：使用 4 步 `StepWizard` 流程重新设计

### 🔧 修复

- 胖 JAR 捆绑所有平台的 JavaFX 原生 `.dll` / `.so` / `.dylib`

---

## [2.x] — Swing 时代（稳定版）

### v2.1.1 — 2026-05-07

- 修复：持久化语言设置，改善插件类加载器

### v2.1.0 — 2026-05-07

- 新功能：i18n `panelMethod` 属性和设置 i18n 刷新

### v2.0.2 — 2026-05-06

- 新功能：官方插件 i18n 支持

### v2.0.1 — 2026-05-06

- 新功能：国际化系统，支持英文和中文
- 新功能：`@SwissKitPage` 中需要 `pluginName`/`pluginVersion`
- 重构：插件注册表和基于注解的插件发现

### v2.0.0 — 2026-04-20

- **破坏性变更**：移除 `KitPage` 接口，纯注解插件发现
- 新功能：菜单点击导航和拖拽排序功能

---

## [1.x] — Swing 时代（初始版）

### v1.2.2 — 2026-04-15

- 新功能：鼠标插件（KeepMove 防止屏幕保护程序）
- 修复：优雅处理插件加载错误

### v1.2.1 — 2026-04-08

- 新功能：Excel 复制整个工作表到所有拆分文件
- 修复：HappyLearning 课时类型代码、跳课、UI 更新
- 修复：提升 Windows 上的插件卸载可靠性

### v1.2.0 — 2026-04-01

- 新功能：邮件富文本编辑器，带格式工具栏
- 新功能：HappyLearning 跳课按钮

### v1.1.0 — 2026-03-31

- **新功能：插件热部署** — 无需重启即可部署、重载和卸载插件
- 新功能：HappyLearning 课时跟踪和状态显示

### v1.0.0 — 2026-03-28

初始稳定版。

- Excel 工具：复杂拆分、配置编辑器、进度跟踪
- 邮件工具：群发、通讯录、标签管理、发送日志
- 插件系统：Java SPI 自动发现、隔离 ClassLoader
- 设置：统一配置、插件管理
- 跨平台：Windows、Linux、macOS（Apple Silicon）
- 数据库：H2 嵌入式 + MyBatis

---

## 预发布

### v1.0.0-RC.1 — 2026-03-28

- 11 个错误修复：EDT 违规、NPE 防护、资源泄漏、静默失败

### v1.0.0-Beta.4 — 2026-03-27

- 插件管理 UI、HappyLearning 课时显示
- EDT 违规修复、资源泄漏修复、NPE 防护

### v1.0.0-Beta.3 — 2026-03-27

- 邮件通讯录双击编辑

### v1.0.0-Beta.2 — 2026-03-27

- IsolatedPluginClassLoader JDK 委托修复

### v1.0.0-Beta.1 — 2026-03-26

- 邮件发送失败时显示错误对话框

### v1.0.0-Alpha.5 — 2026-03-26

- FengYu-Api 模块、邮件发送日志查看

### v1.0.0-Alpha.4 — 2026-03-26

- 邮件进度条、标签名称显示

### v1.0.0-Alpha.3 — 2026-03-26

- 标签关联重构为使用标签 ID

### v1.0.0-Alpha.2 — 2026-03-25

- 邮件发送、群发、基于标签的附件

### v1.0.0-Alpha.1 — 2026-03-24

- Excel 复杂拆分、邮件通讯录、标签管理、插件加载
