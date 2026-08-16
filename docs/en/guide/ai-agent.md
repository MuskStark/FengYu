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

The flow builder's visual canvas (below) compiles to exactly this `workflow` field, so the canvas and
the AI plan path are peers against the same runner.

### Visual flows (Flows view)

The **Flows** view (`/flows`, Flowise-inspired) is the no-code peer to letting the model plan. A
library page lists saved flows and one-click templates; opening one enters the full-workspace flow
builder: a categorized node palette on the left (search + collapsible groups, drag to add), the Vue
Flow canvas in the middle, a node configuration panel on the right, and Flowise-style sticky notes
for annotations. The canvas is a 1:1 replica of Flowise's **AgentFlow v2 canvas** (the dark
canvas from the screenshot), rebuilt in pure Vue on vue-flow after reading the original source:
per-node-type colors straight from `tokens.ts` tint the card (`darken(color, 0.8)`, MUI formula),
the 40px rounded-square icon badge, the 5×20 color-bar input handle, the hover-revealed chevron
output handle, gradient bezier edges (source→target color) with hover delete buttons, the
#1a1a1a dot-grid surface, bottom-center controls with snap/background toggles, and the dark
minimap. Structural edits — node/edge/note add, remove (toolbar, buttons, or the Delete key),
and moves — are all undoable (toolbar buttons or ⌘/Ctrl+Z / ⇧⌘Z), capped at 50 steps. `workflow.ts` compiles the graph into the `AgentPlan` sent to
`POST /api/agent/run` — the same runner, validation, and step-result references apply (e.g.
`steps.N.result` or `last.result`, substituted into a later step's arguments). Tools are disabled
during planning so the model only structures the workflow, never executes tools while planning.

Canvas edges compile to each step's `dependsOn` list. Steps in the same dependency level run on
virtual threads in parallel; a dependent step starts only after all prerequisites complete.

### Chat with the flow (one tool-call mode)

Borrowing Flowise's chat-with-your-chatflow loop, the builder ships a docked chat panel
(bottom-right). Sending a message binds the turn to the flow being edited: the backend
exposes that flow — **draft or published** — to the model as `run_current_flow`, an ordinary
tool call in the very chat tool-call loop that powers AI Chat (same permission modes, same
approval gates, same SSE `tool` events; the turn auto-saves pending edits first). AI Chat
itself reaches published flows the same way via `run_workflow_<id>` — chat and the canvas
are peers over one tool-call runtime, not two execution models.

### Reusable workflows: manual and AI invocation

The builder can persist a graph as a reusable workflow instead of sending a one-off `AgentPlan`.
Each definition stores a name, description, JSON Schema input contract, the compiled plan, the
authored canvas graph, publication state, and revision. Use <code v-pre>{{inputs.name}}</code> in the goal or any node argument; an exact placeholder keeps
the JSON value's original type, while a placeholder embedded in text is rendered as a string.
Existing <code v-pre>{{steps.N.result...}}</code> references continue to connect step outputs.

- **Manual:** select the saved workflow, enter an input JSON object, and run it. The host binds the
  inputs, validates required fields and basic JSON Schema types, then starts a normal agent run.
- **AI:** publish the workflow. It immediately appears in the live Spring AI catalog as
  `run_workflow_<id>`, using the workflow input schema as its tool schema. The model's tool call
  binds the same inputs and uses the same DAG runner, persisted run history, and tool callbacks.

AI invocation cannot pause for human approval inside the synchronous tool call, so published
workflow execution uses the permissions already granted to the outer chat tool call. Manual runs
retain the normal per-step approval policy. Workflow tools cannot be nested in saved definitions;
this prevents recursive invocation and keeps execution/audit boundaries explicit.

### Workflow authoring guardrails

The canvas fails at authoring time rather than mid-run, and never silently loses work:

- **Saved graphs.** A definition stores the authored canvas graph verbatim — nodes, edges, sticky
  notes, and node ids — so a saved flow reopens exactly as it was arranged (node ids are what
  references addressed by node id survive reloads too). Definitions saved
  before graph persistence reconstruct the canvas from the compiled plan + layout.
- **Save-time validation.** Saving rejects <code v-pre>{{inputs.*}}</code> references that the
  input schema never declares — a graph that could only ever fail at binding time — and caps
  definitions at 64 steps.
- **Run-time input gating.** The run dialog blocks the start until required workflow inputs are
  filled, naming the missing fields; the host re-validates on `POST /api/workflows/{id}/run`.
- **Unsaved-changes protection.** Switching, creating, or deleting a workflow with unsaved canvas
  edits asks for confirmation first, and closing the tab or leaving the view triggers the
  browser's leave guard.
- **Save a copy.** Every flow card in the library offers a one-click duplicate — the fastest
  path from an existing example to "my version".
- **Per-step results.** The run panel and plan view show each completed step's actual output
  (collapsed behind a *Result* toggle), both for live runs and when reopening a persisted run.
- **Localized errors.** Host validation messages (missing inputs, undeclared references, name
  limits, publication state) surface localized in the UI instead of raw English exceptions.

### One-click templates and run-time pickers

Building a graph from a blank canvas still requires knowing the tools. For the common
"split a workbook, then email each part" scenario the Flows view ships a built-in template
gallery (on the library page and in the builder's empty state): **Excel split → batch email** pre-wires
`excel_complex_config → excel_execute → email_send_batch → confirm_send`, pre-maps every
output reference (including the nested `confirmation.confirmationId`), and ships a run-form
input schema an ordinary user just fills in:

- **File inputs** (`format: "fengyu-file"`) render an upload picker in the run dialog. The
  picked file is granted to every eligible plugin and travels with the run; node arguments
  carry it as an `@file:<input>` placeholder the host swaps for the current plugin's
  FileRef right before dispatch.
- **Shared output folders** (`"x-fengyu-auto": "shared-directory"`) need no user interaction:
  the run mints one host-owned scratch directory and grants it *live* to every eligible
  plugin — files an Excel step writes are immediately readable by a later Email step, on
  every sandbox backend (this is the cross-plugin hand-off that a plugin's private default
  output folder cannot provide).
- **Dynamic option inputs** (`"x-fengyu-enum"` referencing a plugin list tool such as
  `email_accounts_list` or `email_tags_list`) render live dropdowns — the user picks
  "alice@example.com" or a recipient group, never a numeric id.
- **The send step is approval-gated.** `confirm_send` is an `external`-effect tool and the
  template marks it *requires approval*: every permission mode except full-access pauses the
  run at that step, and one click in the run panel releases it — the workflow equivalent of
  the chat confirmation card.

Under the hood, `POST /api/agent/run` and `POST /api/workflows/{id}/run` accept a `files`
array (`{name, refs | nativePath | createSharedDirectory}`); the resolved grants attach to
the run and bind `@file:<name>` placeholders during step dispatch.

## Permission rules & lifecycle hooks

Between the coarse permission modes and each tool call sits a user-configurable guard,
evaluated in a fixed order:

```text
PreToolUse hooks → deny rules → ask rules → allow rules → permission-mode default
```

**Rules** are configured in Settings (one per line) and evaluated order-independently —
a deny always beats an allow, regardless of declaration order:

| Rule | Matches |
| --- | --- |
| `Command(git status)`, `Command(git:*)` | `execute_command` — word-boundary prefix or glob |
| `Tool(excel_*)`, `Tool(browser_navigate)` | tool names (glob) |
| `Effect(read)` | every tool declaring that effect |
| `Mcp(github__*)`, `mcp__github` | MCP tools by qualified name |
| `WebFetch(domain:example.com)` | `web_fetch`/`web_search` on a host or subdomain |

Shell chains are checked per segment: a deny/ask rule matches **any** segment of an
`a && b | c` chain, while an allow rule only grants when **every** segment independently
matches — so `Command(git status)` cannot authorize `git status && rm -rf /`. A
dangerous-command floor (`rm`, `sudo`, `kill`, `git push`, …) voids allow rules; those
commands always ask. A denied call fails its step with the rule's reason, which the
model can see and replan around.

**Hooks** extend the same pipeline. A hook is `{name, event, matcher, type, command|url,
timeoutSeconds, enabled}`; `command` hooks receive the event envelope as JSON on stdin,
HTTP hooks receive it as a POST body:

- `pre_tool_use` — a gate: exit code 2 denies (first stderr line is the reason), stdout
  JSON `{"decision":"deny","reason":"…"}` denies on any exit code; exit 0 (or a JSON
  allow) lets the call proceed.
- `post_tool_use` / `post_tool_use_failure` — observe finished calls (arguments + result).
- `run_complete` / `run_error` — observe agent-run termination.

Hook failures (crash, unknown exit code, timeout) **fail open**: the failure is logged
and the tool call proceeds. FengYu is a local personal tool where an induced hook failure
is not part of the threat model; blocking every call because a hook crashed would turn
the feature into a self-inflicted outage.

**Plugins can contribute hooks** (a `hooks/hooks.json` inside a `.fyp` package, grok-shaped
`{"hooks": {"PreToolUse": […]}}` or FengYu's flat list). Installing or enabling a plugin
never activates its hooks — the user must trust the plugin explicitly
(`POST /api/plugin-hooks/{id}/trust`); untrusting takes effect on the next call. Trusted
plugin hooks run with the plugin's install directory as working directory and receive
`FENGYU_PLUGIN_ROOT`/`FENGYU_PLUGIN_DATA` in their environment, and their names are
namespaced `plugin/<id>/<name>` for audit trails.

## Background tasks

Long workflows no longer occupy the synchronous tool slot. The model can call
`task_submit_workflow(workflowId, inputs)` to launch a published workflow in the
background (it returns a `taskId` immediately), then `task_output(taskId, timeoutMs)` to
poll or block, `task_wait(ids, "any"|"all", timeoutMs)` to wait on up to 20 tasks at
once, and `task_kill(taskId)` to stop a runaway task — cooperative cancellation first,
SIGTERM → SIGKILL escalation for process-backed tasks. The same registry backs
`GET /api/agent/tasks` for the UI.

## Workflow schedules

Published workflows can run on a schedule (`POST /api/agent/schedules`, or the
`task_schedule` tool): a minimum interval of 60 seconds, at most 50 active schedules,
automatic expiry after 7 days, an optional immediate first fire, and
`recurring: false` for a delayed one-shot. Scheduled runs submit ordinary background
tasks, so `task_output`/`task_wait`/`task_kill` and the run panel treat them exactly
like manual ones. Schedules are in-memory — a restart clears them (the deliberate
non-durable default).

## Run history: search, fork, rewind

- **Search** — `GET /api/agent/runs?q=…` filters history by goal/summary/error text.
- **Fork** — `POST /api/agent/runs/{id}/fork` copies a finished run's plan into a fresh
  peer run ("try a different approach"), with plan review before execution.
- **Rewind** — `POST /api/agent/runs/{id}/rewind {keepSteps}` truncates the plan to its
  first N steps, inherits only the completed executions below that boundary, and resumes
  with plan review. Side effects of the dropped steps are **not** rolled back — the
  review gate exists precisely so a human can account for them.

Every run also records the plugin-sandbox posture it was created under; resuming,
forking, or rewinding a sandboxed run is refused while the host runs plugins
unsandboxed, so replay can never silently weaken isolation.

## Read-only batch capability

`POST /api/agent/batch` accepts `capabilityMode: "read-only"`, restricting every child
run to `read`-effect tools — the declared shape for parallel research or review tasks.
A plan containing any non-read step is rejected before a single tool runs.

## Cross-session memory (experimental, off by default)

Enable it in Settings and the AI gains `memory_remember` / `memory_search` /
`memory_list` / `memory_forget`: durable facts stored per user, retrieved by keyword
overlap weighted with a 7-day recency half-life, and relevant memories are injected
into the planning context of agent runs. The experimental flag is deliberate restraint —
like every memory feature, it can memorize the wrong thing, so it stays opt-in.

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
