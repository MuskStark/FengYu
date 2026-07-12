# 迁移到 FengYu 3.1（ChatBackend）

FengYu 3.1 用统一的 `ChatBackend` 契约替换了 `AiService` 接口。这对直接调用
`AiServiceProvider.getService()` 或引用具体服务类的外部插件是**破坏性变更**。

## 受影响范围

| 插件模式 | 影响 |
|---------|------|
| 调用 `AiServiceProvider.getService()` 驱动 AI 对话 | **需要改代码** —— 类型重命名 |
| 自己实现 `AiService` 注册自定义后端 | **需要重写**（罕见）—— 见下文[自定义后端](#自定义后端) |
| 使用 `instanceof OpenAiService` / `instanceof AnthropicService` | **需要改代码** —— 见[类型判断](#类型判断) |
| 只通过 `AiServiceProvider.registerTool()` 注册 `AiTool` | **无需改动** |
| 只调用 `AiServiceProvider.registerTool()` / `getTools()` / `unregisterTool()` | **无需改动** |

## 迁移步骤

### 1. 替换 `AiService` 类型引用

```java
// 3.0.x 及之前：
import fan.summer.fengyu.api.ai.AiService;
Optional<AiService> opt = AiServiceProvider.getService();
AiService svc = opt.get();
svc.chat(history, callback);

// 3.1 之后：
import fan.summer.fengyu.api.ai.ChatBackend;
Optional<ChatBackend> opt = AiServiceProvider.getService();
ChatBackend svc = opt.get();
svc.chat(history, callback);
```

方法签名完全一致 —— 只是类型名变了。

### 2. 类型判断

```java
// 之前：
if (svc instanceof AiServiceImpl) { ... }       // 本地模式
if (svc instanceof OpenAiService) { ... }       // 云端模式

// 之后：
if (svc instanceof LocalChatBackend) { ... }    // 本地模式
if (svc instanceof CloudChatBackend) { ... }    // 云端模式（OpenAI 或 Anthropic）
```

区分 OpenAI 与 Anthropic：

```java
if (svc instanceof CloudChatBackend cloud) {
    if (cloud.provider() == CloudChatBackend.Provider.OPENAI) { ... }
    if (cloud.provider() == CloudChatBackend.Provider.ANTHROPIC) { ... }
}
```

### 3. 如果你直接实例化了云端服务

这从未得到对外部插件的官方支持（宿主负责服务的生命周期）。如果确实这么做了：

```java
// 之前：
OpenAiService svc = new OpenAiService();
svc.configure(endpoint, apiKey, model);

// 之后：
CloudChatBackend svc = CloudChatBackend.openAi(endpoint, apiKey, model);
// （Anthropic 用 CloudChatBackend.anthropic(...)）
```

### 4. 更新 pom.xml

**无需改动。** `FengYu-Api` 3.1 仍然不依赖 LC4j —— 插件作者**不需要**
往 pom 加 LC4j。`ChatBackend` 接口及其实现通过现有的 `FengYu-Api`
依赖即可访问（接口在 API 模块，实现在宿主模块，运行时由 fat JAR 提供）。

### 5. 自定义后端

如果你实现过 `AiService` 以插入自定义后端（例如自托管 LLM），现在需要
改成实现 `ChatBackend`。接口是非密封的 —— 任何人都可以实现。方法签名与
旧的 `AiService` 一致，但移除了工具注册方法（`registerTool` /
`unregisterTool` / `getTools`），这些方法只保留在 `AiServiceProvider` 上。

向宿主注册自定义后端：

```java
AiServiceProvider.switchMode("my-mode", myCustomBackend);
```

宿主会像对待其他后端一样对待它。

## 未变化的部分

- **工具声明** —— `AiTool` 接口、`AiToolParam`、`AiToolCall`、`AiToolResult`
  全部不变。通过 `AiServiceProvider.registerTool(AiTool)` 注册。
- **对话消息** —— `AiChatMessage` 及其工厂方法（`user(...)`、`assistant(...)`、
  `system(...)` 等）不变。
- **流式回调** —— `AiStreamCallback` 接口（`onToken`、`onComplete`、`onError`、
  `onToolCall`、`onToolResult`）不变。
- **工具注册表** —— `AiServiceProvider.registerTool()` / `getTools()` /
  `unregisterTool()` / `clearTools()` 不变。
- **插件入口** —— `SwissKitJPlugin` 接口不变。
- **日志器** —— `LoggerFactory.getLogger(Class)` 不变。

## 为什么做这个改动

3.1 之前，云端栈在两个独立的服务类（`OpenAiService` 约 240 行、`AnthropicService`
约 280 行）里手写了 HTTP/SSE 代码。3.1 在 LangChain4j 上重建云端层，把两个 provider
合并成单个 `CloudChatBackend`（约 450 行，含 LC4j 适配器），消除 provider 间的 bug
和功能差异。本地模式（进程内 GGUF）从 `AiServiceImpl` 改名为 `LocalChatBackend`
—— 纯改名，行为不变。

## 获取帮助

如果遇到本指南未覆盖的问题，请到
[github.com/MuskStark/FengYu/discussions](https://github.com/MuskStark/FengYu/discussions) 提交讨论。
