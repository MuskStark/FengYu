---
title: Getting Started
description: Scaffold a FengYu plugin with fengyu plugin create, understand the produced directory layout, and run it locally with fengyu plugin dev.
lang: en
---

# Getting Started

This page walks through creating a new plugin from scratch, the directory layout the scaffolder produces, and running it locally. The `fengyu plugin` CLI has five subcommands — `create`, `dev`, `build`, `validate`, `install` — and this page covers the first two. The full command table is on the [SDK & CLI](/en/plugins/sdk-cli) page.

## Scaffold a plugin

Create a new plugin with `fengyu plugin create`. You must pass a reverse-DNS `--id`:

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin
```

The scaffolder refuses to overwrite an existing directory, writes a starting `manifest.json`, `package.json`, a minimal `ui/index.html` + `ui/app.js`, and copies the SDK bundle to `ui/sdk.js`. The human-readable `name` is derived from the last segment of `--id` (here `My Plugin`).

## Directory layout

After scaffolding, the project looks like this:

```
my-plugin/
├── manifest.json     # metadata, permissions, aiTools — see /en/plugins/manifest
├── package.json      # npm manifest; depends on @fengyu/plugin-sdk
└── ui/
    ├── index.html    # entry HTML, served at /plugin-runtime/{id}/ui/index.html
    ├── app.js        # your UI code; imports './sdk.js'
    └── sdk.js        # @fengyu/plugin-sdk bundle (copied by the scaffolder)
```

Two pieces are still missing for a runnable package — the worker — and you add them yourself:

- `backend/worker.jar` — your JSON-RPC worker executable, built with the Java Worker SDK (see [Worker](/en/plugins/worker) and [Build & Deploy](/en/plugins/build-deploy)). Declare its launch command in `manifest.json` under `backend.command`.

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
  "backend": { "command": "java -jar backend/worker.jar", "protocol": "json-rpc-2.0" },
  "permissions": [],
  "official": false,
  "aiTools": []
}
```

For the full schema — including every valid `category` and `permissions` value — see [Manifest](/en/plugins/manifest).

## Run it locally

`fengyu plugin dev` starts a tiny loopback dev host that serves your `ui/` and simulates the host's `postMessage` bridge so you can exercise `FengYuClient` without the backend:

```bash
fengyu plugin dev --port 4173
```

- The dev host binds `127.0.0.1` only.
- Open the printed URL (`http://127.0.0.1:4173/__fengyu`) to load an RPC inspector shell that hosts your `ui/index.html` in a sandboxed iframe.
- File changes trigger a live reload via Server-Sent Events.
- `rpc.invoke` calls return a dev mock `{success:true, devMock:true, method, params}` — connect a real worker when you need real behavior.

The default port is `4173`; omit `--port` to use it.

## Next steps

- [Manifest](/en/plugins/manifest) — every field, type, and default.
- [Worker (JSON-RPC)](/en/plugins/worker) — write the `backend/worker.jar`.
- [UI Micro-frontend](/en/plugins/ui-microfrontend) — the `FengYuClient` API your `ui/app.js` uses.
- [Build & Deploy](/en/plugins/build-deploy) — `fengyu plugin build` to produce a `.fyp`.
