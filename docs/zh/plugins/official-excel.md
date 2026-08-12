---
title: 官方插件——Excel
description: fan.summer.excel（v4.0.0）讲解——一个 file 类别插件，带 files.read/files.write 权限、三个动作（analyze、configure、split）和六个 aiTools、三种拆分模式（BY_SHEET、BY_COLUMN、COMPLEX）、文件 I/O 用法，以及一个四步向导 MF。
lang: zh-CN
---

# 官方插件——Excel

`fan.summer.excel` 是两个随产品发布的官方插件中能力更强的一个。它按工作表、按列值或按复杂规则把一个 Excel 工作簿拆分成多个文件，并把结果写进一个用户挑选的输出目录。它是把权限、文件 I/O 与 AI 工具结合在一起的权威示例。

## 它做什么

- 读取一个上传的 Excel 文件并报告其工作表与表头。
- 让用户（或 AI）选择三种拆分模式之一并配置它。
- 执行拆分，按每个结果写一个文件，写入一个已授权的输出目录。
- 暴露六个 AI 工具，使得一个聊天/智能体流程无需 UI 也能驱动整条流水线。

## 清单

```json
{
  "schemaVersion": 2,
  "id": "fan.summer.excel",
  "name": "Excel Splitter",
  "description": "Split Excel workbooks by sheet, column value, or complex rules",
  "version": "4.0.0",
  "author": "FengYu",
  "icon": "file-excel",
  "category": "file",
  "ui": { "entry": "ui/index.html" },
  "backend": { "callTimeoutSeconds": 60 },
  "permissions": ["files.read", "files.write"],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "rpc": {
    "methods": {
      "excel_analyze": {
        "description": "Analyze the granted Excel workbook; returns sheet names.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "filePath": { "type": "string", "description": "Resolved absolute path of a readable FengYu FileRef for the workbook." }
          },
          "required": ["filePath"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "success": { "type": "boolean" },
            "summary": { "type": "string" },
            "sheets": { "type": "array", "items": { "type": "string" }, "description": "Workbook sheet names." }
          },
          "required": ["success", "summary"]
        }
      },
      "excel_configure": {
        "description": "Configure the split mode (and BY_SHEET/BY_COLUMN specifics) on the shared AI session.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "mode": { "type": "string", "enum": ["BY_SHEET", "BY_COLUMN", "COMPLEX"] },
            "sheets": { "type": "array", "items": { "type": "string" }, "description": "BY_SHEET selection; omit to use all sheets." },
            "splitSheet": { "type": "string" },
            "splitColumn": { "type": "string" }
          },
          "required": ["mode"]
        },
        "outputSchema": {
          "type": "object",
          "properties": { "success": { "type": "boolean" }, "summary": { "type": "string" } },
          "required": ["success", "summary"]
        }
      },
      "excel_complex_config": {
        "description": "Add, list, or clear complex split rules on the shared AI session.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "action": { "type": "string", "enum": ["add", "list", "clear"] },
            "sheetName": { "type": "string" },
            "headerIndex": { "type": "integer" },
            "columnIndex": { "type": "integer" }
          },
          "required": ["action"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "success": { "type": "boolean" },
            "summary": { "type": "string" },
            "entries": {
              "type": "array",
              "description": "Configured complex split rules (action=list).",
              "items": {
                "type": "object",
                "properties": {
                  "sheetName": { "type": "string" },
                  "headerIndex": { "type": "integer" },
                  "columnIndex": { "type": "integer" }
                }
              }
            }
          },
          "required": ["success", "summary"]
        }
      },
      "excel_execute": {
        "description": "Execute the configured split synchronously into outputDir (small workbooks).",
        "inputSchema": {
          "type": "object",
          "properties": {
            "outputDir": { "type": "string", "description": "Resolved absolute path of a writable FengYu DirectoryRef." },
            "filePrefix": { "type": "string" }
          },
          "required": ["outputDir"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "success": { "type": "boolean" },
            "summary": { "type": "string" },
            "files": {
              "type": "object",
              "description": "Generated file count and names.",
              "properties": {
                "fileCount": { "type": "integer" },
                "files": { "type": "array", "items": { "type": "string" } }
              }
            }
          },
          "required": ["success", "summary"]
        }
      },
      "excel_query": {
        "description": "Return the current Excel split session state.",
        "inputSchema": { "type": "object", "properties": {} },
        "outputSchema": {
          "type": "object",
          "properties": {
            "success": { "type": "boolean" },
            "summary": { "type": "string" },
            "state": { "type": "object", "description": "Current Excel split session state." }
          },
          "required": ["success", "summary"]
        }
      },
      "excel_cancel": {
        "description": "Clear the active Excel split session.",
        "inputSchema": { "type": "object", "properties": {} },
        "outputSchema": {
          "type": "object",
          "properties": { "success": { "type": "boolean" }, "summary": { "type": "string" } },
          "required": ["success", "summary"]
        }
      }
    }
  },
  "aiTools": [
    { "name": "excel_analyze", "method": "excel_analyze", "effect": "read", "description": "Analyze the granted Excel workbook; returns sheet names.", "timeoutSeconds": 30 },
    { "name": "excel_configure", "method": "excel_configure", "effect": "read", "description": "Configure sheet, column, or complex splitting.", "timeoutSeconds": 30 },
    { "name": "excel_complex_config", "method": "excel_complex_config", "effect": "read", "description": "Add, list, or clear complex split rules.", "timeoutSeconds": 30 },
    { "name": "excel_execute", "method": "excel_execute", "effect": "write", "description": "Execute a configured split synchronously for a small workbook.", "timeoutSeconds": 60 },
    { "name": "excel_query", "method": "excel_query", "effect": "read", "description": "Return the current Excel split session state.", "timeoutSeconds": 30 },
    { "name": "excel_cancel", "method": "excel_cancel", "effect": "read", "description": "Clear the active Excel split session.", "timeoutSeconds": 30 }
  ]
}
```

