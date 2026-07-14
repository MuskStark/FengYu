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

所有其他 endpoint 都要求令牌匹配。在下方的表格中，**Auth** 列为 `token`（需要头）、`—`（无需令牌，已绕过），或某个权限名（令牌加上某项插件权限）。

::: tip
SSE 端点通过 `X-FengYu-Token` 头进行鉴权——**没有** `?token=` 查询参数。打开流时只用 `?streamId=` / `?runId=`。参见 [SSE 事件](/zh/reference/sse-events)。
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
| `POST` | `/api/plugin-runtime/{id}/invoke` | token | 调用某个 worker 方法。请求体 `{method, params}` → JSON-RPC `result`。参见 [Worker](/zh/plugins/worker)。 |
| `GET` | `/plugin-runtime/{id}/**` | — | 插件 UI 静态资产（入口 HTML + JS），在严格的 CSP 下提供。 |

## 插件文件

面向沙箱化插件的文件授权 endpoint。全部位于基址 `/api/plugin-runtime/{id}/files` 下。每个都由插件 [清单](/zh/plugins/manifest) 中声明的一项权限把关。参见 [文件 I/O](/zh/plugins/file-io)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/plugin-runtime/{id}/files/upload` | token + `files.read` | 上传单个文件（multipart `file`）→ 以快照形式落到临时目录的 `FileRef`。 |
| `POST` | `/api/plugin-runtime/{id}/files/upload-directory` | token + `files.read` | 上传一棵目录树（multipart `files` + `paths[]`）→ 目录 `FileRef`。 |
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
| `DELETE` | `/api/plugin-market/{id}` | token | 卸载——停止 worker、移除包、删除描述符。 |

## 设置

面向用户的偏好。参见 [配置——用户设置](/zh/guide/configuration#user-settings)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/settings` | token | 读取 `{theme, language, sidebarCollapsed}`。 |
| `PUT` | `/api/settings` | token | 对用户设置做局部更新。 |
| `POST` | `/api/settings/database/reset` | token | 备份 `datasource.properties`、清空它、重启进入 SETUP 模式。 |

## AI

对话调用与流式端点。参见 [AI 对话](/zh/guide/ai-chat)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/ai/chat` | token | 启动一轮对话。请求体 `{messages:[{role, content}]}` → `{streamId}`。 |
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
| `GET` | `/api/agent/stream?runId=` | token | 该次运行对应的 SSE 流。参见 [SSE 事件——智能体](/zh/reference/sse-events#智能体流)。 |
| `POST` | `/api/agent/{runId}/approve` | token | 放行一道审批关卡。可选发送编辑过的 `AgentPlan` 请求体。 |
| `POST` | `/api/agent/{runId}/cancel` | token | 协作式地取消该次运行。 |
| `GET` | `/api/agent/tools` | token | 可被编排的工具列表（由宿主聚合的 `ToolCallback[]`）。 |

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
