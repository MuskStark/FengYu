---
name: fengyu-flow-dev
description: Author, inspect, modify, compile, publish, and run FengYu/iFlow workflows — the visual AI flow feature. Covers the canvas document model (graph → plan compile), the {{node.*}} reference grammar, flow_if branching and skip cascades, flow_llm nodes, revisions/publishing, run/resume/webhook surfaces, and changing the flow engine or builder code itself. Use whenever the user mentions flows, iFlow, workflows, the flow builder/canvas/inspector, run_workflow_* tools, flowNodes overlays, flow templates, or asks to create, modify, debug, or test a flow.
---

# FengYu Flow (iFlow) Authoring and Development

A flow ("iFlow" in the UI, `workflow` in the backend) is a saved node graph that compiles to a
plan-and-execute `AgentPlan` and runs on `AgentRunner` — one virtual thread per run,
dependency-ready steps in parallel. A **published** flow becomes a first-class catalog tool
(`run_workflow_<id>`) the AI chat, background tasks, schedules, and webhooks can invoke. This
skill covers both authoring flows (through the REST API or the builder's AI chat) and changing
flow engine/builder behavior in this repo.

## Read the applicable source of truth first

Do not design from this skill alone. Inspect the files relevant to the requested change:

- Builder UI: `frontend/src/pages/FlowBuilderPage.tsx` + `FlowLibraryPage.tsx`, everything under
  `frontend/src/components/flow/`, the pure model in `frontend/src/lib/flow*.ts`
  (`flowGraph.ts` is the canvas↔wire conversion + compiler), and
  `frontend/src/services/workflow.ts` + `services/types.ts` for the REST client and every TS shape.
- Engine and persistence: `FengYu/src/main/java/fan/summer/fengyu/ai/workflow/`
  (`WorkflowService`, `WorkflowExecutionService`, `FlowAuthoringToolFactory`, webhook services)
  and `ai/agent/AgentRunner.java` (the run state machine). Entities/repositories live under
  `database/entity|repository/ai/Workflow*`.
- Host builtin flow nodes: `FengYu/src/main/resources/flow-nodes/builtin.json` (canonical) +
  `builtin_zh.json` (locale delta; `FlowNodeBuiltinParityTest` pins parity).
- REST surface: `web/controller/AgentController.java` (`/api/workflows*`, `/api/agent/runs/*`)
  and `WorkflowWebhookController.java`.
- Canonical user guide: `docs/{en,zh}/guide/flow-nodes.md`, plus the flows sections of
  `docs/{en,zh}/guide/ai-agent.md` and `docs/{en,zh}/plugins/manifest.md` (plugin `flowNodes`).

When prose and source disagree, follow the repository.

## The document model: graph, plan, layout

A `WorkflowDefinition` row (`ai_workflow`) persists four JSON columns that must stay consistent:

- **`graph`** — the raw canvas graph (`{nodes, edges}`), the round-trip source of truth for the
  editor. Nullable; readers fall back to reconstructing from the plan.
- **`plan`** — the compiled `AgentPlan` (`{goal, reasoning, steps}`) that actually executes.
- **`layout`** — canvas positions keyed by compiled step index.
- **`inputSchema`** — the JSON Schema for run-time inputs (`{{inputs.x}}` bindings).

Canvas node types are exactly `start`, `tool`, and `sticky` (wire type `"note"`). There is no
agent or end node: the LLM node is the `flow_llm` tool, the branch node is the `flow_if` tool
(rendered with named branch source ports because its flowNode descriptor sets `kind: "control"`),
and "end" is implicit. Exactly one Start node exists. A tool node's `data` carries `toolName`,
`argsText` (the JSON string the inspector edits), `description`, `requiresApproval`, plus optional
`title`, `pinnedOutput`, `retryPolicy {maxAttempts, backoffMs}`, and runtime-captured
`lastRun`/`lastRunAt` (≤16 KiB, excluded from the dirty snapshot).

Compilation (`compileFlowPlan` in `flowGraph.ts`) orders nodes topologically with a
position tiebreak, numbers steps `0..n-1`, derives each step's `dependsOn` from incoming edges,
rewrites `{{node.<id>.<channel>.<path>}}` to `{{steps.<index>.<channel>.<path>}}`, and compiles
an edge leaving a branch port (`sourceHandle: "true"|"false"`) into the target's
`runWhen: [{step: <index>, equals: "<port>"}]`.

## The reference grammar

```text
{{inputs.<name>}}                     run-form input, bound at run/compile time
{{node.<id>.input.<path>}}            canvas syntax — upstream effective arguments
{{node.<id>.result.<path>[0].field}}  canvas syntax — upstream tool result
{{steps.<n>.input.<path>}}            compiled form of the same two channels
{{last.result}}                       the previous step's result (adds an index-1 dependency)
```

- A string that is **exactly one** reference takes the referenced typed value; references
  embedded in a larger string render to text (JSON for non-strings).
- The `input` channel is the schema-sanitized copy of the arguments the upstream step actually
  received (sensitive fields stripped recursively) — not declared outputs. This is why plugins
  must not declare passthrough outputs.
- References resolve **once, before guard/approval/execution**, so permission rules and hooks see
  the same effective arguments the tool receives — templates cannot smuggle values past a guard.
- Missing results/paths fail loudly at run time; forward references fail at compile time.

`flow_if` (`ai/tools/FlowIfTool.java`) evaluates `left operator right`
(`eq/ne/gt/gte/lt/lte/contains/not_contains/starts_with/ends_with/is_empty/is_not_empty`,
numeric-aware equality) and returns `{"branch": "true|false", "summary": "…"}`; the engine
compares `branch` against each `runWhen[].equals`. `flow_llm` (`ai/tools/FlowLlmTool.java`) is
one non-interactive completion (`prompt`, optional `system`, `temperature` 0–2, optional
`responseSchema` for structured output — raw text survives in `text`, parsed object in `data`);
it uses the **global** AI settings model, built as a fresh one-shot ChatModel per call, 180s cap.

Skip semantics: a step is skipped when its `runWhen` didn't fire, its condition producer was
skipped, a referenced producer was skipped, or all its dependencies were skipped (dead-branch
cascade). Skipped steps record `SKIPPED` and contribute no result. For branched flows, the run's
completion result is the **last actually-completed** step, not the last index.

## Limits and validation (`WorkflowService.validateDraft` + `validatePlan`)

- Name ≤160 chars; ≤64 steps with contiguous indexes; graph ≤512 nodes / ≤1024 edges.
- `inputSchema` must be an object; `{{inputs.*}}` references must address declared properties
  (`validateInputReferences` fails the save otherwise).
- `pinnedResult` ≤64 KiB chars; `retryPolicy` 1–5 attempts, backoff ≤30000 ms, multi-attempt
  only for retry-safe tools.
- No nested `run_workflow_*` steps inside a saved workflow. Tool availability is checked against
  the live catalog; the palette filters the workflow tools themselves (`pluginId === 'workflow'`).
- The backend never interprets node internals — the builder owns the graph contract. Only the
  compiled plan's shape is enforced server-side.

## Author a flow through the REST API

Against a running backend (loopback `:24056`; read the auth model before scripting — token or
session per the smoke script). Fetch the live catalog first so args match real schemas:
`GET /api/agent/tools`. Then `POST /api/workflows` with a `WorkflowDraft`
(`{name, description, inputSchema, plan, layout?, graph?, expectedRevision?}`). When you supply
both `graph` and `plan`, they must be **consistent** — recompiling the graph in the editor must
produce the same step order, `dependsOn`, `runWhen`, and rewritten references. Steps carry
`status: "pending"` on creation.

A minimal branch flow (tool names must exist in the catalog; `email_send` is illustrative):

```json
{
  "name": "Digest",
  "description": "Summarize a topic, send it only when the summary is positive",
  "inputSchema": {
    "type": "object",
    "properties": { "topic": { "type": "string" } },
    "required": ["topic"]
  },
  "plan": {
    "goal": "Summarize and conditionally send",
    "reasoning": "",
    "steps": [
      { "index": 0, "toolName": "flow_llm", "status": "pending", "description": "Summarize",
        "args": { "prompt": "Summarize in three bullets: {{inputs.topic}}" } },
      { "index": 1, "toolName": "flow_if", "status": "pending", "description": "Positive?",
        "args": { "left": "{{steps.0.result.text}}", "operator": "contains", "right": "opportunity" } },
      { "index": 2, "toolName": "email_send", "status": "pending", "description": "Send digest",
        "requiresApproval": true, "dependsOn": [1],
        "args": { "subject": "Digest", "body": "{{steps.0.result.text}}" },
        "runWhen": [{ "step": 1, "equals": "true" }] }
    ]
  },
  "graph": {
    "nodes": [
      { "id": "start", "type": "start", "position": { "x": 0, "y": 200 } },
      { "id": "n1", "type": "tool", "position": { "x": 260, "y": 200 },
        "data": { "toolName": "flow_llm", "argsText": "{\"prompt\":\"Summarize in three bullets: {{inputs.topic}}\"}", "description": "Summarize" } },
      { "id": "n2", "type": "tool", "position": { "x": 520, "y": 200 },
        "data": { "toolName": "flow_if", "argsText": "{\"left\":\"{{node.n1.result.text}}\",\"operator\":\"contains\",\"right\":\"opportunity\"}", "description": "Positive?" } },
      { "id": "n3", "type": "tool", "position": { "x": 780, "y": 100 },
        "data": { "toolName": "email_send", "argsText": "{\"subject\":\"Digest\",\"body\":\"{{node.n1.result.text}}\"}", "description": "Send digest", "requiresApproval": true } }
    ],
    "edges": [
      { "id": "e1", "source": "start", "target": "n1" },
      { "id": "e2", "source": "n1", "target": "n2" },
      { "id": "e3", "source": "n2", "target": "n3", "sourceHandle": "true" }
    ]
  }
}
```

Update with `PUT /api/workflows/{id}` carrying the `expectedRevision` optimistic-lock token from
the definition you loaded — a stale editor gets **409** (`WorkflowRevisionConflictException`),
not a silent overwrite. Deleting a workflow atomically cancels its schedules and webhook triggers.

The builder's docked AI chat is the interactive alternative: `inspect_current_flow` /
`diagnose_current_flow` / `edit_current_flow` (READ-effect authoring tools over a live
`FlowAuthoringContext`; edits return a `flow_proposal` the user applies as a diff) and
`run_current_flow`, bound only when a clean saved revision exists.

## Publish, revisions, and invocation surfaces

- **Publish** (`POST /api/workflows/{id}/publish` `{published, expectedRevision?}`) snapshots the
  **current** revision into the immutable `ai_workflow_revision` table without bumping `revision`;
  `publishedRevision` points at the active snapshot. Restore creates a new draft from an old
  snapshot without changing the active one.
- A published flow becomes catalog tool `run_workflow_<id-with-hyphens-as-underscores>`
  (`AiToolRegistry.workflowToolName`) with the workflow inputSchema as its tool schema, executed
  synchronously by `WorkflowExecutionService.executeForAi` (waits ≤15 min).
- Invocation surfaces: AI chat via `flow://` @-mentions; background tasks via
  `task_submit_workflow(workflowId, inputs)`; interval/calendar schedules; loopback webhook
  triggers (`POST /api/agent/webhook-triggers` `{workflowId, name?, defaultInputs?,
  permissionMode?}` — delivery endpoints and audit live in `WorkflowWebhookController`; payload
  and secrets are never persisted).
- AI-invoked runs inherit the **invoking** context's permission mode — never a hardcoded
  full-access. Unattended runs (webhook/schedule/task) fail an unanswered approval gate after
  `fengyu.agent.headless-approval-timeout-seconds` (default 300s) and notify.

## Runs: config, files, results, recovery

- `POST /api/workflows/{id}/run` `{inputs, config, files?}` → `{runId}`. `config.permissionMode`
  is one of `ask-for-approval | approve-for-me | full-access | plan`; the flow UI defaults to
  ask-for-approval. File-class inputs (`format: "fengyu-file" | "fengyu-directory"` in the
  inputSchema) become native pickers whose grants travel with the run as `@file:<name>`
  placeholders; a failure before or after run creation revokes the owned grants.
- Per-step behavior: pinned steps serve `pinnedResult` verbatim; `retryPolicy` retries with
  exponential backoff (`min(30s, backoffMs << attempt)`) and `step_retry` SSE events; per-step
  `requiresApproval` or an ASK guard verdict opens an approval gate.
- Tool results are budgeted before they reach the model/SSE/transcript (plugin tools 256 KiB,
  MCP 64 KiB). A result carrying the truncation marker **fails the step** with an actionable
  "narrow the tool's output or read it from the saved artifact" error — design flow steps to
  return compact results plus file references, not bulk payloads.
- Runs persist to `ai_agent_run`; an in-flight run at restart converts to `RECOVERY_REQUIRED`.
  `POST /api/agent/runs/{runId}/resume` returns to plan review and replays completed executions
  (run inputs are deliberately not persisted; effective inputs are reconstructed from the bound
  plan + persisted results). `/fork` re-runs the same plan from scratch; `/rewind {keepSteps}`
  truncates. A run whose remaining steps hold session-scoped file grants cannot resume after a
  restart — it must start again with re-picked files.
- Single-step debug ("Run this node") executes the node **plus its dependency closure** in one
  run, so stateful plugin chains (configure → execute) share session state.

## Changing flow engine or builder behavior

- Frontend model/compiler changes: edit `frontend/src/lib/flow*.ts`; verify with the colocated
  vitest suites — `cd frontend && corepack yarn run test:unit`, or target one file with
  `corepack yarn vitest run src/lib/flowGraph.test.ts` (same pattern for
  `flowInspectorModel.test.ts`, `flowFieldValidation.test.ts`, `flowAiAuthoring.test.ts`, …).
- Builder UI: `FlowBuilderPage.tsx` + `components/flow/*`; inspector presentation lives in
  `FlowInspector.tsx` (three tabs: Parameters / Output / Settings — tab state survives node
  switches, resets when the rail closes). Flow styles only via
  `frontend/src/styles/features/flow.css` (public style system rules apply).
- Engine semantics: `AgentRunner.java`; always run `AgentRunnerTest` and
  `AgentRunnerResolvedArgsGuardTest` (the resolve-before-guard invariant) plus the
  `ai/workflow/` suites. Webhook changes: their three test classes.
- Builtin node overlays: edit `builtin.json`; every key change must keep `builtin_zh.json`
  parity (`FlowNodeBuiltinParityTest`).
- UI strings: `flows.*` and `agent.*` namespaces in `frontend/src/i18n/{en,zh}.json` — both
  languages in the same change. Node-level zh localization is server-side (`builtin_zh.json` /
  manifest i18n, resolved per Accept-Language).
- Backend Maven verification: `./mvnw -f FengYu/pom.xml test -Dtest='<ClassName>'` for the
  touched suites.

## Confirmed pitfalls

- **No whole-flow file import/export exists.** The persisted JSON (definition + graph) is the
  interchange format, exchanged only through the REST API or AI proposals. The canvas clipboard
  (`fengyu-flow-clipboard`, node/edge selection with id re-minting and internal-reference
  rewriting) is editor-internal.
- The builder header's node count, save, and run gating count **tool nodes only** — Start and
  sticky notes never count; an unavailable tool node (`data.available === false`) disables run.
- xyflow v12 traps: Delete/Backspace are listened for at **window** level, so overlays cannot
  shield the canvas — gate with `deleteEnabled` while modal; arrow-key nudging registers a
  capture-phase window listener and stops propagation so xyflow's own handler does not double-move
  nodes; branch port labels render as siblings, never handle children (children break the
  connection hit test); `handleSelectionChange` must stay identity-stable and bail on unchanged
  ids or the SelectionListener loops into "Maximum update depth exceeded".
- Multi-effect state reads go through refs assigned during render (`currentSnapshotRef`,
  `toolsRef`, `dirtyRef`, …) — when adding an effect/callback that needs fresh builder state,
  read the merged ref instead of re-subscribing.
- On run-start failure the picked file grants are revoked **and the run dialog closes** — the
  form's file state is dead by then; reopening re-seeds pickers fresh.
- Local drafts (localStorage `fengyu.flow-draft.v1:<id|new>`, 30-day expiry) refuse to apply as
  an update when based on an older server revision ('stale-copy') — surface that to the user
  instead of forcing the draft.
- `lastRun` capture (≤16 KiB) is excluded from the dirty snapshot on purpose: a finished run must
  never trip the unsaved-changes guard. Preserve that exclusion when touching capture logic.
- A skipped step satisfies dependencies but contributes no result — consumers of a skipped
  producer fail loudly; branch on `flow_if` output rather than assuming both paths ran.
