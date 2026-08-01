---
title: AI 工具
description: 在插件清单中声明 aiTools 以暴露 AI 可调用的方法；宿主将其聚合成 Spring AI 的 ToolCallback[]，并把调用以 SSE tool 事件的形式呈现。
lang: zh-CN
---

# AI 工具

插件可以暴露方法，供宿主的聊天后端与智能体作为 **AI 工具**调用。声明纯粹是清单层面的事——无需改动宿主代码。动态工具注册表会在生成目录或启动智能体运行时扫描已启用插件，因此安装、升级、启用、停用和卸载后无需重启宿主。

## 声明工具

在 `manifest.json` 中添加一个 `aiTools` 数组。每一项有四个必填字段，以及一个可选的输出契约：

```json
{
  "name": "excel_analyze",
  "description": "Analyze an Excel file and return sheets and headers.",
  "method": "excel_analyze",
  "inputSchema": "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}},\"required\":[\"filePath\"]}",
  "outputSchema": "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"summary\":{\"type\":\"string\"},\"sheets\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | string | 提供给模型的工具名。 |
| `description` | string | 关于模型何时应选用此工具的自然语言指引。 |
| `method` | string | 当模型调用此工具时，宿主调用的 worker JSON-RPC 方法。 |
| `inputSchema` | string | 工具参数的 JSON Schema，**以字符串形式序列化**（注意转义的引号）。 |
| `outputSchema` | string | 可选的 Worker 结果信封 JSON Schema，以字符串形式序列化。供可视化工作流配置使用，Spring AI 工具调用会忽略它。 |

`inputSchema` 必须是一个 JSON Schema 文档。宿主会解析这个字符串来构建交给模型的 Spring AI `ToolDefinition`，因此模型看到的是准确的参数元数据。

## 动态宿主聚合

`AiToolRegistry` 会为每次智能体运行构建不可变的回调快照：

1. 每一个内置的 `@FengYuTool` bean 都通过 `ToolCallbacks.from(...)` 转换。
2. 对于每个 `aiTools` 非空的**已启用**已安装插件，其声明的每个工具都被包装进一个 `ToolCallback`，其中：
   - `getToolDefinition()` 返回一个由清单的 `name`、`description` 与解析后的 `inputSchema` 构建的 `ToolDefinition`；
   - `call(inputJson)` 反序列化模型的 JSON 参数，调用 `PluginProcessManager.invoke(pluginId, method, params)`（一次对 worker 的 JSON-RPC 调用），并把 worker 的结果序列化为字符串返回（失败时返回 `{success:false, error}`）。

可视化工作流目录还会携带稳定的 `pluginId:toolName` 身份、Schema 修订号与 `outputSchema`。已连接的下游输入可以选择完整结果或某个已声明的输出字段。工具消失时已有画布节点会被保留并标记为不可用；同一工具恢复后，节点会按最新输入 Schema 自动协调参数。

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

当模型调用 `excel_analyze` 时，宿主以 JSON-RPC 转发参数。如果用户为本次对话附加了文件（见 AI 聊天中的附加入口）且该工具只有一个读取类（read）的文件参数，宿主会在派发前透明地注入 FileRef（路由 B）；随后 `PluginProcessManager.resolveRefs` 会在 worker 看到它之前把 FileRef 改写为真实路径。对于带有写入目录或多个文件参数的工具，**或当单个读取类参数但用户未附加文件时**，宿主则改为在系统提示词中列出可用的 FileRef，由模型自行填入（路由 A）。两种情况下 worker 最终收到的都是已解析的文件系统路径。见 [文件 I/O](/zh/plugins/file-io)。完整的六个工具集合见 [官方插件——Excel](/zh/plugins/official-excel)。

## 下一步

- [清单](/zh/plugins/manifest)——完整的 schema，包括 `aiTools`。
- [Worker（JSON-RPC）](/zh/plugins/worker)——实现每个工具的 `method`。
- [官方插件——Excel](/zh/plugins/official-excel)——六个 aiTools 的端到端讲解。
