---
title: SSE Events
description: The complete taxonomy of server-sent events for the two Infinia SSE streams — GET /api/ai/stream (chat) and GET /api/agent/stream (agent) — every event name, its payload shape, and when it fires.
lang: en
---

# SSE Events

Infinia streams two kinds of long-running work over [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events): chat turns and agent runs. Both streams are opened with a query id (`?streamId=` or `?runId=`) and authenticated with the `X-FengYu-Token` header — there is **no** `?token=` parameter.

Every event is an SSE frame whose `event:` line names the type and whose `data:` line carries a JSON payload. Both streams open with the same first frame — a `:connected` comment heartbeat — which confirms the stream is open before any events arrive.

```text
: connected

event: <type>
data: { ...json... }
```

See [REST API](/en/reference/rest-api) for how the streams are started (`POST /api/ai/chat`, `POST /api/agent/run`).

## Chat stream

`GET /api/ai/stream?streamId=<uuid>` — the stream for a chat turn started by `POST /api/ai/chat`. See [AI Chat](/en/guide/ai-chat).

| Event | Data shape | When |
| --- | --- | --- |
| `token` | `{text}` | A chunk of the assistant's reply. Concatenate in order to rebuild the message. |
| `thinking` | `{text}` | A chunk of the model's chain-of-thought. Rendered as collapsed cards. |
| `tool` (call) | `{phase:"call", name, arguments}` | The model decided to call a tool. `arguments` is the JSON-serialized argument string. |
| `tool` (result) | `{phase:"result", id, success, output}` | The tool returned. `success:false` carries an error in `output`. |
| `done` | `{text, tokens, tps}` | The turn is complete. `text` is the full reply; `tokens` is the count; `tps` is tokens/sec. |
| `error` | `{message}` | The run failed. The stream ends after this frame. |

A representative chat stream:

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

The `tool` event uses the same name for both phases and disambiguates via the `phase` field. Built-in `@FengYuTool`s and plugin `aiTools` are indistinguishable on the wire — see [AI Tools](/en/plugins/ai-tools).

## Agent stream

`GET /api/agent/stream?runId=<uuid>` — the stream for an agent run started by `POST /api/agent/run`. See [AI Agent](/en/guide/ai-agent).

| Event | Data shape | When |
| --- | --- | --- |
| `plan_token` | plan text chunk | The model is streaming the draft plan, token by token. |
| `plan_ready` | `{ plan: AgentPlan }` | The plan is finalized and ready for review. |
| `plan_approval_requested` | gate details | The runner is paused, waiting for you to approve the plan before executing. |
| `step_start` | step descriptor | A step has begun executing. |
| `step_complete` | step result | A step finished. |
| `step_approval_requested` | gate details | A step needs your approval before it runs. |
| `complete` | final result | The whole run finished successfully. |
| `error` | `{message}` | The run failed. The stream ends after this frame. |

The end-to-end ordering, with the two approval gates:

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

### Approval gates

Both `plan_approval_requested` and `step_approval_requested` are released by the same endpoint — `POST /api/agent/{runId}/approve`. Send no body to approve as-is, or an edited `AgentPlan` body to override the draft. Cancel with `POST /api/agent/{runId}/cancel`; cancel is cooperative, so the runner stops at the next safe point and the stream ends without `complete`. See [AI Agent — Approval gates](/en/guide/ai-agent#approval-gates).

## Conventions

- The first frame on every stream is the `: connected` comment — it is not an event, just a heartbeat.
- Every `data:` line is a single JSON object. Parse it with `JSON.parse`; do not assume string fields beyond those listed.
- An `error` frame is always terminal — the server closes the stream immediately after.
- Streams are not resumable. If the connection drops, start a new run; `streamId` / `runId` are single-use.

## Next steps

- [REST API](/en/reference/rest-api) — the endpoints that start each stream.
- [AI Chat](/en/guide/ai-chat) — how the chat events are rendered (thinking cards, tool blocks).
- [AI Agent](/en/guide/ai-agent) — the plan-and-execute flow and approval gates.
