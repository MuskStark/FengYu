---
title: Frontend
description: The Infinia 4.0.0 frontend is a Vue 3.5.39 + TypeScript SPA — Pinia state, vue-router 4, vue-i18n 10, and Vuetify 3 (MD3) — that loads plugin UIs as micro-frontends and redirects to /setup until initialization completes.
lang: en
---

# Frontend

The Infinia frontend is a **Vue 3 single-page application** written in TypeScript. It renders the host shell and loads plugin UIs as micro-frontends at runtime. The same bundle runs unchanged in a browser tab and inside the Tauri WebView.

## Stack

| Package | Version (major) | Role |
| --- | --- | --- |
| `vue` | 3.5.39 | UI framework |
| `vuetify` | 3 | Component library, Material Design 3 |
| `pinia` | 2 | State management |
| `vue-router` | 4 | Routing |
| `vue-i18n` | 10 | Internationalization |
| `vite` | 6 | Dev server + build |

The MD3 palette (Google default, primary `#6750A4`) and light/dark themes are shared with plugin micro-frontends via the host's Vuetify instance. See the [Design System](/en/design-system) page.

## Pinia stores

Application state is split across focused Pinia stores:

- `aiSession` — active AI chat / agent state
- `aiConfirmation` — approvals for sensitive agent actions
- `categories` — plugin category tree
- `connection` — backend reachability / port / token wiring
- `nav` — navigation state
- `plugins` — installed plugin list and descriptors
- `settings` — user settings
- `setup` — first-launch wizard state
- `theme` — MD3 theme and dark/light mode

## Micro-frontend host

Plugin UIs are not bundled into the SPA. They are loaded on demand by the MF host at `frontend/src/mf/loader.ts`, which dynamically imports the plugin's UI entry and mounts it into a target element with a host-provided context:

```ts
const mod = await import(uiEntry)
mod.default.mount(el, ctx)
```

The micro-frontend reuses the host's Vuetify instance and theme via `ctx`, so MD3 tokens stay consistent across the shell and every plugin. Details live on [Plugin System](/en/architecture/plugin-system).

## Desktop integration

When the SPA runs inside Tauri, `frontend/src/mf/desktop.ts` acts as a facade over Tauri's native-dialog plugin, exposing `pickFile` and `pickDirectory` (backed by `@tauri-apps/plugin-dialog`). In a plain browser these fall back to standard browser equivalents.

The Tauri shell injects three globals before the page loads:

- `window.__FENGYU_TOKEN__` — the per-launch `X-FengYu-Token` value
- `window.__FENGYU_PORT__` — the port the backend printed as `FENGYU_PORT=<n>`
- `window.__FENGYU_API_BASE__` — e.g. `http://127.0.0.1:{port}`

The `connection` store reads these to configure every API call. In dev (browser), the Vite proxy serves the same `/api` and `/plugin-runtime` paths to `localhost:24056`.

## Setup guard

A vue-router navigation guard checks `getSetupStatus()` before allowing the user past the wizard. If the backend reports uninitialized, the guard redirects to `/setup` regardless of the target route. Once initialization completes, the user is released into the main app.

## Next steps

- [Architecture Overview](/en/architecture/overview) — how the SPA sits between the backend and the shell.
- [Desktop](/en/architecture/desktop) — where the `window.__FENGYU_*` globals come from.
- [Design System](/en/design-system) — the shared MD3 + Vuetify theming model.
