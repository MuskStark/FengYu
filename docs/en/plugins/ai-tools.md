---
title: AI Tools
description: Declare aiTools in the plugin manifest to expose AI-callable methods; the host aggregates them into its Spring AI ToolCallback[] and surfaces calls as SSE tool events.
lang: en
---

# AI Tools

A plugin can expose methods that the host's chat backends and agent can call as **AI tools**. Declaration is purely a manifest concern — no host code changes are needed. The live tool registry scans enabled plugins when it creates a catalog or starts an agent run, so install, upgrade, enable, disable, and uninstall changes do not require a host restart.

## Declaring tools

Add an `aiTools` array to `manifest.json`. Each entry references an [`rpc.methods`](/en/plugins/manifest#rpc-methods) method and carries four fields — the input/output schemas live on the method it points at:

```json
{
  "rpc": {
    "methods": {
      "excel_analyze": {
        "description": "Analyze an Excel file and return sheets and headers.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "filePath": { "type": "string", "description": "Resolved path of a granted FengYu FileRef." }
          },
          "required": ["filePath"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "success": { "type": "boolean" },
            "summary": { "type": "string" },
            "sheets": { "type": "array", "items": { "type": "string" } }
          },
          "required": ["success", "summary"]
        }
      }
    }
  },
  "aiTools": [
    {
      "name": "excel_analyze",
      "method": "excel_analyze",
      "effect": "read",
      "description": "Analyze an Excel file and return sheets and headers."
    }
  ]
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `name` | string | The tool name offered to the model. |
| `method` | string | The `rpc.methods` key the host invokes when the model calls this tool. |
| `effect` | string | Approval classification: `read`, `write`, or `external`. |
| `description` | string | Natural-language guidance for when the model should pick this tool. |

The input/output schemas live on the referenced `rpc.methods` entry (see [Manifest](/en/plugins/manifest#rpc-methods)). The host reads the method's `inputSchema` to build the Spring AI `ToolDefinition` handed to the model, so the model sees accurate argument metadata. `outputSchema` is used by visual workflow configuration and ignored by Spring AI tool calling.

## Live host aggregation

`AiToolRegistry` builds an immutable callback snapshot for each agent run:

1. Every built-in `@FengYuTool` bean is converted via `ToolCallbacks.from(...)`.
2. For each **enabled** installed plugin whose `aiTools` is non-empty, each declared tool is wrapped in a `ToolCallback` whose:
   - `getToolDefinition()` returns a `ToolDefinition` built from the manifest's `name`, `description`, and parsed `inputSchema`;
   - `call(inputJson)` deserializes the model's JSON arguments, invokes `PluginProcessManager.invoke(pluginId, method, params)` (a JSON-RPC call to the worker), and returns the worker's result serialized as a string (or `{success:false, error}` on failure).

The visual workflow catalog also carries a stable `pluginId:toolName` identity, a schema revision, and `outputSchema`. Connected downstream inputs can select either the whole result or a declared output field. Existing canvas nodes are preserved when a tool disappears, marked unavailable, and reconciled with the latest input schema if the same tool returns.

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
  "method": "excel_analyze",
  "effect": "read",
  "description": "Analyze an Excel file and return sheets and headers."
}
```

Worker registration in `ExcelWorkerMain`:

```java
.method(PluginMethods.EXCEL_ANALYZE, ExcelAnalyzeInput.class, ExcelAnalyzeOutput.class,
        (ExcelAnalyzeInput input, RpcContext ctx) -> analyze.analyze(input, ctx))
```

When the model calls `excel_analyze`, the host forwards the arguments as JSON-RPC. If the user has attached a matching file or writable directory for the conversation (see the attach affordance in AI chat) and the tool has exactly one file-class parameter, the host transparently injects the FileRef before dispatch (route B); `PluginProcessManager.resolveRefs` then rewrites it to a real path before the worker sees it. Write-directory injection requires a `write` or `read-write` grant. For tools with multiple file parameters, **or when no matching grant is attached**, the host instead lists the available FileRefs in the system prompt and the model fills them itself (route A). In both cases the worker receives a resolved filesystem path; the Excel worker rejects unresolved objects instead of converting their map representation into a relative path. See [File I/O](/en/plugins/file-io). The full set of six tools is documented in [Official Plugin — Excel](/en/plugins/official-excel).

## Next steps

- [Manifest](/en/plugins/manifest) — the full schema, including `aiTools`.
- [Worker (JSON-RPC)](/en/plugins/worker) — implement each tool's `method`.
- [Official Plugin — Excel](/en/plugins/official-excel) — all six aiTools end to end.
