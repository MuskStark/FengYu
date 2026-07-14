---
title: AI 对话
description: 使用多后端 AI 对话——支持流式（SSE）回复、思考卡片、工具调用展示与已保存的会话。
lang: zh-CN
---

# AI 对话

AI 对话是 Infinia 中的会话界面。选择一个后端，发送一条提示，然后以服务器推送事件（SSE）流的方式逐 token 阅读回复。在模型工作的过程中，它可以流式输出自己的思考内容，并内联调用插件工具；完成的会话会被持久化，以便你之后重新打开。

## 后端

对话运行在四个后端之上，可在[配置](/zh/guide/configuration)的 AI 配置下选择：

| 模式 | 后端 | 说明 |
| --- | --- | --- |
| `local` | Ollama | 通过 Ollama 的本地 HTTP API 与**外部 `ollama serve` 进程**通信。后端不会在进程内加载 GGUF。 |
| `openai` | OpenAI | 标准 OpenAI API。 |
| `anthropic` | Anthropic | Anthropic Messages API。 |
| `deepseek` | DeepSeek | DeepSeek **兼容 OpenAI**——通过 OpenAI 风格的适配器驱动。 |

当前生效的模式即 `PUT /api/ai/config` 最后持久化的那个；它在运行时通过 `BackendReactivator.reactivate()` 热切换，因此模式切换无需重启即可生效。

## 请求流程

一轮对话是一个两步请求：先启动运行，再打开 SSE 流。

```text
POST /api/ai/chat
  Content-Type: application/json
  X-FengYu-Token: <token>
  { "messages": [ { "role": "user", "content": "Summarize this workbook" } ] }

  ◄── 200 { "streamId": "<uuid>" }

GET /api/ai/stream?streamId=<uuid>
  X-FengYu-Token: <token>
  Accept: text/event-stream

  ◄── SSE stream (see below)
```

流上的第一帧是一个 `:connected` 注释心跳——它在任何事件到来之前确认流已打开。

::: warning
SSE 端点是 `GET /api/ai/stream?streamId=...`。**没有** `?token=` 查询参数。请用 `X-FengYu-Token` 头来认证流请求，这与其他所有端点一致。
:::

## SSE 事件

每个事件都是一个以其类型命名的 SSE 帧。完整的分类体系请参见 [SSE 事件](/zh/reference/sse-events)。

| 事件 | 数据 | 含义 |
| --- | --- | --- |
| `token` | `{text}` | 助手回复的一个片段。逐个拼接以重建完整消息。 |
| `thinking` | `{text}` | 模型思维链的一个片段。 |
| `tool` | `call`：`{phase:"call", name, arguments}` | 模型决定调用一个工具（插件或内置）。 |
| `tool` | `result`：`{phase:"result", id, success, output}` | 工具返回结果。`success:false` 时 `output` 中携带错误信息。 |
| `done` | `{text, tokens, tps}` | 本轮完成。`text` 是完整回复；`tps` 是每秒 token 数。 |
| `error` | `{message}` | 运行失败。此帧之后流结束。 |

一个具有代表性的流：

```text
: connected

event: token
data: {"text":"Let me check "}

event: tool
data: {"phase":"call","name":"excel_analyze","arguments":"{\"filePath\":...}"}

event: tool
data: {"phase":"result","id":"...","success":true,"output":"..."}

event: token
data: {"text":"the workbook has 3 sheets."}

event: done
data: {"text":"Let me check the workbook has 3 sheets.","tokens":42,"tps":18.6}
```

### 渲染

- **思考内容**渲染为折叠卡片——每段思考一张卡片，点击可展开，这样它平时不碍事，需要时才展开。
- **工具调用**内联渲染。一个 `call` 帧显示工具名和参数；匹配的 `result` 帧用输出或错误更新同一个区块。内置的 `@FengYuTool` 与插件的 `aiTools` 在传输上无法区分（参见 [AI 工具](/zh/plugins/ai-tools)）。

## 会话

会话存储在后端。所有端点都要求带 `X-FengYu-Token` 头。

| 方法 + 路径 | 请求体 / 查询 | 返回 |
| --- | --- | --- |
| `GET /api/ai/conversations` | — | 会话摘要列表，**按时间倒序**（最新在前）。 |
| `GET /api/ai/conversations/{id}` | — | 单个会话（标题 + 消息）。 |
| `POST /api/ai/conversations` | `{title, messages}` | 创建的会话及其 `id`。 |
| `PUT /api/ai/conversations/{id}` | `{title, messages}` | 整体替换标题和消息。 |
| `DELETE /api/ai/conversations/{id}` | — | 删除该会话。 |

`PUT` 是一次整体替换——请发送你希望存储的完整 `messages` 数组，而不是增量。

## 下一步

- [AI 智能体](/zh/guide/ai-agent)——基于同一后端构建的「规划-执行」智能体。
- [配置](/zh/guide/configuration)——设置当前模式与 API 密钥。
- [AI 工具](/zh/plugins/ai-tools)——插件工具如何变得可从对话中调用。
