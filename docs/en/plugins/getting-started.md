---
title: Getting Started
description: Scaffold a FengYu plugin with fengyu plugin create (Vue + Java by default), understand the produced project layout, and run it locally with a real-worker dev simulator.
lang: en
---

# Getting Started

This page walks through creating a new plugin from scratch, the directory layout the scaffolder produces, and running it locally. The `fengyu plugin` CLI has five subcommands — `create`, `dev`, `build`, `validate`, `install` — and this page covers the first two. The full command table is on the [SDK & CLI](/en/plugins/sdk-cli) page.

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
npx --yes @infinia/plugin-cli@1.0.0 plugin create my-plugin --id com.example.my-plugin
cd my-plugin
export FENGYU_GITHUB_TOKEN='<GitHub token with read:packages>'
npx --yes @infinia/plugin-cli@1.0.0 plugin dev .
npx --yes @infinia/plugin-cli@1.0.0 plugin build .
npx --yes @infinia/plugin-cli@1.0.0 plugin install dist-package/com.example.my-plugin-1.0.0.fyp --host http://127.0.0.1:24056
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
│   ├── vite.config.ts     # builds into ./dist
│   └── src/{main.ts, App.vue}
└── worker/                # the Java JSON-RPC worker
    ├── pom.xml            # depends on fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.0.0
    └── src/main/java/<pkg>/<Prefix>WorkerMain.java
```

`ui-src` builds into `ui-src/dist`, which the build stages as `ui/` inside the `.fyp`. The worker builds into `worker/target/<prefix>-worker.jar`, staged as `backend/worker.jar`.

## Run it locally

`fengyu plugin dev` detects the declared project, builds the worker JAR if it is missing, starts the **real** Java JSON-RPC worker, and serves a loopback simulator whose `rpc.invoke` calls are forwarded to the worker over `POST /__rpc`:

```bash
fengyu plugin dev .
```

- The dev host binds `127.0.0.1` only.
- Open the printed URL (`http://127.0.0.1:4173/__fengyu`) to load the RPC inspector shell.
- Edits to Java sources under `worker/` (excluding `target/`) trigger a debounced rebuild + worker restart; while rebuilding, RPC calls return `worker rebuilding`.
- Toggle **theme** (dark/light) and **locale** (en/zh) from the inspector's control buttons to verify your UI reacts to `bindFengYuEnvironment`.
- The default port is `4173`; pass `--port` to change it.

For UI-only and static projects, the simulator keeps the previous mock behavior (no worker).

## Next steps

- [UI Components](/en/plugins/ui-components) — the `@infinia/plugin-ui` kit: shell, file picker, step wizard, and more.
- [Manifest](/en/plugins/manifest) — every field, type, and default.
- [Worker (JSON-RPC)](/en/plugins/worker) — write the `backend/worker.jar`.
- [Build & Deploy](/en/plugins/build-deploy) — the staged lifecycle, GitHub Packages auth, and `.fyp` packaging.
