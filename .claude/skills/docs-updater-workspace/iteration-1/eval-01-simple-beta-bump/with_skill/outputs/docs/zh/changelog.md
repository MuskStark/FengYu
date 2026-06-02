# 更新日志

## 3.0.0-beta.2 (2026-05-29)

### 新功能

- **AI Chat**: 支持 OpenAI 和 Anthropic API 后端，自动加载
- **AI Chat**: 通过 WebView 渲染 Markdown 响应，重命名为 SwissKitJClaw
- **AI Chat**: 内置 AI 工具 — Base64、Hash、JSON Format、Color Convert
- **AI Chat**: ToolExecutor 和 ToolSchemaBuilder 工具注册实用程序
- **AI Chat**: Excel 集成 — 用于 AI 驱动拆分操作的 analyze、configure、execute、query 和 cancel 工具
- **AI Chat**: excel_complex_config 工具和 reasoning_content 处理
- **AI Chat**: FunctionGemma 原生工具调用协议支持，包含适配器、停止序列和 AiServiceImpl 集成
- **AI Chat**: 模型文件选择器支持 *.ggufz 文件
- **Email Archive**: 内置邮件归档工具，支持 IMAP
- **Plugin System**: 后台执行支持，包含视图缓存和 ToolCard 指示器
- **Hash Calculator**: 计算文本输入的哈希摘要（MD5、SHA-1、SHA-256）

### 修复

- 修复 ToolCard 后台指示器不显示和预览 i18n 不工作的问题
- 修复插件 i18n 资源包因 ClassLoader 父委托返回宿主翻译的问题
- 修复 ExcelSplitterPlugin 缺少 hasRunningTasks 的问题
- 修复 MainWindow 中过期的 AiServiceImpl 初始化覆盖启动后端的问题
- 修复 WebView 白色背景，使用深色主题显示 AI 消息气泡
- 修复自动调整 WebView 高度以匹配内容
- 修复 AI 消息气泡圆角和文字亮度
- 修复 JSON Schema 中数组类型的工具参数处理
- 修复工具参数的正确 JSON Schema 和改进的工具调用提示
- 修复 Base64 编码无法处理 UTF-8 字符的问题

### 变更

- 整合 AI 工具基础设施：基于 Gson 的 JSON、共享注册表、移除 JsonBuilder/JsonParser
- 暴露共享的 SplitConfig 供 AI 工具访问

## 3.0.0-beta.1 (2026-05-20)

- 初始 JavaFX 迁移版本
