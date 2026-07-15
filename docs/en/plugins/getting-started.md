---
title: Getting Started
description: Scaffold a FengYu plugin with fengyu plugin create, understand the produced Vue/Vuetify project layout, and run it locally with fengyu plugin dev.
lang: en
---

# Getting Started

This page walks through creating a new plugin from scratch, the directory layout the scaffolder produces, and running it locally. The `fengyu plugin` CLI has five subcommands — `create`, `dev`, `build`, `validate`, `install` — and this page covers the first two. The full command table is on the [SDK & CLI](/en/plugins/sdk-cli) page.

## Scaffold a plugin

Create a new plugin with `fengyu plugin create`. You must pass a reverse-DNS `--id`:

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin
```

By default the scaffolder **also runs `npm install`** in the new project so it is ready to run immediately. Pass `--no-install` to skip the install (e.g. when you want to use a different package manager or a local `file:` dependency):

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin --no-install
```

The scaffolder refuses to overwrite an existing directory and writes a Vue 3 + Vuetify (Material Design 3) project from the `vue-codex` template. The human-readable `name` is derived from the last segment of `--id` (here `My Plugin`). FengYu UI components are imported from [`@fengyu/plugin-ui`](/en/plugins/ui-components); the generated `src/main.ts` already binds the host theme and locale, so you do not need to wire that up yourself.

## Quick start

The full loop, from nothing to a packaged `.fyp`, is four commands:

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin
cd my-plugin
fengyu plugin dev .
fengyu plugin build .
```

- `create` installs by default; `--no-install` skips it.
- `src/main.ts` already binds theme/locale and provides the `FengYuClient` to the whole app — see [UI Components](/en/plugins/ui-components).
- The base controls you compose with (`v-btn`, `v-card`, `v-list`, …) are ordinary Vuetify controls, already registered globally by `createFengYuVuetify`.
- FengYu components (`FyFilePicker`, `FyStepWizard`, …) import from `@fengyu/plugin-ui`.
- Legacy static plugins (a plain `ui/index.html` + `ui/app.js` with no build step) remain fully supported by `dev` and `build`; migrating is **optional**.

## Directory layout

After scaffolding, the project looks like this:

```
my-plugin/
├── manifest.json     # metadata, permissions, aiTools — see /en/plugins/manifest
├── package.json      # npm manifest; depends on @fengyu/plugin-sdk + @fengyu/plugin-ui
├── index.html        # Vite entry HTML
├── vite.config.ts    # builds into ./ui (so manifest.ui.entry resolves)
├── tsconfig.json
└── src/
    ├── main.ts       # mounts the app, binds theme/locale, provides FengYuClient
    └── App.vue       # your UI: FyPluginShell + FyPageHeader + FyFilePicker + …
```

::: tip Static plugins
A legacy static plugin keeps the old shape — `ui/index.html` + `ui/app.js` + a copied `ui/sdk.js`, no Vite. `dev` and `build` detect which kind of project it is automatically. See [UI Micro-frontend](/en/plugins/ui-microfrontend).
:::

The one piece still missing for a backend-connected plugin is the worker, which you add yourself:

- `backend/worker.jar` — your JSON-RPC worker executable, built with the Java Worker SDK (see [Worker](/en/plugins/worker) and [Build & Deploy](/en/plugins/build-deploy)). Declare its launch command in `manifest.json` under `backend.command`. In dev, the simulator answers `rpc.invoke` with a mock, so you can build the UI before the worker exists.

## Edit the manifest

Open `manifest.json` and adjust the fields the scaffolder cannot guess. The minimum you usually touch:

```json
{
  "schemaVersion": 1,
  "id": "com.example.my-plugin",
  "name": "My Plugin",
  "description": "What this plugin does",
  "version": "1.0.0",
  "author": "Your Name",
  "icon": "puzzle-outline",
  "category": "other",
  "ui": { "entry": "ui/index.html" },
  "permissions": [],
  "official": false,
  "aiTools": []
}
```

Note `ui.entry` points at `ui/index.html` — the **output** of `vite build` (configured by `vite.config.ts`'s `build.outDir: 'ui'`). For the full schema — including every valid `category` and `permissions` value — see [Manifest](/en/plugins/manifest).

## Run it locally

`fengyu plugin dev` detects the Vue/Vite project, starts Vite (with HMR), and serves a loopback simulator page that hosts your app in a sandboxed iframe and answers the SDK `postMessage` calls:

```bash
fengyu plugin dev .
```

- The dev host binds `127.0.0.1` only.
- Open the printed URL (`http://127.0.0.1:4173/__fengyu`) to load the RPC inspector shell; its iframe points at the Vite dev server, so edits hot-reload.
- The simulator answers `host.ready` with the current theme/locale, `rpc.invoke` with a dev mock `{success:true, devMock:true, method, params}`, `files.open` with a sample file, and `notify` with success.
- Toggle **theme** (dark/light) and **locale** (en/zh) from the inspector's control buttons to verify your UI reacts to `bindFengYuEnvironment`.
- The default port is `4173`; pass `--port` to change it.

## Next steps

- [UI Components](/en/plugins/ui-components) — the `@fengyu/plugin-ui` kit: shell, file picker, step wizard, and more.
- [Manifest](/en/plugins/manifest) — every field, type, and default.
- [Worker (JSON-RPC)](/en/plugins/worker) — write the `backend/worker.jar`.
- [UI Micro-frontend](/en/plugins/ui-microfrontend) — the `FengYuClient` API your UI talks to.
- [Build & Deploy](/en/plugins/build-deploy) — `fengyu plugin build` to produce a `.fyp`.
