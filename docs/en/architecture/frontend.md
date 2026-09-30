---
title: Frontend
description: The Infinia frontend is a React 19 + TypeScript SPA — zustand state, react-router, react-i18next, and Tailwind 4 over the zai.css token system — that loads plugin UIs as micro-frontends and redirects to /setup until initialization completes.
lang: en
---

# Frontend

The Infinia frontend is a **React 19 single-page application** written in TypeScript. It renders the host shell and loads plugin UIs as micro-frontends at runtime. The same bundle runs unchanged in a browser tab and inside the Electron BrowserWindow.

## Stack

| Package | Version (major) | Role |
| --- | --- | --- |
| `react` / `react-dom` | 19 | UI framework |
| `react-router-dom` | 7 | Routing |
| `zustand` | 5 | State management |
| `i18next` / `react-i18next` | 25 / 16 | Internationalization |
| `tailwindcss` + `zai.css` | 4 | Styling and design tokens |
| `@xyflow/react` | 12 | FengyuFlow canvas |
| `vite` | 7 | Dev server + build |

## Two-layer architecture

The tree is split into a framework-agnostic core and a thin UI edge:

- `src/platform/` — environment abstractions: the `window.fengyu` desktop facade, URL/config resolution, and browser fallbacks. Nothing here renders.
- `src/services/` — the typed service layer every API call goes through (with structured error codes mapped to i18n messages).
- `src/stores/` — focused zustand stores: `aiSession`, `backgroundTasks`, `connection`, `notifications`, `pluginBackgroundJobs`, `plugins`, `settings`, `skills`, `toasts`, `update`.

## Micro-frontend host

Plugin UIs are not bundled into the SPA. `PluginPage.tsx` loads each plugin's `uiEntry` in a sandboxed iframe. The iframe and host negotiate the shared `@infinia/plugin-sdk/protocol` version, then exchange typed request, response, cancellation, and environment messages over `postMessage`. A plugin cannot reach into the host's React tree; inside the isolation boundary it renders with `@infinia/plugin-ui` (a plugin-local Vue/Vuetify instance). Details live on [Plugin System](/en/architecture/plugin-system).

## Desktop integration

When the SPA runs inside the Electron shell, `frontend/src/platform/desktop.ts` acts as a facade over the `window.fengyu` bridge, exposing `pickFile` and `pickDirectory` (which go through Electron's native dialogs via IPC). In a plain browser these fall back to standard browser equivalents.

The Electron shell exposes `window.fengyu` via a preload `contextBridge` before the page loads:

- `window.fengyu.apiBase()` — the backend base URL, e.g. `http://127.0.0.1:{port}` (read-only snapshot)
- `window.fengyu.token()` — the per-launch `X-FengYu-Token` value (read-only snapshot)
- `window.fengyu.desktop` — `true` feature flag

The `connection` store and the platform layer read these to configure every API call. `window.fengyu` is `undefined` in a plain browser, where the platform layer falls through to env vars; in dev (browser), the Vite proxy serves the same `/api` and `/plugin-runtime` paths to `localhost:24056`.

## Setup guard

The app-level guard (`App.tsx`, behind `shell/BootGate.tsx`) checks `services.system.setupStatus()` before allowing the user past the wizard. If the backend reports uninitialized, the guard redirects to `/setup` regardless of the target route. Once initialization completes, the user is released into the main app.

## Next steps

- [Architecture Overview](/en/architecture/overview) — how the SPA sits between the backend and the shell.
- [Desktop](/en/architecture/desktop) — where the `window.fengyu` bridge comes from.
- [Design System](/en/design-system) — the Zai token model and how plugin UIs stay themed.
