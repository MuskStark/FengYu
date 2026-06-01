# 更新日志

SwissKitJ 的所有重要变更。格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)。

---

## [3.0.0] — JavaFX 迁移

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
- **插件预览窗口**：为第三方插件开发者提供的独立预览套件，包含 `PreviewTitleBar`、`PreviewSidebar`、`PreviewToolCard`、`PreviewDetailPanel` 和 `swisskit-preview.css`
- **玻璃通知组件**：玻璃拟态风格的通知组件，替换所有 `Alert` 弹窗
- **应用图标**：为 macOS（.icns）、Windows（.ico）和 Linux（.png）提供原生分辨率的应用图标
- **内置工具**：Base64 编解码器、哈希计算器、JSON 格式化器、颜色转换器和 Markdown 编辑器插件，全部注册为内置工具
- **国际化框架**：SwissKitJ-Api 中的核心 `I18n` 类，支持数据库持久化语言设置、插件资源包注册/注销，以及所有 UI 组件（TitleBar、MainWindow、Sidebar、ContentArea、ToolCard、DetailPanel、Settings）的实时语言切换
- **设置界面**：重新设计的设置页面，包含 AI、邮件和地址簿标签页
- **三层 CSS 架构**：`swisskit-common.css`（共享变量和 glass-* 工具类）、`shell.css`（应用外壳）和 `builtin.css`（内置工具），支持场景图继承
- **类型安全枚举**：SwissKitJ-Api 中 `ToolCategory`、`ToolType` 和 `IconStyle` 枚举替代基于字符串的元数据
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
- 修复插件存储路径 —— 移至 `.swisskit/plugin/` 并修复先安装后加载失败的问题
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

- **国际化框架**：SwissKitJ-Api 中的核心 `I18n` 类，支持数据库持久化语言设置、插件资源包注册/注销和实时语言切换

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
- **工具调用 API**：`SwissKitJ-Api` 模块中的 `AiTool`、`AiToolCall`、`AiToolParam`、`AiToolResult`；主机中的 `ToolCallParser` 和 `ToolRegistry`
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
- **三层 CSS**：`swisskit-common.css`、`shell.css`、`builtin.css`，通过场景图继承
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

- SwissKitJ-Api 模块、邮件发送日志查看

### v1.0.0-Alpha.4 — 2026-03-26

- 邮件进度条、标签名称显示

### v1.0.0-Alpha.3 — 2026-03-26

- 标签关联重构为使用标签 ID

### v1.0.0-Alpha.2 — 2026-03-25

- 邮件发送、群发、基于标签的附件

### v1.0.0-Alpha.1 — 2026-03-24

- Excel 复杂拆分、邮件通讯录、标签管理、插件加载
