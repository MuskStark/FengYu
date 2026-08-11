---
title: Frontend
description: The Infinia 4.0.0 frontend is a Vue 3.5.39 + TypeScript SPA — Pinia state, vue-router 4, vue-i18n 10, and Vuetify 3 (MD3) — that loads plugin UIs as micro-frontends and redirects to /setup until initialization completes.
lang: en
---

# Frontend

The Infinia frontend is a **Vue 3 single-page application** written in TypeScript. It renders the host shell and loads plugin UIs as micro-frontends at runtime. The same bundle runs unchanged in a browser tab and inside the Electron BrowserWindow.

## Stack

| Package | Version (major) | Role |
| --- | --- | --- |
| `vue` | 3.5.39 | UI framework |
| `vuetify` | 3 | Component library, Material Design 3 |
| `pinia` | 2 | State management |
| `vue-router` | 4 | Routing |
| `vue-i18n` | 10 | Internationalization |
| `vite` | 6 | Dev server + build |

The MD3 palette (Google default, primary `#6750A4`) is implemented by the host and the plugin UI kit. The host bridge reports environment and theme changes to sandboxed plugin UIs. See the [Design System](/en/design-system) page.

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

Plugin UIs are not bundled into the SPA. `PluginView.vue` loads each plugin's `uiEntry` in a sandboxed iframe. The iframe and host negotiate the shared `@infinia/plugin-sdk/protocol` version, then exchange typed request, response, cancellation, and environment messages over `postMessage`. A plugin cannot access the host's Vue or Vuetify objects directly; `@infinia/plugin-ui` renders the matching MD3 surface inside the isolation boundary. Details live on [Plugin System](/en/architecture/plugin-system).

## Desktop integration

When the SPA runs inside the Electron shell, `frontend/src/mf/desktop.ts` acts as a facade over the
`window.fengyu` bridge, exposing `pickFile` and `pickDirectory` (which go through Electron's native
dialog via IPC). In a plain browser these fall back to standard browser equivalents.

The Electron shell exposes `window.fengyu` via a preload `contextBridge` before the page loads:

- `window.fengyu.apiBase()` — the backend base URL, e.g. `http://127.0.0.1:{port}` (read-only snapshot)
- `window.fengyu.token()` — the per-launch `X-FengYu-Token` value (read-only snapshot)
- `window.fengyu.desktop` — `true` feature flag (replaces the old `isTauri()` probe)

The `connection` store / `config.ts` reads these to configure every API call. `window.fengyu` is
`undefined` in a plain browser, where `config.ts` falls through to env vars; in dev (browser), the
Vite proxy serves the same `/api` and `/plugin-runtime` paths to `localhost:24056`.

## Setup guard

A vue-router navigation guard checks `getSetupStatus()` before allowing the user past the wizard. If the backend reports uninitialized, the guard redirects to `/setup` regardless of the target route. Once initialization completes, the user is released into the main app.

## Next steps

- [Architecture Overview](/en/architecture/overview) — how the SPA sits between the backend and the shell.
- [Desktop](/en/architecture/desktop) — where the `window.fengyu` bridge comes from.
- [Design System](/en/design-system) — the shared MD3 + Vuetify theming model.
