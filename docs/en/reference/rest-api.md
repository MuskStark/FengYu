---
title: REST API
description: The complete Infinia 4.0.0 backend endpoint catalog — every REST and SSE route, grouped by controller, with auth requirement and one-line purpose. The host is loopback-bound and token-gated; three path prefixes bootstrap without a token.
lang: en
---

# REST API

The Infinia backend is a headless Spring Boot application that exposes a REST + SSE API over loopback (`server.address=127.0.0.1`). The default port is `24056`; if it is taken the launcher falls back to an OS-assigned port and announces it as `FENGYU_PORT=<n>` on stdout. See [Backend](/en/architecture/backend).

## Authentication

Every request passes through `TokenAuthFilter`, which compares the `X-FengYu-Token` header to the value supplied via `--token` at launch. Three path prefixes **bypass** the filter so the system can bootstrap without a credential:

- `/api/health` — liveness probe.
- `/api/setup/*` — first-launch wizard (the token may not exist yet).
- `/plugin-runtime/{id}/**` — static plugin UI assets, served under a strict CSP.

All other endpoints require a matching token. In the tables below, the **Auth** column is `token` (header required), `—` (no token, bypassed), `ticket` (a one-time `?ticket=` from the matching `stream-ticket` endpoint — for SSE, which cannot set headers), or a permission name (token plus a plugin permission).

::: tip
The SSE streams do **not** accept the token as a `?token=` query parameter. Mint a one-time
ticket first (`POST /api/ai/stream-ticket`, `/api/agent/stream-ticket`, or
`/api/notifications/stream-ticket`), then open the stream with `?ticket=` (plus `?streamId=`
or `?runId=` where applicable). See [SSE Events](/en/reference/sse-events).
:::

## Health

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/health` | — | Liveness probe. Returns `{ "status": "ok" }`. |

## Account

Local control plane for the optional Infinia Store cloud identity. These routes still require the
local launch token; the backend owns the system-browser OAuth 2.1 + PKCE flow and never exposes
Store tokens to the SPA. See [Backend — Cloud account sign-in](/en/architecture/backend#cloud-account-sign-in).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/account/me` | token | Return the bound Store profile, or the local virtual user when signed out. |
| `POST` | `/api/account/sign-in` | token | Start browser sign-in → `{attemptId, authorizationUrl}`. |
| `GET` | `/api/account/sign-in/{attemptId}` | token | Poll the attempt → `{status: PENDING\|COMPLETED\|FAILED, user?, error?}`. |
| `POST` | `/api/account/sign-out` | token | Revoke the Store refresh token best-effort, delete the binding, and return the local user. |

## Plugin categories

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/plugin-categories` | token | The category vocabulary (`id`, `labelKey`, `icon`) used by the marketplace UI. |

## Plugin runtime

Descriptor access and worker invocation for installed plugins.

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/plugin-runtime` | token | Enabled plugins as `InstalledPluginDescriptor[]`. |
| `GET` | `/api/plugin-runtime/status` | token | Operational snapshots for all installed Workers: state, fault category, runtime, pid, restarts, backoff, and sandbox. |
| `GET` | `/api/plugin-runtime/{id}/status` | token | One plugin's operational snapshot. |
| `POST` | `/api/plugin-runtime/{id}/invoke` | token | Invoke a worker method. Body `{callId, method, params}` → JSON-RPC `result`. `callId` is the protocol correlation id. See [Worker](/en/plugins/worker). |
| `POST` | `/api/plugin-runtime/{id}/invoke/{callId}/cancel` | token | Interrupt a tracked invocation. Returns `{cancelled}`; cancelling a Worker call tears down that Worker so a stuck handler cannot continue. |
| `GET` | `/api/plugin-runtime/{id}/logs` | token | Recent Worker events as `{timestamp, level, logger, thread, message, sequence}`; legacy stderr has null logger/thread. |
| `GET` | `/api/plugin-runtime/{id}/logs/stream` | token | Replay recent Worker events, then stream new events over SSE. |
| `GET` | `/plugin-runtime/{id}/**` | — | Plugin UI static assets (entry HTML + JS), served under a strict CSP. |