要点：

- **`category: "file"`**——一个文件处理插件。
- **`permissions: ["files.read", "files.write"]`**——它读取一个上传的输入文件，并把拆分文件写进一个输出目录（随后导出一个 zip）。两个权限都是必需的。
- **`rpc.methods`** 声明了每个方法的 `inputSchema`/`outputSchema`（JSON-Schema **对象**）。
- **`aiTools`** 有六个条目，因此 `supportsAi` 为 `true`。每个 `{name, description, method, effect}` 把一个面向模型的工具映射到一个 `rpc.methods` 中的方法；`effect`（`read`/`write`/`external`）驱动审批分类。
- **`backend: { "callTimeoutSeconds": 60 }`**——插件级默认调用超时；线协议固定为 stdio 上的 JSON-RPC 2.0，不再在清单中声明。

## 三个动作

除了六个 AI 工具方法之外，worker 还暴露了三个由 UI 直接驱动的**动作**（在 `ExcelWorkerMain` 中注册）：

| 动作 | 用途 |
| --- | --- |
| `analyze` | 检查输入文件并返回其工作表 + 表头（`excel_analyze` 的 UI 侧）。 |
| `configure` | 设置拆分模式及其参数（`excel_configure` 的 UI 侧）。 |
| `split` | 把配置好的拆分执行进输出目录（`excel_execute` 的 UI 侧）。 |

这些 action 方法与 `excel_*` AI 工具方法共享同一套底层服务——AI 工具就是向导所驱动的那条流水线，只不过可从聊天调用。参见 [AI 工具](/zh/plugins/ai-tools) 与 [Worker（JSON-RPC）](/zh/plugins/worker)。

## 三种拆分模式

| 模式 | 行为 |
| --- | --- |
| `BY_SHEET` | 为每个选中的工作表产出一个输出文件。 |
| `BY_COLUMN` | 按某一选定列的唯一值对行分组；每个值一个文件。 |
| `COMPLEX` | 应用多配置规则（通过 `excel_complex_config` 的 add/list/clear）以做更细粒度的拆分。 |

`excel_configure` 设置 `mode`（枚举 `BY_SHEET | BY_COLUMN | COMPLEX`）加上随模式而异的字段（`sheets`、`splitSheet`、`splitColumn`）；`excel_complex_config` 用 `action` 枚举 `add | list | clear` 和整型 `headerIndex` / `columnIndex` 管理 `COMPLEX` 模式的规则列表。

## 文件 I/O 用法

该插件端到端使用宿主的文件授权模型（见 [文件 I/O](/zh/plugins/file-io)）：

1. **Analyze 读取一个已上传的文件。** UI 调用 `fengyu.files.open({ extensions: ['xlsx'] })` → `POST .../files/upload`（需要 `files.read`）→ 一个 `FileRef`。它把该 ref 传给 `analyze` / `excel_analyze`；宿主在 worker 看到它之前把 `ref_*` 改写为绝对路径。
2. **Execute 把拆分文件写进一个输出目录。** UI 调用 `fengyu.files.outputDirectory()` → `POST .../files/output`（需要 `files.write`）→ 一个可写的 `DirectoryRef`，作为 `outputDir` 传给 `split` / `excel_execute`。worker 把拆分文件写在那里。
3. **Export 把它打 zip。** `fengyu.files.export(outDir)` → `GET .../files/export/{ref}`（需要 `files.write`）把输出目录以 zip 形式流式下载。

```js
import { fengyu } from '@infinia/plugin-sdk'
import { createPluginRpc } from './generated/fengyu-rpc'

const rpc = createPluginRpc(fengyu)

const file   = await fengyu.files.open({ extensions: ['xlsx'] })   // files.read
await rpc.analyze({ filePath: file })                              // 宿主把 ref → path

const outDir = await fengyu.files.outputDirectory()                // files.write
await rpc.split({ outputDir: outDir, filePrefix: 'q3-' })
await fengyu.files.export(outDir)                                  // 打 zip + 下载
```

