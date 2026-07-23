---
title: Getting Started
description: Scaffold a FengYu plugin with fengyu plugin create (Vue + Java by default), understand the produced project layout, and debug it locally in your IDE via the @infinia/plugin-dev Vite plugin + fengyu-plugin-devkit PluginDevMain.
lang: en
---

# Getting Started

This page walks through creating a new plugin from scratch, the directory layout the scaffolder produces, and debugging it locally in your IDE. The `fengyu plugin` CLI has two subcommands — `create` and `build`; this page covers `create` and the IDE dev loop. The full command table is on the [SDK & CLI](/en/plugins/sdk-cli) page.

## Scaffold a plugin

Create a new plugin with `fengyu plugin create`. You must pass a reverse-DNS `--id`:

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin
```

By default the scaffolder produces a **complete Vue + Java plugin**: a Vue 3 + Vuetify UI that calls a Java JSON-RPC worker, plus the Maven Wrapper and the build declaration. It also runs `npm install` inside `ui-src` so the project is ready to run immediately. Pass `--no-install` to skip the install:

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin --no-install
```

Pass `--ui-only` to keep the lightweight UI-only scaffold (no Java worker) — useful for pure-frontend plugins:

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin --ui-only
```

The scaffolder refuses to overwrite an existing directory. The human-readable `name` is derived from the last segment of `--id` (here `My Plugin`); the Java package and class prefix are derived from the id too (`com.example.my_plugin` → `MyPluginWorkerMain`).

## Quick start

The full loop, from nothing to a packaged `.fyp`:

```bash
npx --yes @infinia/plugin-cli@1.1.0 plugin create my-plugin --id com.example.my-plugin
cd my-plugin
export FENGYU_GITHUB_TOKEN='<GitHub token with read:packages>'
# Develop in your IDE (see "Run it locally" below):
#   UI:     cd ui-src && npm run dev                 # → http://127.0.0.1:5173/__fengyu
#   Worker: Debug PluginDevMain (in worker/src/test/java)
npx --yes @infinia/plugin-cli@1.1.0 plugin build .
# Install the built .fyp via the host's plugin marketplace UI (POST /api/plugin-market/upload).
```

- `create` installs UI deps by default; `--no-install` skips it.
- The worker resolves the Java Worker SDK from GitHub Packages via `.mvn/settings.xml` (the `FENGYU_GITHUB_TOKEN` env var). See [Build & Deploy](/en/plugins/build-deploy).
- The generated `App.vue` calls the worker's `hello` method end-to-end through the host RPC bridge.

## Directory layout

After scaffolding (Vue + Java), the project looks like this:

```
my-plugin/
├── manifest.json          # runtime metadata, permissions, aiTools — see /en/plugins/manifest
├── fengyu.plugin.json     # build orchestration (UI + worker commands) — see /en/plugins/build-deploy
├── mvnw, mvnw.cmd         # Maven Wrapper (3.9.11) — the only Maven the build uses
├── .mvn/
│   ├── settings.xml       # GitHub Packages auth (env-driven, no committed token)
│   └── wrapper/…
├── ui-src/                # the Vue/Vuetify front-end
│   ├── package.json
│   ├── vite.config.ts     # loads @infinia/plugin-dev; builds into ./dist
│   └── src/{main.ts, App.vue}
└── worker/                # the Java JSON-RPC worker
    ├── pom.xml            # depends on fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.1.0 (+ devkit, test scope)
    └── src/
        ├── main/java/<pkg>/{<Prefix>Worker, <Prefix>WorkerMain}.java
        └── test/java/<pkg>/{<Prefix>WorkerTest, PluginDevMain}.java
```

`ui-src` builds into `ui-src/dist`, which the build stages as `ui/` inside the `.fyp`. The worker builds into `worker/target/<prefix>-worker.jar`, staged as `backend/worker.jar`.

## Run it locally

Development happens in your IDE — the CLI no longer runs a dev server. The scaffolded `vite.config.ts`
loads `@infinia/plugin-dev`, which turns the Vite dev server into a FengYu host simulator; the scaffolded
`PluginDevMain` (under `worker/src/test/java`) exposes your worker over loopback TCP so IDE breakpoints
fire directly.

**UI side** — start the Vite dev server in `ui-src/`:

```bash
cd ui-src && npm run dev
```

Open `http://127.0.0.1:5173/__fengyu` to load the simulator shell. The plugin UI runs in an iframe
with full HMR; the shell bridges `@infinia/plugin-sdk`'s `postMessage` calls and forwards `rpc.invoke`
to the worker endpoint configured in `vite.config.ts` (default `127.0.0.1:24057`).

**Worker side** — in your IDE, run `PluginDevMain.main()` from the **Debug** action (not
`<Prefix>WorkerMain`, which is the production stdio entry). It starts the `fengyu-plugin-devkit`
loopback TCP server at `127.0.0.1:24057`, serving the **same handlers** as the production worker via
`<Prefix>Worker.create()`. Set breakpoints in your handlers — they fire when the UI calls `rpc.invoke`.

- Both endpoints bind `127.0.0.1` only.
- Toggle **theme** (dark/light) and **locale** (en/zh) from the simulator's control buttons to verify
  your UI reacts to `bindFengYuEnvironment`.
- When the plugin iframe requests a file or directory, the simulator offers a browser picker and
  snapshots the selection into a temporary dev directory before registering its FileRef. A manual
  absolute-path field remains available for desktop-style in-place I/O. Workspace snapshots are
  writable, output directories can be downloaded as zip files through `files.export`, FileRefs are
  rewritten to Worker-visible paths at `rpc.invoke`, and temporary directories are removed when
  Vite closes.
- Change the worker port with `-Dfengyu.dev.port=<n>` and update `workerEndpoint` in `vite.config.ts`
  to match.
- A configured but unavailable worker produces an RPC error; it never silently falls back to mock
  data. Set `mockWorker: true` only when stub responses are intentional.

For UI-only plugins, `vite.config.ts` sets `mockWorker: true` — `rpc.invoke` returns a deterministic
stub, so you can iterate the UI before any worker exists. See
[`plugin-dev/README.md`](https://github.com/MuskStark/FengYu/tree/main/plugin-dev) for the full guide.

## Next steps

- [UI Components](/en/plugins/ui-components) — the `@infinia/plugin-ui` kit: shell, file picker, step wizard, and more.
- [Manifest](/en/plugins/manifest) — every field, type, and default.
- [Worker (JSON-RPC)](/en/plugins/worker) — write the `backend/worker.jar`.
- [Build & Deploy](/en/plugins/build-deploy) — the staged lifecycle, GitHub Packages auth, and `.fyp` packaging.