## Plugin files

File grant endpoints for sandboxed plugins. All live under base `/api/plugin-runtime/{id}/files`. Each is gated by a permission declared in the plugin [manifest](/en/plugins/manifest). See [File I/O](/en/plugins/file-io).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/plugin-runtime/{id}/files/upload` | token + `files.read` | Upload a single file (multipart `file`) → `FileRef` snapshotted into temp. |
| `POST` | `/api/plugin-runtime/{id}/files/upload-directory` | token + `files.read` (+ `files.write` for `read-write`) | Upload a tree (multipart `files` + `paths[]`, optional `access=read-write`) → directory `FileRef`. |
| `POST` | `/api/plugin-runtime/{id}/files/native` | token + `files.read` and/or `files.write` | Wrap a native OS path (body `{path, kind, access}`) as a `FileRef`. Desktop only. |
| `POST` | `/api/plugin-runtime/{id}/files/output` | token + `files.write` | Allocate a fresh writable output directory → `FileRef`. |
| `GET` | `/api/plugin-runtime/{id}/files/export/{ref}` | token + `files.write` | Stream a zip of the granted directory for download. |

## Marketplace

Plugin registry and lifecycle. Base `/api/plugin-market`. See [Marketplace](/en/plugins/marketplace).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/plugin-market` | token | Browse the catalog → `MarketplacePlugin[]`. |
| `POST` | `/api/plugin-market/upload` | token | Install from an uploaded `.fyp` (multipart). Same id as an installed plugin → replaces it (update). |
| `POST` | `/api/plugin-market/upload-native` | token | Install from a local filesystem path (body `{path}`). Desktop only. |
| `POST` | `/api/plugin-market/inspect` | token | Read an uploaded `.fyp`'s manifest without installing → `PackageInspection` (install-vs-update + version step). |
| `POST` | `/api/plugin-market/inspect-native` | token | Path-based twin of `/inspect` (body `{path}`). Desktop only. |
| `POST` | `/api/plugin-market/{id}/install` | token | Install a catalog plugin by id. |
| `POST` | `/api/plugin-market/{id}/update?confirmPermissions=<boolean>` | token | Health-gated update to the catalog's latest; added permissions require explicit confirmation. |
| `PATCH` | `/api/plugin-market/{id}/enabled` | token | Toggle enabled. Body `{enabled}`. Disabling stops the worker immediately. |
| `DELETE` | `/api/plugin-market/{id}?deleteData=<boolean>` | token | Uninstall with an explicit runtime-data retain/delete policy. Retain also preserves the provisioned DB namespace. |

## Settings

