---
title: AI 智能体
description: 运行「规划-执行」智能体——给它一个目标，在审批关卡审阅计划，通过 SSE 观看每一步的流式进度，并可中途取消。
lang: zh-CN
---

# AI 智能体

AI 智能体是一个**「规划-执行」**运行器。用自然语言给它一个目标，它会起草一个多步骤计划，请求你批准该计划（可选地批准每一步），然后并行执行依赖已满足的步骤——并通过 SSE 流式回传进度。它构建在与对话相同的后端之上，并复用宿主聚合后的工具集（包括 MCP 工具），因此凡能从对话中调用的工具，也能从智能体运行中调用。

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
浏览器 `EventSource` 无法设置自定义请求头，因此桌面 UI 使用
`?runId=...&token=...` 打开流；非浏览器客户端也可以使用 `X-FengYu-Token`。
:::

### 调用方提供的 workflow

请求体接受一个可选的 `workflow`（一个 `AgentPlan`）。省略时由当前模型根据 `goal` 规划；提供时则驱动**确定性执行**——运行器会在任何工具运行前校验所提供的计划（模型或用户编写均可），因此 HTTP API 可以执行一个已知图，而不依赖 LLM 规划。

```json
{
  "goal": "按 Region 列拆分 invoices.xlsx",
  "config": { ... },
  "workflow": { "steps": [ ... ] }
}
```

桌面 UI 的可视化画布（见下）编译后填入的就是这个 `workflow` 字段，因此画布与 AI 规划路径对同一个运行器而言是对等的。

### 可视化画布（桌面 UI）

**AI 智能体**视图在目标输入框旁内置了一个 Vue Flow 画布。把工具从面板拖到画布上，连成图后运行——`workflow.ts` 会把图编译成发送给 `POST /api/agent/run` 的 `AgentPlan`。这是「让模型规划」的无代码对等路径：使用同一个运行器、同一套校验与步骤结果引用（如 `steps.N.result` 或 `last.result`，会被替换进后续步骤的参数中）。规划期间工具被禁用，因此模型只负责组织工作流，绝不在规划时执行工具。

画布连线会编译为每个步骤的 `dependsOn`。同一依赖层级的步骤会在虚拟线程上并行运行；后继步骤只有在所有前置步骤完成后才会启动。

### 可复用工作流：人工与 AI 调用

画布可以把图持久化为可复用工作流，而不只是发送一次性的 `AgentPlan`。每个定义保存名称、
描述、JSON Schema 输入契约、计划、发布状态和修订号。可在目标或任意节点参数中使用
<code v-pre>{{inputs.name}}</code>：参数值完全等于占位符时会保留原始 JSON 类型，嵌入文本时则渲染为字符串。
原有的 <code v-pre>{{steps.N.result...}}</code> 引用继续负责连接步骤输出。

- **人工调用：** 选择已保存工作流，输入 JSON 对象并运行。宿主绑定输入、校验必填字段和
  基础 JSON Schema 类型，然后启动一次普通智能体运行。
- **AI 调用：** 发布工作流后，它会立即以 `run_workflow_<id>` 出现在实时 Spring AI 工具目录中，
  并把工作流输入 Schema 作为工具 Schema。模型调用时绑定同一套输入，并复用同一个 DAG
  运行器、持久运行历史和工具回调。

AI 调用位于同步工具调用边界内，无法在内部暂停等待人工审批，因此已发布工作流沿用外层
对话工具调用已经授予的权限；人工运行仍保留通常的逐步审批策略。保存的定义不能嵌套工作流
工具，以避免递归调用，并保持执行与审计边界清晰。

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

## 持久历史与恢复

运行快照和有序生命周期事件会持久化。`GET /api/agent/runs` 列出历史，
`GET /api/agent/runs/{runId}` 返回计划、执行结果与审计事件。失败、取消或因应用重启而
中断的运行可通过 `POST /api/agent/runs/{runId}/resume` 恢复：已完成步骤直接复用，
只执行未完成部分，并且恢复后的计划必定先暂停等待审阅。

对于相互独立的目标，`POST /api/agent/batch` 可并行启动 1–8 个隔离的运行生命周期并返回
各自的 `runIds`。每个运行分别维护审批、取消、历史和 SSE 观察。

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

该列表由宿主聚合的 Spring AI `ToolCallback[]` 构成——每一个内置的 `@FengYuTool`、
每一个已启用插件所声明的 `aiTools`，以及已配置 MCP 服务器提供的工具。插件与 MCP
工具在传输上与内置工具无法区分（参见 [AI 工具](/zh/plugins/ai-tools)）。

## 下一步

- [AI 对话](/zh/guide/ai-chat)——智能体的会话式对应物。
- [配置](/zh/guide/configuration)——选择智能体运行所使用的后端。
- [AI 工具](/zh/plugins/ai-tools)——工具如何变得可从智能体运行中编排。
