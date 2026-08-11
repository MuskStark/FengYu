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

All other endpoints require a matching token. In the tables below, the **Auth** column is `token` (header required), `—` (no token, bypassed), or a permission name (token plus a plugin permission).

::: tip
The SSE endpoints authenticate with the `X-FengYu-Token` header — there is **no** `?token=` query parameter. Open the stream with `?streamId=` / `?runId=` only. See [SSE Events](/en/reference/sse-events).
:::

## Health

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/health` | — | Liveness probe. Returns `{ "status": "ok" }`. |

## Plugin categories

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/plugin-categories` | token | The category vocabulary (`id`, `labelKey`, `icon`) used by the marketplace UI. |

## Plugin runtime

Descriptor access and worker invocation for installed plugins.

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/plugin-runtime` | token | Enabled plugins as `InstalledPluginDescriptor[]`. |
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
| `POST` | `/api/plugin-market/upload` | token | Install from an uploaded `.fyp` (multipart). |
| `POST` | `/api/plugin-market/upload-native` | token | Install from a local filesystem path (body `{path}`). Desktop only. |
| `POST` | `/api/plugin-market/{id}/install` | token | Install a catalog plugin by id. |
| `POST` | `/api/plugin-market/{id}/update` | token | Update an installed plugin to the catalog's latest. |
| `PATCH` | `/api/plugin-market/{id}/enabled` | token | Toggle enabled. Body `{enabled}`. Disabling stops the worker immediately. |
| `DELETE` | `/api/plugin-market/{id}?deleteData=<boolean>` | token | Uninstall with an explicit runtime-data retain/delete policy. Retain also preserves the provisioned DB namespace. |

## Settings

User-facing preferences. See [Configuration — User settings](/en/guide/configuration#user-settings).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/settings` | token | Read `{theme, language, sidebarCollapsed, logLevel}`. |
| `PUT` | `/api/settings` | token | Partial update of user settings; `logLevel` applies live to the host and Java Workers. |
| `POST` | `/api/settings/database/reset` | token | Back up `datasource.properties`, clear it, restart into SETUP mode. |

## AI

Chat invocation and the streaming endpoint. See [AI Chat](/en/guide/ai-chat).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/ai/chat` | token | Start a chat turn. Body `{messages:[{role, content}]}` → `{streamId}`. |
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
| `POST` | `/api/agent/runs/{runId}/resume` | token | Resume unfinished steps from a failed/cancelled run and require plan review. |
| `GET` | `/api/mcp/status` | token | Configured MCP connections and discovered tool count. |

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