User-facing preferences. See [Configuration — User settings](/en/guide/configuration#user-settings).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/settings` | token | Read `{theme, language, sidebarCollapsed, logLevel, computerUseEnabled, computerUse}`. |
| `PUT` | `/api/settings` | token | Partial update of user settings; `logLevel` applies live to the host and Java Workers, `computerUseEnabled` toggles the desktop `computer_*` tools. |
| `POST` | `/api/settings/database/reset` | token | Back up `datasource.properties`, clear it, restart into SETUP mode. |

## AI

Chat invocation and the streaming endpoint. See [AI Chat](/en/guide/ai-chat).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/ai/chat` | token | Start a chat turn. Body `{messages:[{role, content}], permissionMode?, workflowId?}` → `{streamId}`. A `workflowId` binds the turn to that flow (draft or published): the model receives it as the `run_current_flow` tool inside the ordinary chat tool-call loop. |
| `GET` | `/api/ai/stream?streamId=` | token | SSE stream for the chat turn. See [SSE Events — Chat](/en/reference/sse-events#chat-stream). |

## AI config

Backend selection and API keys, with hot-swap. See [Configuration — AI config](/en/guide/configuration#ai-config).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/ai/config` | token | Masked config snapshot (API keys masked with `***`). |
| `PUT` | `/api/ai/config` | token | Partial update; hot-swaps the active backend without restart. |
| `POST` | `/api/ai/config/test` | token | Probe a connection without saving. Body `{mode, endpoint, apiKey, model, baseUrl}`. |

## Conversations

Persisted chat history. See [AI Chat — Conversations](/en/guide/ai-chat#conversations).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/ai/conversations` | token | Conversation summaries, newest first. |
| `GET` | `/api/ai/conversations/{id}` | token | A single conversation (title + messages). |
| `POST` | `/api/ai/conversations` | token | Create. Body `{title, messages}` → created conversation with `id`. |
| `PUT` | `/api/ai/conversations/{id}` | token | Full replace of title + messages. Body `{title, messages}`. |
| `DELETE` | `/api/ai/conversations/{id}` | token | Remove a conversation. |

## Agent

The plan-and-execute agent. See [AI Agent](/en/guide/ai-agent).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/agent/run` | token | Start a run. Body `{goal, config}` → `{runId}`. |
| `POST` | `/api/agent/batch` | token | Start 1–8 independent runs concurrently. Body `{goals, config}` → `{runIds}`. |
| `GET` | `/api/agent/stream?runId=` | token | SSE stream for the run. See [SSE Events — Agent](/en/reference/sse-events#agent-stream). |
| `POST` | `/api/agent/{runId}/approve` | token | Release an approval gate. Optional edited `AgentPlan` body. |
| `POST` | `/api/agent/{runId}/cancel` | token | Cooperatively cancel the run. |
| `GET` | `/api/agent/tools` | token | Orchestrable tool list (host-aggregated `ToolCallback[]`). |
| `GET` | `/api/agent/runs` | token | Persisted run summaries, newest first. |
| `GET` | `/api/agent/runs/{runId}` | token | Persisted plan, executions, and ordered audit events. |
| `POST` | `/api/agent/runs/{runId}/resume` | token | Resume unfinished steps from a failed/cancelled/recovery-required run and require plan review. Restart recovery reuses stable step invocation IDs; remaining session-scoped file grants make a run non-resumable. |
| `GET` | `/api/agent/tasks` | token | List the current user's durable recent background-task snapshots and output, including `priority`, distinct `queued`/`running` states, queue wait, and, once started, start time/run duration. The host runs 16 bodies, queues up to 128 globally, and queues at most 32 for one owner; queued or running tasks become non-replayed failures after restart. |
| `GET` | `/api/agent/tasks/capacity` | token | Return global `running`/`queued` counts and limits, interactive/normal/batch counts, global batch/non-interactive limits (64/96), remaining admission capacity, current-owner share and equivalent 16/24/32 limits, `activeOwners`, `oldestQueueWaitMs` plus per-priority `oldestInteractiveQueueWaitMs`/`oldestNormalQueueWaitMs`/`oldestBatchQueueWaitMs`, `saturated`, and `schedulingPolicy` (`owner-round-robin-weighted-priority`); no other owner's task details are exposed. Queue admission failures return retryable HTTP 429 with `capacityScope` set to `owner`, `global`, `owner-priority`, or `global-priority`; priority-reservation failures also include `capacityPriority`. |
| `GET` | `/api/agent/tasks/{taskId}?timeoutMs=` | token | Return an owned task snapshot, optionally waiting up to 60 seconds for a terminal state. |
| `DELETE` | `/api/agent/tasks/{taskId}` | token | Cancel an owned queued task before it starts, or cooperatively cancel a running task. |
| `GET` | `/api/agent/schedules` | token | List active durable workflow schedules. Each row includes `nextFireAt`, `fires`, coalesced `missedFires`, last task/error, expiry, and sandbox posture. |
| `POST` | `/api/agent/schedules` | token | Persist a schedule from `{workflowId, inputs?, intervalSeconds?, recurring?, fireImmediately?}`. The workflow must be published and inputs valid. |
| `DELETE` | `/api/agent/schedules/{scheduleId}` | token | Durably cancel an active schedule. |
| `GET` | `/api/agent/webhook-triggers` | token | List the current user's active durable webhook triggers. Plaintext secrets are never returned. |
| `POST` | `/api/agent/webhook-triggers` | token | Create from `{workflowId, name?, defaultInputs?, permissionMode?}`. Returns the plaintext secret exactly once. The workflow must be published and cannot require ephemeral file grants. |
| `GET` | `/api/agent/webhook-triggers/{triggerId}/deliveries?limit=` | token | List 20 recent delivery lifecycle rows by default (limit 1–100) for an owned active trigger. Rows contain task/status/timestamps/error and whether an idempotency key was supplied; payloads, secrets, event IDs, and hashes are omitted. |
| `POST` | `/api/agent/webhook-triggers/{triggerId}/rotate-secret` | token | Immediately invalidate the old secret and return its replacement once. |
| `DELETE` | `/api/agent/webhook-triggers/{triggerId}` | token | Durably disable an active trigger. |
| `GET` | `/api/mcp/status` | token | Configured MCP connections and discovered tool count. |
| `GET` | `/api/mcp/servers` | token | List dynamically managed MCP servers, connection state, and discovered tool names. |
| `POST` | `/api/mcp/servers` | token | Add a `STDIO`, `SSE`, or `STREAMABLE_HTTP` server and connect immediately. Credentials are accepted in `env`/`headers` and are never returned by the API. |
| `PUT` | `/api/mcp/servers/{id}` | token | Replace a server definition, close the old session, reconnect, and refresh the live AI tool catalog. |
| `DELETE` | `/api/mcp/servers/{id}` | token | Disconnect and remove a dynamically managed server. |
| `POST` | `/api/mcp/servers/{id}/test` | token | Reconnect and perform MCP initialization plus `tools/list`. |
| `POST` | `/api/mcp/servers/{id}/call` | token | Directly call a discovered MCP tool. Body `{tool, arguments}`. |
| `GET` | `/api/mcp/servers/{id}/prompts` | token | List prompts exposed by the live MCP session. |
| `GET` | `/api/mcp/servers/{id}/resources` | token | List resources exposed by the live MCP session. |

## Workflows

Reusable workflow definitions use the same `AgentPlan` DAG as the agent runner. `inputSchema` is a
JSON Schema object; runtime inputs bind to <code v-pre>{{inputs.name}}</code> placeholders. `layout`
maps compiled step indexes to canvas positions, and `graph` (optional) stores the authored canvas
graph verbatim — `{nodes, edges}` with sticky-note nodes included — so the flow builder reopens the
exact arrangement (definitions without `graph` reconstruct from `plan` + `layout`). Published
definitions are added to the live AI tool catalog.

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/workflows` | token | List the current user's workflow definitions. |
| `GET` | `/api/workflows/{workflowId}` | token | Read one definition. |
| `POST` | `/api/workflows` | token | Create from `{name, description, inputSchema, plan, layout?, graph?}`. |
| `PUT` | `/api/workflows/{workflowId}` | token | Replace the editable draft with `{name, description, inputSchema, plan, layout?, graph?, expectedRevision?}` and increment its revision. A matching `expectedRevision` protects against stale-editor overwrites; the active published snapshot is unchanged. |
| `POST` | `/api/workflows/{workflowId}/publish` | token | Publish the current draft (or unpublish) with `{published, expectedRevision?}`. Publishing creates an immutable snapshot; a stale revision returns HTTP 409. |
| `GET` | `/api/workflows/{workflowId}/revisions` | token | List immutable published revisions and identify the currently active snapshot. |
| `GET` | `/api/workflows/{workflowId}/revisions/{revision}` | token | Read one published snapshot. |
| `POST` | `/api/workflows/{workflowId}/revisions/{revision}/restore` | token | Restore a snapshot into a new editable draft with `{expectedRevision?}`. The active published snapshot does not change until the draft is published. |
| `DELETE` | `/api/workflows/{workflowId}` | token | Atomically cancel its active schedules and webhook triggers, then delete the definition → `{ok, cancelledSchedules, cancelledWebhookTriggers}`. |
| `POST` | `/api/workflows/{workflowId}/run` | token | Manually run with `{inputs, config}` → `{runId}`; observe the normal agent SSE stream. |
| `POST` | `/api/workflow-hooks/{triggerId}` | webhook secret | Submit a JSON-object input overlay (maximum 256 KiB) with `X-FengYu-Webhook-Secret`; optionally add `X-FengYu-Event-Id` (maximum 200 characters) for at-most-once admission. New events return HTTP 202; duplicates return HTTP 200 with the original delivery state/task. A full background queue returns retryable HTTP 429 with `Retry-After: 1`; because no task was admitted, the event-ID claim is released for a safe retry. The launch token is not used for this endpoint. |

## Notifications

The unified host notification center — persisted rows plus a live SSE fan-out. Producers
POST one row; every connected shell receives it live (see [SSE Events — Notification
stream](/en/reference/sse-events#notification-stream)) and shows an in-app toast or a native
OS notification depending on window visibility. Known producers: the plugin `notify` host
bridge and agent run termination. History is kept newest-first with a 200-row retention
window per install.

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/notifications` | token | Create + broadcast. Body `{source, level, title, body?, link?}` → the created view. `level` is `info\\|success\\|warning\\|error`; `source` names the originator (`host`, `agent`, `plugin:<id>`). |
| `GET` | `/api/notifications?limit=&unreadOnly=` | token | Newest-first history (capped at 100 per call). |
| `GET` | `/api/notifications/unread-count` | token | Badge counter. |
| `POST` | `/api/notifications/{id}/read` | token | Acknowledge one (idempotent). |
| `POST` | `/api/notifications/read-all` | token | Acknowledge everything. |
| `DELETE` | `/api/notifications/{id}` | token | Remove one from the center. |
| `POST` | `/api/notifications/stream-ticket` | token | Mint the one-time ticket the SSE stream redeems. |
| `GET` | `/api/notifications/stream?ticket=` | ticket | Live `notification` events to every connected shell. |

## Setup

First-launch wizard. All endpoints bypass the token filter and exist only in SETUP mode. See [Database — Setup endpoints](/en/guide/database#setup-endpoints).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/setup/status` | — | `{initialized, supportedTypes[], embeddedTypes[]}`. |
| `GET` | `/api/setup/types` | — | Per-backend form metadata for the wizard. |
| `POST` | `/api/setup/test-connection` | — | Probe a connection without persisting. Body `{type, params}`. |
| `POST` | `/api/setup/initialize` | — | Re-test, persist config, signal restart into APP mode. Body `{type, params}`. |
| `DELETE` | `/api/setup/config` | — | Back up config, clear it, restart into SETUP mode. |

## Conventions

- **Content type** for JSON bodies is `application/json`; file uploads use `multipart/form-data`.
- **Errors** use standard HTTP status codes. A `403` from a file endpoint means a missing [permission](/en/plugins/manifest#valid-permissions); a `401`/`403` elsewhere means a missing or mismatched token.
- **SSE** frames are named after their event type and carry a JSON `data` payload. Both stream endpoints emit a `:connected` comment heartbeat as the first frame.

## Next steps

- [SSE Events](/en/reference/sse-events) — the full chat and agent stream taxonomy.
- [Architecture — Backend](/en/architecture/backend) — the launcher, port announcement, and SETUP vs APP mode.
- [Guide — Configuration](/en/guide/configuration) — worked examples for settings and AI config.
