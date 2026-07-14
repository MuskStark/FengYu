---
title: Plugin Overview
description: A FengYu plugin is a self-contained .fyp package — manifest, sandboxed UI micro-frontend, and an out-of-process JSON-RPC 2.0 worker.jar — that adds capability without touching the host Spring context.
lang: en
---

# Plugin Overview

A FengYu plugin extends the host with new UI and backend capability while staying **strictly isolated**: its code never runs inside the host Spring context, and its UI never shares the host's DOM tree. Every plugin ships as a single `.fyp` package, and the host supervises its lifecycle from install to uninstall.

## What a plugin is

A plugin is a **`.fyp` package** — a zip archive with three parts:

| Path | Contents |
| --- | --- |
| `manifest.json` | Metadata, permissions, AI tools, and the launch command |
| `ui/` | The micro-frontend assets (entry HTML + JS), served under `/plugin-runtime/{id}/**` |
| `backend/worker.jar` | The worker executable, spawned as its own OS process |

The UI runs in a **sandboxed iframe** and talks to the host through a `postMessage` bridge provided by `@fengyu/plugin-sdk`. The backend is an **out-of-process worker** that speaks JSON-RPC 2.0 over stdio. A worker crash or hang cannot take down the host, and a worker cannot reach into host beans or the JPA session.

## Official vs third-party

Two sources of plugins:

- **Official** — built and signed by the FengYu team, declared with `"official": true` in the manifest, and seeded into every fresh install by the `OfficialPluginSeeder`. The shipped set is `fan.summer.markdown` and `fan.summer.excel`.
- **Third-party** — any `.fyp` archive installed by the user through the marketplace. Their `source` is `THIRD_PARTY`.

The descriptor exposes this as the `source` field — `OFFICIAL` or `THIRD_PARTY` — on every `InstalledPluginDescriptor` returned by `GET /api/plugin-runtime`.

> The `plugin-email` directory in the source tree is **source-only** and is not packaged into a `.fyp`. Do not treat it as a working plugin.

## Lifecycle

A plugin moves through these states under control of the host's `PluginProcessManager` and `PluginPackageService`:

```
install  ──►  enabled  ──►  invoked (UI + worker RPC)  ──►  disabled  ──►  uninstalled
   │            │                                            │
   └─ upload .fyp via marketplace                            └─ DELETE /api/plugin-market/{id}
```

1. **Install** — a `.fyp` is uploaded via the [marketplace](/en/plugins/marketplace); its manifest is parsed and stored.
2. **Enable** — `PATCH /api/plugin-market/{id}/enabled {enabled:true}`; the worker process is spawned lazily on first invoke.
3. **Invoke** — the UI loads in its iframe; calls to `client.invoke(method, params)` are forwarded by the host as JSON-RPC to the worker. See [Worker (JSON-RPC)](/en/plugins/worker).
4. **Disable** — `PATCH .../enabled {enabled:false}`; the host **stops the worker process** immediately.
5. **Uninstall** — `DELETE /api/plugin-market/{id}`; the plugin is removed from the catalog and its process stopped.

## The `source` field

Every installed descriptor carries a `source` discriminator so the UI can distinguish bundled plugins from user-installed ones:

| Value | Meaning |
| --- | --- |
| `OFFICIAL` | Seeded from the built-in official set (`official: true` in manifest) |
| `THIRD_PARTY` | Installed by the user from a `.fyp` archive |

`source` is read-only — it is derived from the manifest's `official` flag at install time and never mutated by the enable/disable cycle.

## Next steps

- [Getting Started](/en/plugins/getting-started) — scaffold a plugin with `fengyu plugin create`.
- [Manifest](/en/plugins/manifest) — the full `manifest.json` field reference.
- [Architecture: Plugin System](/en/architecture/plugin-system) — how the host mounts and supervises plugins.
