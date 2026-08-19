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
| `schemaVersion` | number | 是 | — | 清单 schema 版本。当前为 `2`。 |
| `id` | string | 是 | — | 反向 DNS 的插件 id，例如 `fan.summer.excel`。在已安装插件中必须唯一。 |
| `name` | string | 是 | — | 人类可读的显示名。 |
| `description` | string | 是 | — | 在市场与插件列表中展示的一行描述。 |
| `version` | string | 是 | — | SemVer 风格的版本字符串，例如 `4.0.0`。 |
| `author` | string | 是 | — | 作者或组织名。 |
| `icon` | string | 是 | — | 图标标识符（一个 Vuetify/Material 设计图标名，例如 `file-excel`）。 |
| `category` | string | 是 | — | [合法 category 取值](#合法-category-取值)之一。 |
| `ui` | object | 是 | — | UI 子记录。见 [`ui`](#ui)。 |
| `backend` | object | 否 | — | Worker 子记录。见 [`backend`](#backend)。**可选**——纯 UI 插件可省略它。 |
| `rpc` | object | 否 | — | RPC 方法表。见 [`rpc.methods`](#rpcmethods)。声明每个方法的 `inputSchema`/`outputSchema`（JSON-Schema **对象**）。 |
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
| `callTimeoutSeconds` | integer | 否 | 插件级的默认每次调用超时（秒）。会被钳制到 `[1, 600]`。省略时宿主使用 `60`。`aiTools[].timeoutSeconds` 会针对单个工具覆盖此值。 |

宿主按约定以 `java -jar backend/worker.jar` 启动 worker，并通过 stdio 上的 JSON-RPC 2.0 驱动它；线协议固定为 `json-rpc-2.0`，不再在清单中声明启动命令或协议。

### `rpc.methods`

插件暴露的 JSON-RPC 方法表，以方法名为键。每个方法自带其参数与输出的 JSON Schema——是真正的 JSON-Schema **对象**，而非转义字符串。Java Worker SDK 与 TypeScript UI 客户端均从该表生成类型化绑定。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `description` | string | 否 | 方法的简短描述。 |
| `inputSchema` | object | 是 | 描述方法参数的 JSON Schema **对象**。 |
| `outputSchema` | object | 否 | 描述 Worker 结果信封的 JSON Schema 对象。 |

`aiTools[].method` 必须在此表中存在；`fengyu` CLI 在 `check`/`build` 时会校验二者一致。

### `aiTools[]`

每一项声明一个 AI 可调用的工具，宿主会把它们聚合成其 Spring AI 的 `ToolCallback[]`。参数与输出 Schema 不再内联——它们声明在 [`rpc.methods`](#rpcmethods) 中；`aiTools[]` 只携带工具的面向模型的元数据与副作用分类。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 暴露给模型的工具名。 |
| `description` | string | 是 | 给模型的自然语言描述。 |
| `method` | string | 是 | 当模型调用此工具时要调用的 worker JSON-RPC 方法（必须在 `rpc.methods` 中存在）。 |
| `effect` | string | 是 | 审批分类：`read`、`write` 或 `external`。 |
| `timeoutSeconds` | integer | 否 | 针对此工具的调用超时（秒），钳制到 `[1, 600]`。覆盖 `backend.callTimeoutSeconds`。默认 `60`。**可能超过其声明超时的工具必须拆分为 `*_start` / `*_status` / `*_cancel` 的 job 方法**——参见 [Worker → 长任务（job 模式）](/zh/plugins/worker#长任务-job-模式)。 |

端到端流程见 [AI 工具](/zh/plugins/ai-tools)。

### `flowNodes[]`

面向 Flows 流程构建器的显式画布节点声明。带 `flowNodes` 声明的工具在画布上呈现为
一等节点——类型化端口、示例值、帮助文案、自定义控件；未声明的工具仍会出现在调色板
「显示全部工具」开关之后，作为按 Schema 推导的降级节点。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `tool` | string | 是 | 该节点渲染并执行的 `aiTools[].name`。 |
| `label` | string | 否 | 卡片标签（缺省为工具名的人性化形式）。 |
| `kind` | string | 否 | `action`（默认）/ `control` / `start` —— 画布结构节点的保留字。 |
| `help` | string | 否 | 节点级帮助，展示在检查器的帮助区。 |
| `docsUrl` | string | 否 | 外部文档链接。 |
| `color` | string | 否 | 卡片十六进制颜色。 |
| `icon` | string | 否 | 徽章的 MDI 图标名。 |
| `inputs[]` | array | 否 | 声明的输入，见下。 |
| `outputs[]` | array | 否 | 命名输出端口，见下。 |

每个**输入**携带 `name` + `widget`（`text` / `number` / `switch` / `select` / `textarea` /
`json` / `analyze` / `rows`），以及可选的 `title`、`description`、`help`（字段级提示）、
`type`（流程数据类型：`string` / `number` / `boolean` / `object` / `array` / `file` / `any`，
驱动变量选择器的类型过滤，缺省 `any`）、`required`、`placeholder`、`examples[]`、
`advanced`（折叠进高级设置）、`default`、`options[]`（`select` 用）、`source`（从插件列表
RPC 加载选项）、`context`（分析式编辑期数据集）与 `fields[]`（`rows` 控件的每行字段）。

每个**输出**携带 `name`、`title`、`type`（为端口着色并过滤选择器）、`description` /
`help`、`examples[]`（在真实运行数据到来前展示），对象或数组输出还可递归声明
`properties` / `items`，让变量树能提供 `confirmation.confirmationId`、`files[0]` 这样的
嵌套路径。

完整词表定义在
[`toolchain/spec/manifest.schema.json`](https://github.com/MaskStark/FengYu/blob/4.0.0/toolchain/spec/manifest.schema.json)
（`flowNode`、`flowNodeInput`、`flowNodeOutput`、`flowOutputProperty` 定义）；宿主的
`flow-nodes/builtin.json` 以
[`flow-node.schema.json`](https://github.com/MaskStark/FengYu/blob/4.0.0/toolchain/spec/flow-node.schema.json)
校验。

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
| `database` | 宿主向 worker 环境注入数据库连接坐标（`FENGYU_DB_*` —— type/driver/url/username/password —— 以及一个私有数据目录），以隔离 DB 用户/schema 形式 provision；由 worker 自行建立连接。参见[插件数据库规范](/zh/plugins/database)。 |

任何其他取值在 validate 与 install 时都会被当作未知权限拒绝。在缺少对应权限的情况下尝试文件操作会被以 `403` 拒绝。参见 [文件 I/O](/zh/plugins/file-io)。

> **强制力度并不一致（P1-9）。** 不要假设每个被接受的权限都被同等强制执行：
> - **由宿主/OS 沙箱强制：** `files.read`、`files.write`（FileRef 授权闸门）、`network`（OS 网络命名空间）。
> - **在网络层按全量出站放行（advisory）：** `network.email`、`database` 目前授予宽泛的出站网络——宿主尚未代理 SMTP/IMAP，也未限制 DB 只连特定主机。真正的邮件/DB 代理是一项已立项的后续工作。
> - **宿主桥门控（插件 `notify`）：** `notifications`。宿主桥会在运行时读取它——声明了该
>   权限的插件，其 `notify` 调用会产生真正的统一宿主通知（应用内 toast + 原生桌面通知 +
>   持久化通知中心）；未声明的调用回退到 `@infinia/plugin-ui` 的 iframe 内部通知中心。
> - **仅声明（尚无宿主强制）：** `clipboard.read`、`clipboard.write` 只是为未来到桌面外壳的 capability 桥接声明意图，运行时当前不读取。
>
> 在任何汇总插件权限的 UI 中都要如实呈现——不要对 `network.email`/`database` 暗示比 OS 实际强制更细的网络隔离。

## 示例

### Markdown 插件

`fan.summer.markdown` 的清单——一个无权限、无 AI 工具的文本插件：

```json
{
  "schemaVersion": 2,
  "id": "fan.summer.markdown",
  "name": "Markdown Editor",
  "description": "Split-pane Markdown editor with isolated server-side rendering",
  "version": "4.0.0",
  "author": "FengYu",
  "icon": "language-markdown",
  "category": "text",
  "ui": { "entry": "ui/index.html" },
  "backend": { "callTimeoutSeconds": 30 },
  "permissions": [],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "rpc": {
    "methods": {
      "render": {
        "description": "Render Markdown source to sanitized HTML via commonmark.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "markdown": { "type": "string", "description": "The Markdown source to render." }
          },
          "required": ["markdown"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "success": { "type": "boolean", "description": "true when the render completed." },
            "summary": { "type": "string", "description": "Short localized result summary." },
            "html": { "type": "string", "nullable": true, "description": "The rendered, sanitized HTML." }
          },
          "required": ["success", "summary"]
        }
      }
    }
  }
}
```

### Excel 插件（含 aiTools）

`fan.summer.excel` 的清单——一个带读写权限和 AI 工具的文件插件。这里完整展示两个方法：`excel_analyze`（短时同步调用），以及 `excel_execute_start`（长时拆分 job 模式对的启动半边）。参数与输出 Schema 是 `rpc.methods` 里的 JSON-Schema **对象**；`aiTools[]` 只引用方法名并声明副作用：

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
            "filePath": { "type": "string", "description": "Resolved absolute path of a readable FengYu FileRef." }
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
      },
      "excel_execute_start": {
        "description": "Launch the configured split as a background job and return a jobId immediately. Poll excel_execute_status with a cursor to drain progress logs.",
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
            "jobId": { "type": "string" }
          },
          "required": ["success", "summary"]
        }
      }
    }
  },
  "aiTools": [
    { "name": "excel_analyze", "method": "excel_analyze", "effect": "read", "description": "Analyze the granted Excel workbook; returns sheet names.", "timeoutSeconds": 30 },
    { "name": "excel_execute_start", "method": "excel_execute_start", "effect": "write", "description": "Launch the configured split as a background job and return its job ID.", "timeoutSeconds": 30 }
  ]
}
```

> `inputSchema`/`outputSchema` 是真正的 JSON-Schema **对象**（不再是转义字符串）。`aiTools[]` 只携带 `name`/`method`/`effect`/`description`/`timeoutSeconds`——参数与输出 Schema 统一声明在 `rpc.methods` 中，宿主据此构建 Spring AI 的 `ToolDefinition`。

## 下一步

- [Worker（JSON-RPC）](/zh/plugins/worker)——实现 `rpc.methods` 所声明方法的进程外后端。
- [AI 工具](/zh/plugins/ai-tools)——声明并暴露 `aiTools`。
- [文件 I/O](/zh/plugins/file-io)——每条 `permissions` 条目解锁的能力。
