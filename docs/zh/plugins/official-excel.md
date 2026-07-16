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
  "schemaVersion": 1,
  "id": "fan.summer.excel",
  "name": "Excel Splitter",
  "description": "Split Excel workbooks by sheet, column value, or complex rules",
  "version": "4.0.0",
  "author": "FengYu",
  "icon": "file-excel",
  "category": "file",
  "ui": { "entry": "ui/index.html" },
  "backend": { "command": "java -jar backend/worker.jar", "protocol": "json-rpc-2.0" },
  "permissions": ["files.read", "files.write"],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "aiTools": [
    {
      "name": "excel_analyze",
      "description": "Analyze an Excel file and return sheets and headers.",
      "method": "excel_analyze",
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}},\"required\":[\"filePath\"]}"
    },
    {
      "name": "excel_configure",
      "description": "Configure BY_SHEET, BY_COLUMN, or COMPLEX splitting.",
      "method": "excel_configure",
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\",\"enum\":[\"BY_SHEET\",\"BY_COLUMN\",\"COMPLEX\"]},\"sheets\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"splitSheet\":{\"type\":\"string\"},\"splitColumn\":{\"type\":\"string\"}},\"required\":[\"mode\"]}"
    },
    {
      "name": "excel_complex_config",
      "description": "Add, list, or clear complex split rules.",
      "method": "excel_complex_config",
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"add\",\"list\",\"clear\"]},\"sheetName\":{\"type\":\"string\"},\"headerIndex\":{\"type\":\"integer\"},\"columnIndex\":{\"type\":\"integer\"}},\"required\":[\"action\"]}"
    },
    {
      "name": "excel_execute",
      "description": "Execute the configured split into an authorized output directory.",
      "method": "excel_execute",
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"outputDir\":{\"type\":\"object\",\"description\":\"A writable FengYu DirectoryRef\"},\"filePrefix\":{\"type\":\"string\"}},\"required\":[\"outputDir\"]}"
    },
    {
      "name": "excel_query",
      "description": "Query the active Excel split session.",
      "method": "excel_query",
      "inputSchema": "{\"type\":\"object\",\"properties\":{}}"
    },
    {
      "name": "excel_cancel",
      "description": "Cancel and clear the active Excel split session.",
      "method": "excel_cancel",
      "inputSchema": "{\"type\":\"object\",\"properties\":{}}"
    }
  ]
}
```

要点：

- **`category: "file"`**——一个文件处理插件。
- **`permissions: ["files.read", "files.write"]`**——它读取一个上传的输入文件，并把拆分文件写进一个输出目录（随后导出一个 zip）。两个权限都是必需的。
- **`aiTools`** 有六个条目，因此 `supportsAi` 为 `true`。每个 `{name, description, method, inputSchema}` 把一个面向模型的工具映射到一个 worker JSON-RPC 方法。
- **`backend.command: "java -jar backend/worker.jar"`** 搭配 **`protocol: "json-rpc-2.0"`**。

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
const file   = await fengyu.files.open({ extensions: ['xlsx'] })   // files.read
await fengyu.invoke('analyze', { filePath: file })                 // 宿主把 ref → path

const outDir = await fengyu.files.outputDirectory()                // files.write
await fengyu.invoke('split', { outputDir: outDir, filePrefix: 'q3-' })
await fengyu.files.export(outDir)                                  // 打 zip + 下载
```

一个 `files.write` 操作（output/export）在 `permissions` 没有 `files.write` 时会返回 `403`。Excel 清单声明了两者，因此整条流水线都被授权。参见 [常见陷阱](/zh/plugins/pitfalls)。

## 向导 UI

UI 是一个**四步向导**微前端，使用宿主的 Vuetify 实例构建：

```
1. 选择文件  ──►  2. 选择模式  ──►  3. 配置  ──►  4. 输出
   选 .xlsx        BY_SHEET /       随模式而异        输出目录 +
   (files.read)    BY_COLUMN /      参数             导出 zip
                   COMPLEX                            (files.write)
```

- **第 1 步——选择文件：** 上传输入工作簿（`files.open`）。
- **第 2 步——选择模式：** 选 `BY_SHEET`、`BY_COLUMN` 或 `COMPLEX`。
- **第 3 步——配置：** 随模式而异的选项（工作表、拆分列或复杂规则）。
- **第 4 步——输出：** 分配输出目录，执行拆分，并导出 zip。

它加载在 `/plugin-runtime/fan.summer.excel/**` 下的沙箱化 iframe 中，并通过 `@infinia/plugin-sdk` 与宿主桥接。参见 [UI 微前端](/zh/plugins/ui-microfrontend)。

## 下一步

- [清单](/zh/plugins/manifest)——完整的 schema，包括 `aiTools` 与 `permissions`。
- [AI 工具](/zh/plugins/ai-tools)——六个 `excel_*` 工具如何被聚合成 Spring AI 的 `ToolCallback[]`。
- [文件 I/O](/zh/plugins/file-io)——analyze/execute/export 流程背后的授权模型。
- [官方插件——Markdown](/zh/plugins/official-markdown)——那个更简单的兄弟插件。