一个 `files.write` 操作（output/export）在 `permissions` 没有 `files.write` 时会返回 `403`。Excel 清单声明了两者，因此整条流水线都被授权。参见 [常见陷阱](/zh/plugins/pitfalls)。

## 向导 UI

UI 是一个受控、有状态的**四步向导**微前端，使用 `FyStepWizard` 与 iframe 内部的 `@infinia/plugin-ui` Vuetify 实例构建：

```
1. Source  ──►  2. Mode  ──►  3. Output  ──►  4. Run
   选择并分析       选择并配置       授权输出目录       执行、查看、下载
```

- **Source：** 通过 `files.open` 选择 `.xlsx` 或 `.xls` 授权；该步骤分析工作簿并显示其中的工作表与列。
- **Mode：** 选择并配置三种模式之一。`BY_SHEET` 可选工作表：非空选择会作为 `selectedSheets` 发送，留空时则省略该字段，由 worker 展开为所有已分析工作表。`BY_COLUMN` 要求工作表与表头仍存在于最新分析结果中。`COMPLEX` 要求一条或多条完整规则：普通索引必须是大于等于 1 的整数；整表复制规则则要求并发送 `headerIndex: -1` 与 `columnIndex: -1`。可选的输出文件名前缀也属于此步骤；UI 始终发送 `filePrefix`（包括 `""`），因此清空它会重置之前 configure 调用保存的值。
- **Output：** 通过 `files.outputDirectory()` 选择新的可写目录授权。
- **Run：** 调用 `split`。校验成功后会显式完成向导并显示已写文件的数量/列表；**Download results** 是另一个用户操作，会调用 `files.export`。

Analyze、configure、输出选择或 split 失败时会停留在当前步骤，并只显示一条由向导拥有的内联错误提示。对于 worker 响应，UI 会依次使用 `error`、`summary` 与对应操作的兜底文字，因此真实 JSON-RPC `{ success: false, summary }` 契约仍能提供可操作信息。前进操作会变成 **Retry**，再次执行该步骤的校验；重复的前进/运行操作只会执行一次，过期的异步结果不能推进工作流。导出发生在向导校验完成后，因此导出错误仍会显示在完成结果中。

上游编辑会使依赖进度、下游旧错误与之前的拆分结果失效。更改 Source 会重置 Mode、Output 与 Run；更改 Mode 或任何模式专属选项会重置 Output 与 Run；更改输出授权会重置 Run。真实访问路径会裁剪回发生变化的步骤，因此失效的未来步骤会保持锁定，直至依赖重新校验。特别是，新的 Source 或 Mode 选择也会清除之前的输出目录授权。

由 Excel 插件而非 `FyStepWizard` 在 `sessionStorage` 中持久化版本化的 JSON 向导快照及其业务草稿。重新加载时，它会校验记录结构（包括 COMPLEX 索引与 copy-all 不变量）、恢复草稿，并在恢复前重新分析已存的源文件/会话。如果保存路径已到达 Output，随后还会用恢复的模式和选项重放 `configure`；只有 worker 状态重建成功才会开放 Output。如果恢复的 `BY_COLUMN` 工作表或表头已在重新分析时消失，向导会直接回到 Mode，且不调用 configure。configure 返回失败或抛错时同样会回到 Mode，显示可重试的内联错误。对于 `BY_SHEET`，恢复的空选择仍会省略 `selectedSheets`，保持 worker 的“全部工作表”语义。损坏的记录或不可用的 storage 会从 Source 干净启动；序列化及 storage 读/写/删除失败都会被隔离，保存/清除失败只警告一次，不会让当前工作流崩溃。过期的源文件授权会带错误返回 Source。结果不会作为已完成工作恢复。

在当前 SDK 契约下，输出目录权限授权无法从 `sessionStorage` 安全恢复。因此重新加载总会清除 Output 与 Run，最晚从 Output 继续，并要求用户在 Run 前重新选择输出目录。完成与之后的 zip 下载都是显式操作。

它加载在 `/plugin-runtime/fan.summer.excel/**` 下的沙箱化 iframe 中，并通过 `@infinia/plugin-sdk` 与宿主桥接。参见 [UI 微前端](/zh/plugins/ui-microfrontend)。

## 下一步

- [清单](/zh/plugins/manifest)——完整的 schema，包括 `aiTools` 与 `permissions`。
- [AI 工具](/zh/plugins/ai-tools)——六个 `excel_*` 工具如何被聚合成 Spring AI 的 `ToolCallback[]`。
- [文件 I/O](/zh/plugins/file-io)——analyze/execute/export 流程背后的授权模型。
- [官方插件——Markdown](/zh/plugins/official-markdown)——那个更简单的兄弟插件。
