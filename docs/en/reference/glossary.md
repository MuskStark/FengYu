---
title: Glossary
description: Definitions for the Infinia 4.0.0 terms used across the docs — FileRef, micro-frontend, SETUP/APP mode, sidecar, virtual user, JSON-RPC worker, .fyp, uiEntry, ToolCallback, MD3, and the Ollama backend.
lang: en
---

# Glossary

The domain terms used across the Infinia docs, each with a one-paragraph definition and a pointer to where it is explained in depth.

## FileRef

An **opaque handle** the host mints to let a sandboxed plugin refer to a file or directory without ever seeing a real path. Its shape is `{id, name, kind, access, size}`, where `id` begins with `ref_`. The plugin UI passes the FileRef straight into an RPC; the host's `PluginProcessManager` rewrites it to an absolute filesystem path **before** dispatch, so the worker receives a real path it can open. Grants live in memory only and do not survive a host restart. See [File I/O](/en/plugins/file-io).

## MF (micro-frontend)

A self-contained UI bundle (the plugin's `ui/` directory) that the host serves as static assets under `/plugin-runtime/{id}/**` and loads into a **sandboxed iframe** under a strict Content Security Policy. Inside the iframe, `@fengyu/plugin-sdk`'s `FengYuClient` bridges to the host over `postMessage`. The host's MF loader can also mount a plugin's ESM bundle directly via `import(uiEntry)` → `default.mount(el, ctx)`. See [UI Micro-frontend](/en/plugins/ui-microfrontend).

## SETUP mode / APP mode

The two runtime modes the backend auto-selects at launch, based on the datasource config at `~/.fengyu/config/datasource.properties`:

- **SETUP mode** boots `SetupApplication` with **no JPA** and serves the first-launch wizard under `/api/setup/*` (token-bypassed). Used before any database is configured.
- **APP mode** boots `FengYuApplication` with `fengyu.mode=app` and the full persistence + AI + plugin stack.

If the config exists but the DB is unreachable, the launcher backs the config up to a `.bak` sibling and falls back to SETUP mode. See [Backend — SETUP vs APP mode](/en/architecture/backend#setup-vs-app-mode).

## Sidecar

The worker process a plugin ships — typically a shaded `backend/worker.jar` launched by `java -jar`. It runs as its **own OS process**, spawned and owned by the host's `PluginProcessManager`, and speaks JSON-RPC 2.0 over stdio. Because it is out-of-process, a worker crash or hang cannot take down the host, and the worker cannot reach into host beans or the JPA session. "Sidecar" emphasizes that it lives alongside the host, not inside it. See [Worker (JSON-RPC)](/en/plugins/worker).

## Virtual user

The local identity Infinia creates on first APP-mode startup: **id** `1`, **name** `ZFlow-Summer`, **role** admin/local. Conversations and other user-scoped records attach to this identity. There is no password or login flow — the virtual user is implicit on a single-user install. See [Database — Virtual user](/en/guide/database#virtual-user).

## JSON-RPC worker

The protocol and process model for plugin backends. The worker reads newline-delimited JSON-RPC 2.0 requests from `stdin` (`{jsonrpc:"2.0", id, method, params}`), dispatches to registered handlers, and writes one response per line to `stdout` (`result` or `error`). `stderr` is reserved for logs. The `FengYu-Plugin-Sdk` artifact ships `JsonRpcWorker`, a tiny runtime with `.on(method, handler)` registration. See [Worker (JSON-RPC)](/en/plugins/worker).

## `.fyp`

The plugin package format — a zip archive with three parts: `manifest.json` (metadata, permissions, AI tools), `ui/` (the micro-frontend assets), and `backend/worker.jar` (the sidecar executable). Installed via the marketplace (`POST /api/plugin-market/upload` for a `.fyp`, or `upload-native` for a local path). Built with `fengyu plugin build` or the Maven shade flow. See [Plugin Overview](/en/plugins/overview) and [Build & Deploy](/en/plugins/build-deploy).

## `uiEntry`

The resolved UI entry URL on an `InstalledPluginDescriptor` — the address the host's MF loader imports to mount the plugin (`import(uiEntry)` → `default.mount(el, ctx)`). It is derived from the manifest's `ui.entry` (typically `ui/index.html`) and served under `/plugin-runtime/{id}/<entry>`. See [Plugin System — Installed plugin descriptor](/en/architecture/plugin-system#installed-plugin-descriptor).

## `ToolCallback`

The Spring AI abstraction for a tool the model can call. Infinia aggregates every built-in `@FengYuTool` and every enabled plugin's declared `aiTools` into a single `ToolCallback[]`, so plugin tools and built-in tools are indistinguishable on the wire — both surface in `GET /api/agent/tools` and both can be invoked from chat or agent runs. See [AI Tools](/en/plugins/ai-tools).

## MD3

Material Design 3 — the design system the host UI (Vuetify) implements. The host passes its Vuetify instance to plugin MFs via `ctx.vuetify` so plugins reuse the host's theme and components rather than bundling their own; this is the "do not bundle Vuetify" rule. The purple `#6750A4` theme color is the MD3 baseline. See [Design System](/en/design-system) and [UI Micro-frontend](/en/plugins/ui-microfrontend).

## Ollama backend

The `local` AI backend mode. It talks to an **external `ollama serve`** process over Ollama's local HTTP API — the backend does **not** load a GGUF model in-process. To use it, run `ollama serve` separately and select `local` in AI config. The other modes are `openai`, `anthropic`, and `deepseek` (OpenAI-compatible). See [AI Chat — Backends](/en/guide/ai-chat#backends).

## Next steps

- [REST API](/en/reference/rest-api) — where these terms show up in endpoints.
- [Architecture — Plugin System](/en/architecture/plugin-system) — how FileRefs, MFs, and workers connect.
- [Architecture — Backend](/en/architecture/backend) — SETUP/APP mode and the sidecar process model.
