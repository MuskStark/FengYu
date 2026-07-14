---
title: SSE 事件
description: Infinia 两条 SSE 流——GET /api/ai/stream（对话）与 GET /api/agent/stream（智能体）——的完整事件分类体系：每个事件名、其 payload 结构，以及触发时机。
lang: zh-CN
---

# SSE 事件

Infinia 通过[服务器推送事件](https://developer.mozilla.org/zh-CN/docs/Web/API/Server-sent_events)流式输出两类长时间运行的工作：对话轮次与智能体运行。两条流都通过一个查询 id（`?streamId=` 或 `?runId=`）打开，并用 `X-FengYu-Token` 头进行鉴权——**没有** `?token=` 参数。

每个事件都是一个 SSE 帧，其 `event:` 行命名类型，`data:` 行承载 JSON payload。两条流都以相同的起始帧——一个 `:connected` 注释心跳——打开，它确认流已就绪，然后任何事件才会到来。

```text
: connected

event: <type>
data: { ...json... }
```

关于如何启动这些流（`POST /api/ai/chat`、`POST /api/agent/run`），请参见 [REST API](/zh/reference/rest-api)。

## 对话流

`GET /api/ai/stream?streamId=<uuid>`——由 `POST /api/ai/chat` 启动的一轮对话所对应的流。参见 [AI 对话](/zh/guide/ai-chat)。

| Event | Data 结构 | 时机 |
| --- | --- | --- |
| `token` | `{text}` | 助手回复的一个片段。按顺序拼接以重建完整消息。 |
| `thinking` | `{text}` | 模型思维链的一个片段。渲染为折叠卡片。 |
| `tool`（call） | `{phase:"call", name, arguments}` | 模型决定调用某个工具。`arguments` 是 JSON 序列化后的参数字符串。 |
| `tool`（result） | `{phase:"result", id, success, output}` | 工具已返回。`success:false` 时 `output` 中携带错误。 |
| `done` | `{text, tokens, tps}` | 本轮完成。`text` 是完整回复；`tokens` 是计数；`tps` 是每秒 token 数。 |
| `error` | `{message}` | 运行失败。此帧之后流结束。 |

一个具有代表性的对话流：

```text
: connected

event: token
data: {"text":"Let me check "}

event: thinking
data: {"text":"The user wants sheet names; I'll call excel_analyze."}

event: tool
data: {"phase":"call","name":"excel_analyze","arguments":"{\"filePath\":...}"}

event: tool
data: {"phase":"result","id":"...","success":true,"output":"..."}

event: token
data: {"text":"the workbook has 3 sheets."}

event: done
data: {"text":"Let me check the workbook has 3 sheets.","tokens":42,"tps":18.6}
```

`tool` 事件对两个阶段使用同一个名字，并通过 `phase` 字段加以区分。内置的 `@FengYuTool` 与插件的 `aiTools` 在传输上无法区分——参见 [AI 工具](/zh/plugins/ai-tools)。

## 智能体流

`GET /api/agent/stream?runId=<uuid>`——由 `POST /api/agent/run` 启动的一次智能体运行所对应的流。参见 [AI 智能体](/zh/guide/ai-agent)。

| Event | Data 结构 | 时机 |
| --- | --- | --- |
| `plan_token` | 计划文本片段 | 模型正逐 token 流式输出草稿计划。 |
| `plan_ready` | `{ plan: AgentPlan }` | 计划已定稿，等待复核。 |
| `plan_approval_requested` | 关卡详情 | 运行器已暂停，等待你在执行前批准该计划。 |
| `step_start` | 步骤描述符 | 某个步骤已开始执行。 |
| `step_complete` | 步骤结果 | 某个步骤已完成。 |
| `step_approval_requested` | 关卡详情 | 某个步骤在运行前需要你的批准。 |
| `complete` | 最终结果 | 整次运行已成功完成。 |
| `error` | `{message}` | 运行失败。此帧之后流结束。 |

端到端的顺序，连同两道审批关卡：

```text
: connected

event: plan_token
data: {"text":"1. Read the workbook"}

event: plan_ready
data: {"plan":{ /* AgentPlan */ }}

event: plan_approval_requested
data: { /* gate details */ }

# → POST /api/agent/{runId}/approve  (releases the gate)

event: step_start
data: { /* step descriptor */ }

event: step_complete
data: { /* step result */ }

event: complete
data: { /* final result */ }
```

### 审批关卡

`plan_approval_requested` 与 `step_approval_requested` 都由同一个 endpoint 放行——`POST /api/agent/{runId}/approve`。不发送请求体即按原样批准；发送一个编辑过的 `AgentPlan` 请求体即可覆盖草稿。取消则用 `POST /api/agent/{runId}/cancel`；取消是协作式的，因此运行器会在下一个安全点停下，且流不会发出 `complete` 就结束。参见 [AI 智能体——审批关卡](/zh/guide/ai-agent#approval-gates)。

## 约定

- 每条流上的第一帧是 `: connected` 注释——它不是一个事件，只是一个心跳。
- 每一行 `data:` 都是单个 JSON 对象。请用 `JSON.parse` 解析；不要对所列字段之外的字符串字段做任何假设。
- 一个 `error` 帧永远是终结性的——服务器会在其后立即关闭该流。
- 流不可恢复。如果连接掉线，请开启一次新的运行；`streamId` / `runId` 都是一次性使用的。

## 下一步

- [REST API](/zh/reference/rest-api)——启动每条流的 endpoint。
- [AI 对话](/zh/guide/ai-chat)——对话事件如何被渲染（思考卡片、工具区块）。
- [AI 智能体](/zh/guide/ai-agent)——「规划-执行」流程与审批关卡。
