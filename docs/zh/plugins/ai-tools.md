---
title: AI 工具
description: 在插件清单中声明 aiTools 以暴露 AI 可调用的方法；宿主将其聚合成 Spring AI 的 ToolCallback[]，并把调用以 SSE tool 事件的形式呈现。
lang: zh-CN
---

# AI 工具

插件可以暴露方法，供宿主的聊天后端与智能体作为 **AI 工具**调用。声明纯粹是清单层面的事——无需改动宿主代码。启动时，`AiToolDiscoveryConfig` 会遍历每个已启用插件的 `aiTools` 数组，并把每一项转换成一个 Spring AI 的 `ToolCallback`。

## 声明工具

在 `manifest.json` 中添加一个 `aiTools` 数组。每一项有四个字段：

```json
{
  "name": "excel_analyze",
  "description": "Analyze an Excel file and return sheets and headers.",
  "method": "excel_analyze",
  "inputSchema": "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}},\"required\":[\"filePath\"]}"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | string | 提供给模型的工具名。 |
| `description` | string | 关于模型何时应选用此工具的自然语言指引。 |
| `method` | string | 当模型调用此工具时，宿主调用的 worker JSON-RPC 方法。 |
| `inputSchema` | string | 工具参数的 JSON Schema，**以字符串形式序列化**（注意转义的引号）。 |

`inputSchema` 必须是一个 JSON Schema 文档。宿主会解析这个字符串来构建交给模型的 Spring AI `ToolDefinition`，因此模型看到的是准确的参数元数据。

## 宿主聚合

启动时，`AiToolDiscoveryConfig.aiToolCallbacks(...)` 构建主 `ToolCallback[]`：

1. 每一个内置的 `@FengYuTool` bean 都通过 `ToolCallbacks.from(...)` 转换。
2. 对于每个 `aiTools` 非空的**已启用**已安装插件，其声明的每个工具都被包装进一个 `ToolCallback`，其中：
   - `getToolDefinition()` 返回一个由清单的 `name`、`description` 与解析后的 `inputSchema` 构建的 `ToolDefinition`；
   - `call(inputJson)` 反序列化模型的 JSON 参数，调用 `PluginProcessManager.invoke(pluginId, method, params)`（一次对 worker 的 JSON-RPC 调用），并把 worker 的结果序列化为字符串返回（失败时返回 `{success:false, error}`）。

同一个 `ToolCallback[]` bean 会被注入到聊天后端与智能体运行器中——因此声明了 `aiTools` 的插件立即就能从聊天和智能体流程中被调用。

## `supportsAi`

描述符标志 `supportsAi` 的含义就是 **`aiTools` 非空**。市场与插件列表用它来给提供 AI 能力的插件打徽标。一个 `"aiTools": []` 的插件其 `supportsAi` 为 `false`。

## SSE `tool` 事件

当模型在流式聊天或智能体运行中调用某个插件工具时，该调用会通过 SSE 流以 `tool` 事件的形式呈现，分为两个阶段：

| 阶段 | 时机 | 携带 |
| --- | --- | --- |
| `call` | 模型决定调用该工具 | 工具名 + 参数 |
| `result` | worker 返回 | 工具结果（或错误） |

这些与内置 `@FengYuTool` 发出的 `tool` 事件完全相同——插件工具在线上与内置工具无从区分。完整的事件分类见 [SSE 事件](/zh/reference/sse-events)。

## 实战示例：`excel_analyze`

`fan.summer.excel` 插件声明了六个工具。其 `excel_analyze` 项把模型接到 worker 的 `excel_analyze` JSON-RPC 方法，后者委托给 `ExcelAnalyzeTool.analyze(filePath)`：

```json
{
  "name": "excel_analyze",
  "description": "Analyze an Excel file and return sheets and headers.",
  "method": "excel_analyze",
  "inputSchema": "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}},\"required\":[\"filePath\"]}"
}
```

在 `ExcelWorkerMain` 中的 worker 注册：

```java
.on("excel_analyze", p -> analyze.analyze(JsonRpcWorker.string(p, "filePath")))
```

当模型调用 `excel_analyze` 时，宿主以 JSON-RPC 转发 `{filePath: <FileRef>}`；宿主会在 worker 看到它之前把 FileRef 改写为真实路径（见 [文件 I/O](/zh/plugins/file-io)）。完整的六个工具集合见 [官方插件——Excel](/zh/plugins/official-excel)。

## 下一步

- [清单](/zh/plugins/manifest)——完整的 schema，包括 `aiTools`。
- [Worker（JSON-RPC）](/zh/plugins/worker)——实现每个工具的 `method`。
- [官方插件——Excel](/zh/plugins/official-excel)——六个 aiTools 的端到端讲解。
