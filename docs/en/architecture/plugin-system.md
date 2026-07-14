---
title: Plugin System
description: Infinia 4.0.0 plugins are self-contained .fyp packages — a manifest, a UI micro-frontend, and an out-of-process JSON-RPC 2.0 worker.jar — mounted in a sandboxed iframe and supervised by the host's PluginProcessManager.
lang: en
---

# Plugin System

An Infinia plugin is a self-contained **`.fyp` package** that adds both UI and backend capability without touching the host process. The defining design rule is **isolation**: a plugin's code never runs inside the host Spring context, and its UI never shares the host's DOM tree.

## The `.fyp` package

A `.fyp` file is a zip archive with three parts:

| Path | Contents |
| --- | --- |
| `manifest.json` | Plugin metadata, permissions, and the AI tools it exposes |
| `ui/` | The micro-frontend assets, served by the host under `/plugin-runtime/{id}/**` |
| `backend/worker.jar` | The worker executable, spawned as its own process |

For the manifest field reference, see [Manifest](/en/plugins/manifest).

## Out-of-process workers

The worker is the plugin's backend. It speaks **JSON-RPC 2.0** over stdio and is spawned as a **separate operating-system process** — it never lives in the host Spring context. The host invokes it through `POST /api/plugin-runtime/{id}/invoke` with a `{method, params}` body; the host's `PluginProcessManager`:

- spawns and **owns** the worker process for the plugin's lifetime,
- dispatches each JSON-RPC call to that process,
- resolves any `ref_*` FileRefs in the parameters to absolute filesystem paths before dispatch, so the worker receives real paths it can open directly.

Because workers are out-of-process, a worker crash or hang cannot take down the host, and a worker cannot reach into host beans or the JPA session.

## Sandboxed UI

The plugin's `ui/` micro-frontend is served by the host as static assets under a strict Content Security Policy at `/plugin-runtime/{id}/**` — these asset paths are the only plugin URLs that bypass the token filter, so the UI can bootstrap without a credential. The host loads the UI through its [micro-frontend host](/en/architecture/frontend) (`import(uiEntry)` → `default.mount(el, ctx)`) and reuses the host's Vuetify instance for consistent MD3 theming.

Inside the iframe, the SDK `@fengyu/plugin-sdk` provides a `FengYuClient` that bridges to the host over `postMessage`. The plugin uses this client to call its own worker (which the host forwards as JSON-RPC) and to request file access — it never talks to the OS directly.

## Installed plugin descriptor

The host exposes installed plugins through `InstalledPluginDescriptor`. Its fields are:

| Field | Notes |
| --- | --- |
| `id` | Unique plugin id |
| `name` | Display name |
| `description` | Short description |
| `category` | Category id (see the category tree) |
| `icon` | Icon identifier |
| `version` | Plugin version string |
| `uiEntry` | Resolved UI entry URL |
| `author` | Author string |
| `permissions` | Declared permissions (e.g. `files.read`, `files.write`) |
| `enabled` | Whether the plugin is currently enabled |
| `iconStyle` | Hardcoded `"BLUE"` |
| `supportsAi` | `true` when `aiTools` is non-empty |
| `source` | `OFFICIAL` or `THIRD_PARTY` |

`GET /api/plugin-runtime` returns the array of enabled descriptors; the SPA's `plugins` store consumes them.

## How the pieces connect

```
┌─────────────────────────┐        postMessage         ┌──────────────────────────┐
│  Plugin UI micro-frontend│  ◄──────────────────────►  │  Host SPA (loader.ts)    │
│  (sandboxed iframe,      │       FengYuClient          │  mounts via MF host       │
│   @fengyu/plugin-sdk)    │                             └──────────┬───────────────┘
└──────────────────────────┘                                        │ HTTP (token-gated)
                                                                     ▼
                                                    ┌──────────────────────────────┐
                                                    │  Host Spring backend         │
                                                    │  PluginProcessManager        │
                                                    └──────────┬───────────────────┘
                                                               │ JSON-RPC 2.0 (stdio)
                                                               ▼
                                                    ┌──────────────────────────────┐
                                                    │  Worker process              │
                                                    │  backend/worker.jar          │
                                                    └──────────────────────────────┘
```

## Next steps

- [Manifest](/en/plugins/manifest) — the `manifest.json` field reference.
- [Architecture Overview](/en/architecture/overview) — where plugins sit in the three-layer system.
- [Frontend](/en/architecture/frontend) — the micro-frontend host that mounts plugin UIs.
