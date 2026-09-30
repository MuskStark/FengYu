---
title: Official Plugin — Offline Python
description: Walkthrough of fan.summer.offlinepython (v4.0.0) — a dev-category plugin with files.read/files.write/network permissions that builds offline Python install repositories (wheelhouses) with all dependencies, via a three-panel UI and six stateless AI tools.
lang: en
---

# Official Plugin — Offline Python

`fan.summer.offlinepython` builds **offline Python install repositories** — a
self-contained directory (a "wheelhouse") holding every wheel needed to install your
project's dependencies on an air-gapped machine. You configure a project, resolve
dependencies, run an async `pip download` build, verify it, and optionally deploy it. It is
the canonical example of a plugin that combines file I/O, **async jobs**, and AI tools.

## What it does

- Initializes an offline-python project skeleton (`config.json` + `requirements.txt`).
- Resolves dependencies: searches PyPI for available wheels (version / platform / size).
- Builds a wheelhouse via `pip download` as an **async job** (start → poll status → cancel).
- Verifies a build output directory against its manifest (checksums, integrity, requirements).
- Deploys a built bundle to a target and runs a doctor check on the host Python/pip environment.
- Exposes six stateless AI tools so an Agent flow can drive the whole pipeline without the UI.

## The manifest

```json
{
  "schemaVersion": 2,
  "id": "fan.summer.offlinepython",
  "name": "Offline Python Builder",
  "description": "Build offline Python install repositories with all dependencies",
  "version": "4.0.0",
  "author": "FengYu",
  "icon": "language-python",
  "category": "dev",
  "ui": { "entry": "ui/index.html" },
  "backend": { "callTimeoutSeconds": 60 },
  "permissions": ["files.read", "files.write", "network"],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "rpc": { "methods": { /* init, config.*, build.*, deploy.*, … — see below */ } },
  "aiTools": [ /* six tools — see below */ ]
}
```

Key points:

- **`category: "dev"`** — a developer-tooling plugin.
- **`permissions: ["files.read", "files.write", "network"]`** — it reads/writes project files and **needs network** to reach PyPI during `pip download`. The `network` permission is what makes air-gapped *building* (not installing) possible.
- **`backend.callTimeoutSeconds: 60`** — the host spawns the standard `backend/worker.jar` and drives it over JSON-RPC on stdio.
- **`aiTools`** has six entries, so `supportsAi` is `true`.

## Methods

The worker (`OfflinePythonWorkerMain`) registers three groups of JSON-RPC methods:

**UI-facing, session-keyed workflow:**

| Method | Purpose |
| --- | --- |
| `init` | Initialize a project skeleton in a writable directory. |
| `config.get` / `config.save` | Read / write the project `config.json`. |
| `requirements.get` / `requirements.save` | Read / write the project `requirements.txt`. |
| `python.detect` | Detect the host Python/pip executable and version. |
| `deps.latest` / `deps.search` | Resolve latest versions / search PyPI for available wheels. |
| `verify` | Verify a build output directory against its manifest. |
| `package` | Package a built output directory into a deployable bundle. |
| `doctor` | Diagnose the host Python/pip environment for offline-build readiness. |

**Async build / deploy** (start → jobId → poll `*.status` → `*.cancel`):

| Method | Purpose |
| --- | --- |
| `build.start` / `build.status` / `build.cancel` | Run `pip download` asynchronously; a real download routinely exceeds the host's ~60s single-RPC limit, so it runs as a job. |
| `deploy.start` / `deploy.status` / `deploy.cancel` | Deploy a built bundle to a target asynchronously. |

**AI-facing, stateless tools** (declared in `manifest.aiTools[]`):

| Tool | Maps to | Purpose |
| --- | --- | --- |
| `offlinepython_doctor` | `doctor` | Diagnose host Python/pip for build readiness. |
| `offlinepython_search_deps` | `deps.search` | Search PyPI for a package's wheels. |
| `offlinepython_init_project` | `init` | Initialize a project skeleton. |
| `offlinepython_verify` | `verify` | Verify a build against its manifest. |
| `offlinepython_build_start` | `build.start` | Start an async wheelhouse build → `jobId`. |
| `offlinepython_build_status` | `build.status` | Poll a build job's status and streamed logs. |

The AI tools are the same services the UI drives — just callable from chat. See
[AI Tools](/en/plugins/ai-tools) and [Worker (JSON-RPC)](/en/plugins/worker).

## The three-panel UI

The UI is a `FyPluginShell` with three panels (not a wizard):

```
  Project (🔧)  ──►  Deploy (📦)  ──►  Doctor (🩺)
  configure,        build wheelhouse     diagnose host
  resolve deps      + verify + package   Python/pip env
```

- **Project** — pick a writable project directory grant, initialize it, edit `config.json` /
  `requirements.txt`, detect Python, and resolve/search dependencies.
- **Deploy** — run the async `pip download` build, watch streamed logs, verify the output,
  package it, and deploy to a target. The panel auto-detects this machine's Python interpreter on
  load (the one that will run `pip install`) and offers a manual path override when detection fails
  — this matters on offline machines whose interpreter (conda/pyenv/venv) is not on `PATH`, which
  differs from the build machine's interpreter.
- **Doctor** — run environment diagnostics on the host Python/pip setup.

A shared writable `FileRef` (the project directory, granted by the host) is selected in the
project/deploy panels and read by the others — only that shared state lives at the App level.

It loads in the sandboxed iframe under
`/plugin-runtime/fan.summer.offlinepython/**` and bridges to the host via `@infinia/plugin-sdk`.
See [UI Micro-frontend](/en/plugins/ui-microfrontend).

## Why async jobs

The host kills any single RPC after roughly 60 seconds, but a real `pip download` of a large
dependency tree routinely exceeds that. Build and deploy therefore run as async **jobs**: `build.start`
returns a `jobId` immediately, and the UI polls `build.status` (with a log cursor) until completion.
`build.cancel` stops a running job. See [Worker (JSON-RPC)](/en/plugins/worker).

## Next steps

- [Manifest](/en/plugins/manifest) — the full schema, including `aiTools` and `permissions`.
- [AI Tools](/en/plugins/ai-tools) — how the six `offlinepython_*` tools aggregate into Spring AI `ToolCallback[]`.
- [File I/O](/en/plugins/file-io) — the grant model behind the project-directory flow.
- [Official Plugin — Excel](/en/plugins/official-excel) — a sibling plugin with a wizard UI.
