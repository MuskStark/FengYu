---
title: REST API
description: Infinia 4.0.0 后端 endpoint 完整目录——按 controller 分组的每一条 REST 与 SSE 路由，附鉴权要求与一行用途说明。宿主绑定环回地址、由令牌守护；有三类路径前缀在无令牌时即可完成自举。
lang: zh-CN
---

# REST API

Infinia 后端是一个无头（headless）Spring Boot 应用，通过环回地址（`server.address=127.0.0.1`）暴露一个 REST + SSE API。默认端口为 `24056`；若已被占用，启动器会回退到由操作系统分配的端口，并在 stdout 上以 `FENGYU_PORT=<n>` 公告它。参见 [后端](/zh/architecture/backend)。

## 鉴权

每个请求都会经过 `TokenAuthFilter`，它会把 `X-FengYu-Token` 头与启动时通过 `--token` 提供的值进行比较。有三类路径前缀**绕过**该过滤器，使系统能在没有凭据的情况下完成自举：

- `/api/health`——存活探针。
- `/api/setup/*`——首次启动向导（此时令牌可能尚不存在）。
- `/plugin-runtime/{id}/**`——静态插件 UI 资产，在严格的 CSP 下提供。

所有其他 endpoint 都要求令牌匹配。在下方的表格中，**Auth** 列为 `token`（需要头）、`—`（无需令牌，已绕过）、`ticket`（来自对应 `stream-ticket` endpoint 的一次性 `?ticket=`——用于无法设置请求头的 SSE），或某个权限名（令牌加上某项插件权限）。

::: tip
SSE 流**不接受**以 `?token=` 查询参数传递的令牌。请先签发一次性票据（`POST /api/ai/stream-ticket`、`/api/agent/stream-ticket` 或 `/api/notifications/stream-ticket`），再用 `?ticket=`（以及适用处的 `?streamId=` / `?runId=`）打开流。参见 [SSE 事件](/zh/reference/sse-events)。
:::

## Health

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/health` | — | 存活探针。返回 `{ "status": "ok" }`。 |

## 插件分类

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/plugin-categories` | token | 市场界面所用的分类词表（`id`、`labelKey`、`icon`）。 |

## 插件运行时

