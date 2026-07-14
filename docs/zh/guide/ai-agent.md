---
title: AI 智能体
description: 运行「规划-执行」智能体——给它一个目标，在审批关卡审阅计划，通过 SSE 观看每一步的流式进度，并可中途取消。
lang: zh-CN
---

# AI 智能体

AI 智能体是一个**「规划-执行」**运行器。用自然语言给它一个目标，它会起草一个多步骤计划，请求你批准该计划（可选地批准每一步），然后逐个执行这些步骤——并通过 SSE 流式回传进度。它构建在与对话相同的后端之上，并复用宿主聚合后的工具集，因此凡能从对话中调用的工具，也能从智能体运行中调用。

## 请求流程

一次运行以一个目标外加一个 `AgentConfig` 开始，然后通过以 `runId` 为键的 SSE 流式传输。

```text
POST /api/agent/run
  Content-Type: application/json
  X-FengYu-Token: <token>
  { "goal": "Split invoices.xlsx by the Region column", "config": { ... } }

  ◄── 200 { "runId": "<uuid>" }

GET /api/agent/stream?runId=<uuid>
  X-FengYu-Token: <token>
  Accept: text/event-stream

  ◄── SSE stream (see below)
```

::: tip
和对话一样，流通过 `?runId=` 打开——绝不使用 `?token=`。请用 `X-FengYu-Token` 头认证。
:::

## 端到端流程

```text
goal
  │
  ▼
plan_token ──► plan_ready ──► plan_approval_requested
                                   │
                                   │  POST /api/agent/{runId}/approve
                                   ▼
                          step_start ──► step_complete
                                   │              │
                                   │   step_approval_requested ──► approve
                                   ▼
                               complete
```

## SSE 事件

每个事件都是一个以其类型命名的 SSE 帧。完整的分类体系请参见 [SSE 事件](/zh/reference/sse-events)。

| 事件 | 何时 | 携带 |
| --- | --- | --- |
| `plan_token` | 模型正在流式输出草稿计划 | 计划文本片段 |
| `plan_ready` | 计划已定稿 | 完整的 `AgentPlan` |
| `plan_approval_requested` | 运行器在执行前等待你批准计划 | 关卡详情 |
| `step_start` | 某一步已开始 | 该步描述符 |
| `step_complete` | 某一步已完成 | 该步结果 |
| `step_approval_requested` | 某一步在运行前需要你的批准 | 关卡详情 |
| `complete` | 整次运行完成 | 最终结果 |
| `error` | 运行失败 | `{message}`——此帧之后流结束 |

## 审批关卡

智能体在审批关卡处暂停，在你放行之前不会继续。向运行（而非流）发送批准：

```text
POST /api/agent/{runId}/approve
  X-FengYu-Token: <token>

# 可选——发送一份已编辑的计划以覆盖模型的草稿：
  Content-Type: application/json
  { /* an edited AgentPlan */ }
```

- **不带请求体**时，当前计划按原样批准。
- **带一份已编辑的 `AgentPlan` 请求体**时，运行器在继续之前采纳你的修改——适用于裁剪步骤、重排序或收紧指令。

同一个端点同时放行 `plan_approval_requested` 与 `step_approval_requested` 关卡。

## 取消

取消是**协作式**的——运行器检查标志位并在下一个安全点停止，因此取消可能不会立即生效。

```text
POST /api/agent/{runId}/cancel
  X-FengYu-Token: <token>
```

取消后流结束；运行不会发出 `complete`。

## 可用工具

`GET /api/agent/tools` 返回智能体在其步骤中可调用的可编排工具列表：

```text
GET /api/agent/tools
  X-FengYu-Token: <token>

  ◄── 200 [
        { "name": "...", "description": "...", "inputSchema": { /* JSON Schema */ } },
        ...
      ]
```

该列表由宿主聚合的 Spring AI `ToolCallback[]` 构成——每一个内置的 `@FengYuTool` 以及每一个已启用插件所声明的 `aiTools`。插件工具在传输上与内置工具无法区分（参见 [AI 工具](/zh/plugins/ai-tools)）。

## 下一步

- [AI 对话](/zh/guide/ai-chat)——智能体的会话式对应物。
- [配置](/zh/guide/configuration)——选择智能体运行所使用的后端。
- [AI 工具](/zh/plugins/ai-tools)——工具如何变得可从智能体运行中编排。
