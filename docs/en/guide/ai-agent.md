---
title: AI Agent
description: Run the plan-and-execute agent — give it a goal, review the plan at approval gates, watch each step stream over SSE, and cancel mid-run.
lang: en
---

# AI Agent

The AI Agent is a **plan-and-execute** runner. Give it a goal in plain language and it drafts a multi-step plan, asks you to approve the plan (and optionally each step), then executes dependency-ready steps concurrently — streaming progress back over SSE. It is built on the same chat backends and reuses the host's aggregated tools, including MCP tools, so anything callable from chat is callable from an agent run.

## Request flow

A run starts with a goal plus an `AgentConfig`, then streams over SSE keyed by `runId`.

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
Browser `EventSource` cannot set custom headers. The desktop UI therefore opens the stream with
`?runId=...&token=...`; non-browser clients may instead use `X-FengYu-Token`.
:::

### Caller-supplied workflow

The request body accepts an optional `workflow` (an `AgentPlan`). Omit it to let the active model
plan from `goal`; supply it to drive **deterministic execution** — the runner validates the supplied
plan (model- or user-authored) before any tool runs, so the HTTP API can execute a known graph
without depending on LLM planning.

```json
{
  "goal": "Split invoices.xlsx by the Region column",
  "config": { ... },
  "workflow": { "steps": [ ... ] }
}
```

The desktop UI's visual canvas (below) compiles to exactly this `workflow` field, so the canvas and
the AI plan path are peers against the same runner.

### Visual canvas (desktop UI)

The **Ai Agent** view ships a Vue Flow canvas alongside the goal input. Drag tools from the palette
onto the canvas, connect them into a graph, and run — `workflow.ts` compiles the graph into the
`AgentPlan` sent to `POST /api/agent/run`. This is the no-code peer to letting the model plan: the
same runner, validation, and step-result references apply (e.g. `steps.N.result` or `last.result`,
substituted into a later step's arguments). Tools are disabled during planning so the model only
structures the workflow, never executes tools while planning.

Canvas edges compile to each step's `dependsOn` list. Steps in the same dependency level run on
virtual threads in parallel; a dependent step starts only after all prerequisites complete.

## End-to-end flow

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

## SSE events

Every event is an SSE frame named after its type. See [SSE Events](/en/reference/sse-events) for the full taxonomy.

| Event | When | Carries |
| --- | --- | --- |
| `plan_token` | The model is streaming the draft plan | plan text chunks |
| `plan_ready` | The plan is finalized | the full `AgentPlan` |
| `plan_approval_requested` | The runner is waiting for you to approve the plan before executing | gate details |
| `step_start` | A step has begun | the step descriptor |
| `step_complete` | A step finished | the step result |
| `step_approval_requested` | A step needs your approval before it runs | gate details |
| `complete` | The whole run finished | the final result |
| `error` | The run failed | `{message}` — the stream ends after this frame |

## Approval gates

The agent pauses at approval gates and will not proceed until you release it. Send approval to the run (not the stream):

```text
POST /api/agent/{runId}/approve
  X-FengYu-Token: <token>

# Optional — send an edited plan to override the model's draft:
  Content-Type: application/json
  { /* an edited AgentPlan */ }
```

- With **no body**, the current plan is approved as-is.
- With an **edited `AgentPlan` body**, the runner adopts your edits before continuing — useful for trimming steps, reordering, or tightening instructions.

The same endpoint releases both `plan_approval_requested` and `step_approval_requested` gates.

## Cancel

Cancel is **cooperative** — the runner checks the flag and stops at the next safe point, so a cancel may not be instant.

```text
POST /api/agent/{runId}/cancel
  X-FengYu-Token: <token>
```

After a cancel the stream ends; the run does not emit `complete`.

## Durable history and resume

Run snapshots and ordered lifecycle events are persisted. `GET /api/agent/runs` lists history and
`GET /api/agent/runs/{runId}` returns the plan, executions, and audit events. A failed, cancelled,
or restart-interrupted run can be resumed with `POST /api/agent/runs/{runId}/resume`. Completed
steps are reused, unfinished steps remain, and the restored plan always pauses for review before
execution.

For independent goals, `POST /api/agent/batch` starts between one and eight isolated run
lifecycles concurrently and returns their `runIds`. Each run keeps separate approvals, cancellation,
history, and SSE observation.

## Available tools

`GET /api/agent/tools` returns the orchestrable tool list the agent can call during its steps:

```text
GET /api/agent/tools
  X-FengYu-Token: <token>

  ◄── 200 [
        { "name": "...", "description": "...", "inputSchema": { /* JSON Schema */ } },
        ...
      ]
```

The list is built from the host's aggregated Spring AI `ToolCallback[]` — every built-in
`@FengYuTool`, every enabled plugin's declared `aiTools`, and every configured MCP server tool.
Plugin and MCP tools are indistinguishable from built-ins on the wire (see
[AI Tools](/en/plugins/ai-tools)).

## Next steps

- [AI Chat](/en/guide/ai-chat) — the conversational counterpart to the agent.
- [Configuration](/en/guide/configuration) — pick the backend the agent runs against.
- [AI Tools](/en/plugins/ai-tools) — how tools become orchestrable from agent runs.