针对已安装插件的描述符访问与 worker 调用。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/plugin-runtime` | token | 已启用的插件，以 `InstalledPluginDescriptor[]` 形式返回。 |
| `POST` | `/api/plugin-runtime/{id}/invoke` | token | 调用某个 worker 方法。请求体 `{callId, method, params}` → JSON-RPC `result`；`callId` 为协议关联 ID。参见 [Worker](/zh/plugins/worker)。 |
| `POST` | `/api/plugin-runtime/{id}/invoke/{callId}/cancel` | token | 中断一个已跟踪的调用。返回 `{cancelled}`；取消 Worker 调用会终止该 Worker，避免卡住的处理器继续运行。 |
| `GET` | `/api/plugin-runtime/{id}/logs` | token | 最近的 Worker 事件，结构为 `{timestamp, level, logger, thread, message, sequence}`；旧式 stderr 的 logger/thread 为 null。 |
| `GET` | `/api/plugin-runtime/{id}/logs/stream` | token | 先重放最近的 Worker 事件，再通过 SSE 流式推送新事件。 |
| `GET` | `/plugin-runtime/{id}/**` | — | 插件 UI 静态资产（入口 HTML + JS），在严格的 CSP 下提供。 |

## 插件文件

面向沙箱化插件的文件授权 endpoint。全部位于基址 `/api/plugin-runtime/{id}/files` 下。每个都由插件 [清单](/zh/plugins/manifest) 中声明的一项权限把关。参见 [文件 I/O](/zh/plugins/file-io)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/plugin-runtime/{id}/files/upload` | token + `files.read` | 上传单个文件（multipart `file`）→ 以快照形式落到临时目录的 `FileRef`。 |
| `POST` | `/api/plugin-runtime/{id}/files/upload-directory` | token + `files.read`（`read-write` 另需 `files.write`） | 上传一棵目录树（multipart `files` + `paths[]`，可选 `access=read-write`）→ 目录 `FileRef`。 |
| `POST` | `/api/plugin-runtime/{id}/files/native` | token + `files.read` 和/或 `files.write` | 把一条原生操作系统路径（请求体 `{path, kind, access}`）包装为 `FileRef`。仅限桌面端。 |
| `POST` | `/api/plugin-runtime/{id}/files/output` | token + `files.write` | 分配一个全新的可写输出目录 → `FileRef`。 |
| `GET` | `/api/plugin-runtime/{id}/files/export/{ref}` | token + `files.write` | 以 zip 形式流式下载被授权目录的内容。 |

## 插件市场

插件注册表与生命周期。基址 `/api/plugin-market`。参见 [插件市场](/zh/plugins/marketplace)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/plugin-market` | token | 浏览目录 → `MarketplacePlugin[]`。 |
| `POST` | `/api/plugin-market/upload` | token | 从上传的 `.fyp`（multipart）安装。 |
| `POST` | `/api/plugin-market/upload-native` | token | 从本地文件系统路径安装（请求体 `{path}`）。仅限桌面端。 |
| `POST` | `/api/plugin-market/{id}/install` | token | 按 id 安装目录中的某个插件。 |
| `POST` | `/api/plugin-market/{id}/update` | token | 把已安装的插件更新到目录的最新版。 |
| `PATCH` | `/api/plugin-market/{id}/enabled` | token | 切换启用状态。请求体 `{enabled}`。禁用会立即停止 worker。 |
| `DELETE` | `/api/plugin-market/{id}?deleteData=<boolean>` | token | 使用显式运行数据保留/删除策略卸载；保留数据时也保留已 provision 的数据库命名空间。 |

## 设置

面向用户的偏好。参见 [配置——用户设置](/zh/guide/configuration#user-settings)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/settings` | token | 读取 `{theme, language, sidebarCollapsed, logLevel, computerUseEnabled, computerUse}`。 |
| `PUT` | `/api/settings` | token | 对用户设置做局部更新；`logLevel` 会实时应用到宿主和 Java Worker，`computerUseEnabled` 切换桌面端 `computer_*` 工具。 |
| `POST` | `/api/settings/database/reset` | token | 备份 `datasource.properties`、清空它、重启进入 SETUP 模式。 |

## AI

对话调用与流式端点。参见 [AI 对话](/zh/guide/ai-chat)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/ai/chat` | token | 启动一轮对话。请求体 `{messages:[{role, content}], permissionMode?, workflowId?}` → `{streamId}`。携带 `workflowId` 可把该轮对话绑定到对应流程（草稿或已发布）：模型会在普通聊天工具调用循环中获得 `run_current_flow` 工具。 |
| `GET` | `/api/ai/stream?streamId=` | token | 该轮对话对应的 SSE 流。参见 [SSE 事件——对话](/zh/reference/sse-events#对话流)。 |

## AI 配置

后端选择与 API 密钥，支持热切换。参见 [配置——AI 配置](/zh/guide/configuration#ai-config)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/ai/config` | token | 掩码后的配置快照（API 密钥以 `***` 掩码）。 |
| `PUT` | `/api/ai/config` | token | 局部更新；无需重启即可热切换当前生效的后端。 |
| `POST` | `/api/ai/config/test` | token | 不保存地探测一次连接。请求体 `{mode, endpoint, apiKey, model, baseUrl}`。 |

## 会话

已持久化的对话历史。参见 [AI 对话——会话](/zh/guide/ai-chat#会话)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/ai/conversations` | token | 会话摘要列表，最新在前。 |
| `GET` | `/api/ai/conversations/{id}` | token | 单个会话（标题 + 消息）。 |
| `POST` | `/api/ai/conversations` | token | 创建。请求体 `{title, messages}` → 带有 `id` 的已创建会话。 |
| `PUT` | `/api/ai/conversations/{id}` | token | 整体替换标题与消息。请求体 `{title, messages}`。 |
| `DELETE` | `/api/ai/conversations/{id}` | token | 删除某个会话。 |

## 智能体

「规划-执行」智能体。参见 [AI 智能体](/zh/guide/ai-agent)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/agent/run` | token | 启动一次运行。请求体 `{goal, config}` → `{runId}`。 |
| `POST` | `/api/agent/batch` | token | 并行启动 1–8 个独立运行。请求体 `{goals, config}` → `{runIds}`。 |
| `GET` | `/api/agent/stream?runId=` | token | 该次运行对应的 SSE 流。参见 [SSE 事件——智能体](/zh/reference/sse-events#智能体流)。 |
| `POST` | `/api/agent/{runId}/approve` | token | 放行一道审批关卡。可选发送编辑过的 `AgentPlan` 请求体。 |
| `POST` | `/api/agent/{runId}/cancel` | token | 协作式地取消该次运行。 |
| `GET` | `/api/agent/tools` | token | 可被编排的工具列表（由宿主聚合的 `ToolCallback[]`）。 |
| `GET` | `/api/agent/runs` | token | 按更新时间倒序返回持久化运行摘要。 |
| `GET` | `/api/agent/runs/{runId}` | token | 返回持久化计划、步骤执行和有序审计事件。 |
| `POST` | `/api/agent/runs/{runId}/resume` | token | 恢复失败/取消运行的未完成步骤，并要求重新审阅计划。 |
| `GET` | `/api/mcp/status` | token | 已配置的 MCP 连接与发现的工具数量。 |
| `GET` | `/api/mcp/servers` | token | 列出动态管理的 MCP 服务、连接状态和已发现的工具名。 |
| `POST` | `/api/mcp/servers` | token | 新增 `STDIO`、`SSE` 或 `STREAMABLE_HTTP` 服务并立即连接。凭据通过 `env`/`headers` 传入，API 不会回传凭据值。 |
| `PUT` | `/api/mcp/servers/{id}` | token | 替换服务定义，关闭旧会话、重新连接，并刷新实时 AI 工具目录。 |
| `DELETE` | `/api/mcp/servers/{id}` | token | 断开并删除动态管理的 MCP 服务。 |
| `POST` | `/api/mcp/servers/{id}/test` | token | 重新连接并执行 MCP 初始化及 `tools/list`。 |
| `POST` | `/api/mcp/servers/{id}/call` | token | 直接调用已发现的 MCP 工具。请求体 `{tool, arguments}`。 |
| `GET` | `/api/mcp/servers/{id}/prompts` | token | 列出实时 MCP 会话暴露的提示词。 |
| `GET` | `/api/mcp/servers/{id}/resources` | token | 列出实时 MCP 会话暴露的资源。 |

## 工作流

可复用工作流定义与智能体运行器使用同一份 `AgentPlan` DAG。`inputSchema` 是 JSON Schema
对象，运行时输入会绑定到 <code v-pre>{{inputs.name}}</code> 占位符；`layout` 把编译后的步骤索引映射为
画布位置，`graph`（可选）则原样保存编写时的画布图——含便签节点的 `{nodes, edges}`——使流程
构建器能按原样重开（无 `graph` 的定义从 `plan` + `layout` 重建画布）。已发布定义会加入实时 AI 工具目录。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/workflows` | token | 列出当前用户的工作流定义。 |
| `GET` | `/api/workflows/{workflowId}` | token | 读取一个定义。 |
| `POST` | `/api/workflows` | token | 通过 `{name, description, inputSchema, plan, layout?, graph?}` 创建。 |
| `PUT` | `/api/workflows/{workflowId}` | token | 替换可编辑定义并递增修订号。 |
| `POST` | `/api/workflows/{workflowId}/publish` | token | 通过 `{published}` 设置发布状态；发布后成为 AI 工具。 |
| `DELETE` | `/api/workflows/{workflowId}` | token | 删除定义。 |
| `POST` | `/api/workflows/{workflowId}/run` | token | 使用 `{inputs, config}` 人工运行并返回 `{runId}`；通过标准智能体 SSE 流观察。 |

## 通知

统一宿主通知中心——持久化记录加实时 SSE 扇出。生产者 POST 一条记录；每个已连接的 shell 都会实时收到它（参见 [SSE 事件——通知流](/zh/reference/sse-events#通知流)），并根据窗口可见性展示应用内 toast 或原生 OS 通知。已知生产者：插件 `notify` 宿主桥，以及智能体运行终态。历史按最新在前保存，每个安装保留 200 条上限。

| Method | Path | Auth | 用途 |
| --- | --- | --- | --- |
| `POST` | `/api/notifications` | token | 创建并广播。Body `{source, level, title, body?, link?}` → 创建后的视图。`level` 取 `info\|success\|warning\|error`；`source` 标识来源（`host`、`agent`、`plugin:<id>`）。 |
| `GET` | `/api/notifications?limit=&unreadOnly=` | token | 最新在前的历史（单次上限 100 条）。 |
| `GET` | `/api/notifications/unread-count` | token | 角标计数。 |
| `POST` | `/api/notifications/{id}/read` | token | 确认单条已读（幂等）。 |
| `POST` | `/api/notifications/read-all` | token | 全部确认已读。 |
| `DELETE` | `/api/notifications/{id}` | token | 从通知中心移除单条。 |
| `POST` | `/api/notifications/stream-ticket` | token | 签发 SSE 流兑换用的一次性票据。 |
| `GET` | `/api/notifications/stream?ticket=` | ticket | 向每个已连接 shell 推送实时 `notification` 事件。 |

## Setup

首次启动向导。所有 endpoint 都绕过令牌过滤器，且仅在 SETUP 模式下存在。参见 [数据库——Setup endpoint](/zh/guide/database#setup-endpoints)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/setup/status` | — | `{initialized, supportedTypes[], embeddedTypes[]}`。 |
| `GET` | `/api/setup/types` | — | 各后端的表单元数据，供向导使用。 |
| `POST` | `/api/setup/test-connection` | — | 不持久化地探测一次连接。请求体 `{type, params}`。 |
| `POST` | `/api/setup/initialize` | — | 再次测试、持久化配置、发出重启进入 APP 模式的信号。请求体 `{type, params}`。 |
| `DELETE` | `/api/setup/config` | — | 备份配置、清空它、重启进入 SETUP 模式。 |

## 约定

- JSON 请求体的**内容类型**为 `application/json`；文件上传使用 `multipart/form-data`。
- **错误**使用标准 HTTP 状态码。来自文件 endpoint 的 `403` 表示缺少某项[权限](/zh/plugins/manifest#valid-permissions)；其他地方的 `401`/`403` 表示令牌缺失或不匹配。
- **SSE** 帧以事件类型命名，并承载一个 JSON `data` payload。两条流端点都会把 `:connected` 注释心跳作为第一帧发出。

## 下一步

- [SSE 事件](/zh/reference/sse-events)——完整的对话与智能体流分类体系。
- [架构——后端](/zh/architecture/backend)——启动器、端口公告，以及 SETUP 与 APP 模式。
- [指南——配置](/zh/guide/configuration)——设置与 AI 配置的实战示例。
