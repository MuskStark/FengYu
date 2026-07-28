---
title: 清单
description: manifest.json 的完整参考——schemaVersion、id、name、ui/backend 子记录、权限、category 取值，以及定义一个 FengYu 插件的 aiTools 声明。
lang: zh-CN
---

# 清单

`manifest.json` 是插件的唯一事实来源。宿主在安装时解析它，以获知插件的身份、如何启动其 worker、挂载什么 UI、允许它做什么，以及它暴露哪些 AI 工具。它位于 `.fyp` 归档的根目录。

## Schema 参考

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `schemaVersion` | number | 是 | — | 清单 schema 版本。当前为 `1`。 |
| `id` | string | 是 | — | 反向 DNS 的插件 id，例如 `fan.summer.excel`。在已安装插件中必须唯一。 |
| `name` | string | 是 | — | 人类可读的显示名。 |
| `description` | string | 是 | — | 在市场与插件列表中展示的一行描述。 |
| `version` | string | 是 | — | SemVer 风格的版本字符串，例如 `4.0.0`。 |
| `author` | string | 是 | — | 作者或组织名。 |
| `icon` | string | 是 | — | 图标标识符（一个 Vuetify/Material 设计图标名，例如 `file-excel`）。 |
| `category` | string | 是 | — | [合法 category 取值](#合法-category-取值)之一。 |
| `ui` | object | 是 | — | UI 子记录。见 [`ui`](#ui)。 |
| `backend` | object | 否 | — | Worker 子记录。见 [`backend`](#backend)。**可选**——纯 UI 插件可省略它。 |
| `permissions` | string[] | 否 | `[]` | 声明的[权限](#合法权限)。驱动文件 I/O 授权。 |
| `homepage` | string | 否 | — | 指向插件主页或源码仓库的 URL。 |
| `official` | boolean | 否 | `false` | 由 `OfficialPluginSeeder` 预置的插件设为 `true`；将描述符的 `source` 设为 `OFFICIAL`。 |
| `aiTools` | object[] | 否 | `[]` | 声明的 [AI 工具](/zh/plugins/ai-tools)。空数组表示 `supportsAi = false`。 |

### `ui`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `entry` | string | 是 | 相对于归档根的入口 HTML 路径，通常为 `ui/index.html`。通过 `/plugin-runtime/{id}/<entry>` 提供。 |

### `backend`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `command` | string | 是 | 宿主用于启动 worker 的 shell 命令，例如 `java -jar backend/worker.jar`。 |
| `protocol` | string | 是 | 线协议（wire protocol）。当前为 `json-rpc-2.0`。 |
| `callTimeoutSeconds` | integer | 否 | 插件级的默认每次调用超时（秒）。会被钳制到 `[1, 600]`。省略时宿主使用 `60`。`aiTools[].timeoutSeconds` 会针对单个工具覆盖此值。 |

### `aiTools[]`

每一项声明一个 AI 可调用的工具，宿主会把它们聚合成其 Spring AI 的 `ToolCallback[]`。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 暴露给模型的工具名。 |
| `description` | string | 是 | 给模型的自然语言描述。 |
| `method` | string | 是 | 当模型调用此工具时要调用的 worker JSON-RPC 方法。 |
| `inputSchema` | string | 是 | 描述工具参数的 JSON Schema，序列化为**字符串**。 |
| `timeoutSeconds` | integer | 否 | 针对此工具的调用超时（秒），钳制到 `[1, 600]`。覆盖 `backend.callTimeoutSeconds`。默认 `60`。**可能超过其声明超时的工具必须拆分为 `*_start` / `*_status` / `*_cancel` 的 job 方法**——参见 [Worker → 长任务（job 模式）](/zh/plugins/worker#长任务-job-模式)。 |

端到端流程见 [AI 工具](/zh/plugins/ai-tools)。

## 合法 category 取值

`category` 是一个自由格式的提示性字符串，UI 用它来对插件分组——宿主**不会**校验它是否属于某个固定集合（它只会把你写的值转为大写，为空时默认为 `OTHER`）。为保持一致，请使用以下约定取值之一：

| 取值 | 用途 |
| --- | --- |
| `dev` | 开发者工具 |
| `text` | 文本编辑/渲染（例如 `fan.summer.markdown`） |
| `image` | 图像处理 |
| `net` | 网络相关 |
| `network` | 网络相关（例如 `fan.summer.email`） |
| `file` | 文件处理（例如 `fan.summer.excel`） |
| `ai` | 以 AI 为中心的插件 |
| `other` | 上述未涵盖的任何类型（脚手架的默认值） |

## 合法权限

`permissions` 是一个数组，包含零个或多个以下规范集合中的值，由 CLI 与宿主共同强制执行：

| 取值 | 授权 |
| --- | --- |
| `files.read` | `POST /api/plugin-runtime/{id}/files/upload`、`upload-directory`、`native`（读访问） |
| `files.write` | `POST .../files/native`（写访问）、`POST .../files/output`、`GET .../files/export/{ref}` |
| `network` | 来自 worker 的通用出站网络访问。 |
| `network.email` | worker 可以建立 SMTP/IMAP 连接（`fan.summer.email` 使用）。 |
| `clipboard.read` | 读取宿主剪贴板。 |
| `clipboard.write` | 写入宿主剪贴板。 |
| `notifications` | 显示宿主通知/toast。 |
| `database` | 宿主向 worker 环境注入一个数据源连接（`FENGYU_DB_*` + 一个私有数据目录）。参见[插件数据库规范](/zh/plugins/database)。 |

任何其他取值在 validate 与 install 时都会被当作未知权限拒绝。在缺少对应权限的情况下尝试文件操作会被以 `403` 拒绝。参见 [文件 I/O](/zh/plugins/file-io)。

## 示例

### Markdown 插件

`fan.summer.markdown` 的清单——一个无权限、无 AI 工具的文本插件：

```json
{
  "schemaVersion": 1,
  "id": "fan.summer.markdown",
  "name": "Markdown Editor",
  "description": "Split-pane Markdown editor with isolated server-side rendering",
  "version": "4.0.0-alpha.4",
  "author": "FengYu",
  "icon": "language-markdown",
  "category": "text",
  "ui": { "entry": "ui/index.html" },
  "backend": { "command": "java -jar backend/worker.jar", "protocol": "json-rpc-2.0" },
  "permissions": [],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "aiTools": []
}
```

### Excel 插件（含 aiTools）

`fan.summer.excel` 的清单——一个带读写权限和 AI 工具的文件插件。这里完整展示两个工具：`excel_analyze`（短时同步调用），以及 `excel_execute_start`（长时拆分 job 模式对的启动半边）。其余遵循同样的 `{name, description, method, inputSchema, timeoutSeconds?}` 结构：

```json
{
  "schemaVersion": 1,
  "id": "fan.summer.excel",
  "name": "Excel Splitter",
  "description": "Split Excel workbooks by sheet, column value, or complex rules",
  "version": "4.0.0-alpha.4",
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
      "timeoutSeconds": 30,
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}},\"required\":[\"filePath\"]}"
    },
    {
      "name": "excel_execute_start",
      "description": "Launch the configured split as a background job for large workbooks and return a jobId immediately. Poll excel_execute_status with a cursor to drain progress logs.",
      "method": "excel_execute_start",
      "timeoutSeconds": 30,
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"outputDir\":{\"type\":\"object\",\"description\":\"A writable FengYu DirectoryRef\"},\"filePrefix\":{\"type\":\"string\"}},\"required\":[\"outputDir\"]}"
    }
  ]
}
```

> `inputSchema` 是一个**以字符串形式序列化的 JSON Schema**——注意那些转义的引号。宿主会解析它以构建 Spring AI 的 `ToolDefinition`。

## 下一步

- [Worker（JSON-RPC）](/zh/plugins/worker)——实现 `backend.command` 所指向的目标。
- [AI 工具](/zh/plugins/ai-tools)——声明并暴露 `aiTools`。
- [文件 I/O](/zh/plugins/file-io)——每条 `permissions` 条目解锁的能力。
