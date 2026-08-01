---
title: AI Tools
description: Declare aiTools in the plugin manifest to expose AI-callable methods; the host aggregates them into its Spring AI ToolCallback[] and surfaces calls as SSE tool events.
lang: en
---

# AI Tools

A plugin can expose methods that the host's chat backends and agent can call as **AI tools**. Declaration is purely a manifest concern — no host code changes are needed. At startup, `AiToolDiscoveryConfig` walks every enabled plugin's `aiTools` array and turns each entry into a Spring AI `ToolCallback`.

## Declaring tools

Add an `aiTools` array to `manifest.json`. Each entry has four fields:

```json
{
  "name": "excel_analyze",
  "description": "Analyze an Excel file and return sheets and headers.",
  "method": "excel_analyze",
  "inputSchema": "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}},\"required\":[\"filePath\"]}"
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `name` | string | The tool name offered to the model. |
| `description` | string | Natural-language guidance for when the model should pick this tool. |
| `method` | string | The worker JSON-RPC method the host invokes when the model calls this tool. |
| `inputSchema` | string | JSON Schema for the tool's arguments, **serialized as a string** (note the escaped quotes). |

`inputSchema` must be a JSON Schema document. The host parses this string to build the Spring AI `ToolDefinition` handed to the model, so the model sees accurate argument metadata.

## Host aggregation

At startup, `AiToolDiscoveryConfig.aiToolCallbacks(...)` builds the master `ToolCallback[]`:

1. Every built-in `@FengYuTool` bean is converted via `ToolCallbacks.from(...)`.
2. For each **enabled** installed plugin whose `aiTools` is non-empty, each declared tool is wrapped in a `ToolCallback` whose:
   - `getToolDefinition()` returns a `ToolDefinition` built from the manifest's `name`, `description`, and parsed `inputSchema`;
   - `call(inputJson)` deserializes the model's JSON arguments, invokes `PluginProcessManager.invoke(pluginId, method, params)` (a JSON-RPC call to the worker), and returns the worker's result serialized as a string (or `{success:false, error}` on failure).

The same `ToolCallback[]` bean is injected into the chat backends and the agent runner — so plugins that declare `aiTools` are instantly callable from chat and agent flows.

## `supportsAi`

The descriptor flag `supportsAi` is simply **`aiTools` is non-empty**. The marketplace and plugin list use it to badge plugins that contribute AI capability. A plugin with `"aiTools": []` has `supportsAi = false`.

## SSE `tool` events

When a model invokes a plugin tool during a streaming chat or agent run, the call surfaces over the SSE stream as `tool` events with two phases:

| Phase | When | Carries |
| --- | --- | --- |
| `call` | The model decided to call the tool | tool name + arguments |
| `result` | The worker returned | tool result (or error) |

These are the same `tool` events built-in `@FengYuTool`s emit — plugin tools are indistinguishable from built-ins on the wire. See [SSE Events](/en/reference/sse-events) for the full event taxonomy.

## Worked example: `excel_analyze`

The `fan.summer.excel` plugin declares six tools. Its `excel_analyze` entry wires the model to the worker's `excel_analyze` JSON-RPC method, which delegates to `ExcelAnalyzeTool.analyze(filePath)`:

```json
{
  "name": "excel_analyze",
  "description": "Analyze an Excel file and return sheets and headers.",
  "method": "excel_analyze",
  "inputSchema": "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}},\"required\":[\"filePath\"]}"
}
```

Worker registration in `ExcelWorkerMain`:

```java
.on("excel_analyze", p -> analyze.analyze(JsonRpcWorker.string(p, "filePath")))
```

When the model calls `excel_analyze`, the host forwards the arguments as JSON-RPC. If the user has attached a file for the conversation (see the attach affordance in the AI chat) and the tool has a single read-class file parameter, the host transparently injects the FileRef before dispatch (route B); `PluginProcessManager.resolveRefs` then rewrites it to a real path before the worker sees it. For tools with a write-directory or multiple file parameters, **or when no file has been attached for a single read-class parameter**, the host instead lists the available FileRefs in the system prompt and the model fills them itself (route A). In both cases the worker ultimately receives a resolved filesystem path. See [File I/O](/en/plugins/file-io). The full set of six tools is documented in [Official Plugin — Excel](/en/plugins/official-excel).

## Next steps

- [Manifest](/en/plugins/manifest) — the full schema, including `aiTools`.
- [Worker (JSON-RPC)](/en/plugins/worker) — implement each tool's `method`.
- [Official Plugin — Excel](/en/plugins/official-excel) — all six aiTools end to end.
