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

## 账号

可选 Infinia Store 云身份的本地控制面。这些路由仍需本地启动令牌；后端负责系统浏览器中的
OAuth 2.1 + PKCE 流程，绝不会把 Store token 暴露给 SPA。参见
[后端——云账号登录](/zh/architecture/backend#云账号登录)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/account/me` | token | 返回已绑定的 Store 用户资料；未登录时返回本地虚拟用户。 |
| `POST` | `/api/account/sign-in` | token | 启动浏览器登录 → `{attemptId, authorizationUrl}`。 |
| `GET` | `/api/account/sign-in/{attemptId}` | token | 轮询登录尝试 → `{status: PENDING\|COMPLETED\|FAILED, user?, error?}`。 |
| `POST` | `/api/account/sign-out` | token | 尽力撤销 Store refresh token、删除绑定，并返回本地用户。 |
| `GET` | `/api/account/store-profile` | token | 实时 Store 用户资料，含 Infinia 等级（`beeLevel`）与 `createdAt`；未登录返回 401。 |
| `PUT` | `/api/account/profile` | token | 修改 Store 显示名称（1–64 字符）；本地绑定随之同步。 |
| `PUT` | `/api/account/password` | token | 修改 Store 密码（当前密码 + 8–128 位新密码）。 |
| `GET` | `/api/account/library` | token | Store 库摘要：收藏、授权、安装记录。 |
| `GET` | `/api/account/organizations` | token | 用户加入的组织。 |
| `GET` | `/api/account/sessions` | token | 活跃授权会话。 |
| `DELETE` | `/api/account/sessions/{sessionId}` | token | 撤销一个会话 → 204。 |
| `GET` | `/api/account/devices` | token | 已注册设备及其撤销状态。 |
| `DELETE` | `/api/account/devices/{deviceId}` | token | 撤销一个设备 → 204。 |

## 插件分类

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/plugin-categories` | token | 市场界面所用的分类词表（`id`、`labelKey`、`icon`）。 |

## 插件运行时

针对已安装插件的描述符访问与 worker 调用。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/plugin-runtime` | token | 已启用的插件，以 `InstalledPluginDescriptor[]` 形式返回。 |
| `GET` | `/api/plugin-runtime/status` | token | 所有已安装 Worker 的运维快照：状态、故障分类、runtime、pid、重启、退避与沙箱。 |
| `GET` | `/api/plugin-runtime/{id}/status` | token | 单个插件的运维快照。 |
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

## 插件包

本地 `.fyp` 包生命周期：上传（浏览器与桌面原生）、安装前检查、启停与卸载。每次安装与卸载都运行在运行时更新门控之内——停止 worker、健康预检、提交/回滚。基址 `/api/plugin-packages`。参见 [插件市场](/zh/plugins/marketplace)。（已废弃的 `/api/plugin-market` 别名仍按 1:1 转发这些端点——见本节末尾。）

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/plugin-packages/upload` | token | 从上传的 `.fyp` 安装（multipart `file`、可选 `.sha256` `sidecar`、`confirmPermissions`）。与已安装插件同 id 时执行健康门控更新，失败自动回滚。 |
| `POST` | `/api/plugin-packages/upload-native` | token | 从本地文件系统路径安装（请求体 `{path, confirmPermissions}`）。仅限桌面端。 |
| `POST` | `/api/plugin-packages/inspect` | token | 不安装、只读取上传 `.fyp` 的 manifest → `PackageInspection`（安装还是更新 + 版本变化）。 |
| `POST` | `/api/plugin-packages/inspect-native` | token | `/inspect` 的路径版（请求体 `{path}`）。仅限桌面端。 |
| `PATCH` | `/api/plugin-packages/{id}/enabled` | token | 切换启用状态。请求体 `{enabled}`。禁用会立即停止 worker。 |
| `DELETE` | `/api/plugin-packages/{id}?deleteData=<boolean>` | token | 使用显式运行数据保留/删除策略卸载；保留数据时也保留已 provision 的数据库命名空间。 |

## 统一商店

跨来源（`FENGYU`、`CLAUDE`、`CODEX`、`GROK`）的统一插件商店。基址 `/api/plugin-store`。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/plugin-store/sources` | token | 已配置的商店来源。 |
| `POST` | `/api/plugin-store/sources` | token | 新增商店来源（请求体 `{origin, type, url}`）。 |
| `DELETE` | `/api/plugin-store/sources/{origin}` | token | 移除商店来源。 |
| `POST` | `/api/plugin-store/sources/{origin}/refresh` | token | 重新拉取某个来源的目录。 |
| `GET` | `/api/plugin-store/catalog` | token | 所有来源合并后的目录 → `UnifiedCatalogEntry[]`。 |
| `POST` | `/api/plugin-store/{uid}/install` | token | 按 `uid` 安装目录条目（走上述门控生命周期）。 |
| `POST` | `/api/plugin-store/{uid}/update?confirmPermissions=<boolean>` | token | 重装到目录最新版；新增权限需显式确认。 |
| `PATCH` | `/api/plugin-store/{uid}/enabled` | token | 切换启用状态。请求体 `{enabled}`。 |
| `DELETE` | `/api/plugin-store/{uid}?deleteData=<boolean>` | token | 卸载统一商店条目。 |
| `GET` | `/api/plugin-store/history` | token | 安装/更新历史记录。 |

## Infinia Store（云端商店）

云端商店客户端接口：目录浏览、详情、依赖计划的安装与更新检查。每个下载制品都必须携带经证明的 SHA-256 和来自受信密钥的平台 Ed25519 签名。基址 `/api/store`。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/store/catalog?type=&query=` | token | 合并本地安装状态的目录。 |
| `GET` | `/api/store/listings/{namespace}/{slug}` | token | 条目详情与可见的发布版本。 |
| `GET` | `/api/store/installed` | token | 通过商店安装的坐标（以磁盘真实状态为准）。 |
| `GET` | `/api/store/updates` | token | 已安装坐标的可用更新（按 SemVer 优先级）。 |
| `POST` | `/api/store/install` | token | 按 `infinia://` 坐标安装（请求体 `{coordinate, confirmPermissions}`）。先解析依赖计划；整个计划作为一个带 journal 的事务提交，失败则整体回滚。 |
| `DELETE` | `/api/store/installed?coordinate=&deleteData=<boolean>` | token | 卸载一个通过商店安装的坐标。 |
| `GET` | `/api/store/status` | token | 所配置商店平台的 `{apiBase}`。 |

## 技能

技能生命周期与市场——`.fys` 指导包的插件包生命周期孪生。内置技能不可被覆盖，远程条目必须带有签名。基址 `/api/skills`。参见 [技能](/zh/skills/)。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/skills` | token | 全部已发现技能（内置 + 已安装）。 |
| `GET` | `/api/skills/{id}` | token | 单个技能详情（manifest + 正文）。 |
| `GET` | `/api/skills/market` | token | 市场合并视图：远程目录与本地安装状态联表。 |
| `POST` | `/api/skills/upload` | token | 安装上传的 `.fys`（multipart）。 |
| `POST` | `/api/skills/upload-native` | token | 从本地路径安装 `.fys`（请求体 `{path}`）。仅限桌面端。 |
| `POST` | `/api/skills/{id}/install` | token | 从已配置目录安装（要求签名 + SHA-256 验证通过）。 |
| `POST` | `/api/skills/{id}/update` | token | 更新到目录最新版（同样的验证）。 |
| `PATCH` | `/api/skills/{id}/enabled` | token | 切换启用状态。请求体 `{enabled}`。内置技能返回 409。 |
| `DELETE` | `/api/skills/{id}` | token | 卸载一个已安装技能。 |

## 账号

用于商店鉴权调用的云账号登录与用户中心——OAuth 2.1 客户端 + 强制 PKCE（配置了 `fengyu.store.client-secret` 时叠加 `client_secret_post`，与 Store 的机密 `fengyu-desktop` 注册一致）：access token 仅存内存，refresh token 仅存操作系统凭据库。基址 `/api/account`。`/store-profile`、`/profile`、`/password`、`/library`、`/organizations`、`/sessions`、`/devices` 路由经由该 access token 实时代理已登录用户的 Store 数据，未登录时返回 401。

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/account/me` | token | 当前账号；未登录时为本地虚拟用户。 |
| `POST` | `/api/account/sign-in` | token | 启动浏览器登录 → `{attemptId, authorizationUrl}`。回调服务器绑定一次性随机 loopback 端口。 |
| `GET` | `/api/account/sign-in/{attemptId}` | token | 轮询登录尝试 → `{status: pending|completed|failed, user?, error?}`。 |
| `POST` | `/api/account/sign-out` | token | 吊销并清除所有令牌副本；回到本地虚拟用户。 |
| `GET` | `/api/account/store-profile` | token | 实时 Store 用户资料，含 Infinia 等级（`beeLevel` 0–4）与 `createdAt`。 |
| `PUT` | `/api/account/profile` | token | 修改 Store 显示名称；本地绑定在下一次 `/me` 即生效。 |
| `PUT` | `/api/account/password` | token | 修改 Store 密码（currentPassword + newPassword 8–128 位）。 |
| `GET` | `/api/account/library` | token | 来自 Store 的收藏、授权与安装遥测。 |
| `GET` | `/api/account/organizations` | token | 用户加入的组织。 |
| `GET` | `/api/account/sessions` | token | 活跃授权会话（clientId、kind、createdAt）。 |
| `DELETE` | `/api/account/sessions/{sessionId}` | token | 撤销一个会话 → 204。 |
| `GET` | `/api/account/devices` | token | 已注册设备及其撤销状态。 |
| `DELETE` | `/api/account/devices/{deviceId}` | token | 撤销一个设备 → 204。 |

### 已废弃的 `/api/plugin-market` 别名

RC 之前的 `/api/plugin-market` 接口保留为兼容层：生命周期端点（`/upload`、`/upload-native`、`/inspect`、`/inspect-native`、`/{id}/enabled`、`DELETE /{id}`）按 1:1 转发到 `/api/plugin-packages` 并附带 `Deprecation` 头；其目录端点已被统一商店取代，返回 `410 Gone` 并给出替代路径：`GET /api/plugin-market` → `/api/plugin-store/catalog`，`POST /{id}/install` / `POST /{id}/update` → `/api/plugin-store/{uid}/install` / `/api/plugin-store/{uid}/update`。

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
| `POST` | `/api/agent/runs/{runId}/resume` | token | 恢复失败、取消或需要恢复的运行中尚未完成的步骤，并要求重新审阅计划；重启恢复会复用稳定的步骤调用 ID，剩余步骤若含会话级文件授权则不可恢复。 |
| `GET` | `/api/agent/tasks` | token | 列出当前用户最近持久化的后台任务快照与输出，包含 `priority`、区分 `queued`/`running`、排队耗时，并在正文开始后包含开始时间与运行耗时。宿主同时运行 16 个正文、全局最多排队 128 个、单个所有者最多排队 32 个；重启时排队或运行中的任务转为不重放的失败。 |
| `GET` | `/api/agent/tasks/capacity` | token | 返回全局 `running`/`queued` 数量与限制、交互/普通/批处理计数、全局批处理/非交互限制（64/96）、剩余接纳容量、当前用户占用及对应的 16/24/32 限制、`activeOwners`、`oldestQueueWaitMs` 及按优先级的 `oldestInteractiveQueueWaitMs`/`oldestNormalQueueWaitMs`/`oldestBatchQueueWaitMs`、`saturated` 和 `schedulingPolicy`（`owner-round-robin-weighted-priority`）；不暴露其他用户的任务详情。队列接纳失败返回可重试 HTTP 429，并以 `capacityScope` 区分 `owner`、`global`、`owner-priority` 或 `global-priority` 上限；优先级预留失败还包含 `capacityPriority`。 |
| `GET` | `/api/agent/tasks/{taskId}?timeoutMs=` | token | 返回当前用户拥有的任务快照，并可选择等待最多 60 秒直至终态。 |
| `DELETE` | `/api/agent/tasks/{taskId}` | token | 在开始前取消当前用户拥有的排队任务，或协作式取消运行中任务。 |
| `GET` | `/api/agent/schedules` | token | 列出活跃的持久化工作流调度。每项包含 `nextFireAt`、`fires`、合并后的 `missedFires`、最近任务/错误、过期时间与沙箱姿态。 |
| `POST` | `/api/agent/schedules` | token | 通过 `{workflowId, inputs?, intervalSeconds?, recurring?, fireImmediately?}` 持久化调度；工作流必须已发布且输入有效。 |
| `DELETE` | `/api/agent/schedules/{scheduleId}` | token | 持久化取消一个活跃调度。 |
| `GET` | `/api/agent/webhook-triggers` | token | 列出当前用户活跃的持久化 Webhook 触发器；永不返回明文密钥。 |
| `POST` | `/api/agent/webhook-triggers` | token | 通过 `{workflowId, name?, defaultInputs?, permissionMode?}` 创建，并仅在本次响应中返回明文密钥；工作流必须已发布且不能依赖临时文件授权。 |
| `GET` | `/api/agent/webhook-triggers/{triggerId}/deliveries?limit=` | token | 列出当前用户所拥有且仍活跃的触发器最近投递生命周期；默认 20 条、范围 1–100。记录只含任务、状态、时间、错误及是否提供幂等键，不含正文、密钥、事件 ID 或哈希。 |
| `POST` | `/api/agent/webhook-triggers/{triggerId}/rotate-secret` | token | 立即使旧密钥失效，并仅在本次响应中返回新密钥。 |
| `DELETE` | `/api/agent/webhook-triggers/{triggerId}` | token | 持久化禁用一个活跃触发器。 |
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
| `PUT` | `/api/workflows/{workflowId}` | token | 通过 `{name, description, inputSchema, plan, layout?, graph?, expectedRevision?}` 替换可编辑草稿并递增修订号。匹配的 `expectedRevision` 可防止旧编辑器覆盖新内容；当前已发布快照保持不变。 |
| `POST` | `/api/workflows/{workflowId}/publish` | token | 通过 `{published, expectedRevision?}` 发布当前草稿（或取消发布）。发布会创建不可变快照；修订号过期时返回 HTTP 409。 |
| `GET` | `/api/workflows/{workflowId}/revisions` | token | 列出不可变的已发布修订，并标识当前生效快照。 |
| `GET` | `/api/workflows/{workflowId}/revisions/{revision}` | token | 读取一个已发布快照。 |
| `POST` | `/api/workflows/{workflowId}/revisions/{revision}/restore` | token | 通过 `{expectedRevision?}` 把快照恢复成新的可编辑草稿；重新发布前不改变当前生效快照。 |
| `DELETE` | `/api/workflows/{workflowId}` | token | 在同一事务中取消其活跃调度和 Webhook 触发器并删除定义，返回 `{ok, cancelledSchedules, cancelledWebhookTriggers}`。 |
| `POST` | `/api/workflows/{workflowId}/run` | token | 使用 `{inputs, config}` 人工运行并返回 `{runId}`；通过标准智能体 SSE 流观察。 |
| `POST` | `/api/workflow-hooks/{triggerId}` | Webhook 密钥 | 携带 `X-FengYu-Webhook-Secret` 提交最大 256 KiB 的 JSON 对象输入覆盖；可选加 `X-FengYu-Event-Id`（最长 200 字符）实现 at-most-once 接纳。新事件返回 HTTP 202，重复事件返回 HTTP 200 及原投递状态/任务。后台队列已满时返回带 `Retry-After: 1` 的可重试 HTTP 429；由于任务尚未接纳，事件 ID 声明会被释放以便安全重试。此端点不使用启动 token。 |

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
