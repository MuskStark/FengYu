---
title: Getting Started
description: Create, develop, check, and build a conventional FengYu plugin.
lang: en
---

# Getting Started

Create a Vue plugin with a Java, Python, or Go Worker:

```bash
fengyu init ./my-plugin --id com.example.my-plugin --runtime java
cd my-plugin
```

Choose `--runtime python` or `--runtime go` for those Worker SDKs. FengYu requires Python 3.12+
or Go 1.26+ to build them. Use `--no-install` to skip dependency installation or `--ui-only` to
omit the worker. The generated
project follows the Toolchain 2 standard layout:

```text
my-plugin/
├── manifest.json
├── mvnw, mvnw.cmd
├── .mvn/
├── ui-src/
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
└── worker/
    ├── pom.xml
    ├── src/main/java/…
    └── src/test/java/…/PluginDevMain.java
```

There is no separate legacy build-config file; the single descriptor is `manifest.json`. The CLI
uses npm's `dev`, optional `test`, and `build` scripts, plus the Maven `test` and `package`
lifecycle. The Java worker build must emit one `worker/target/*-worker.jar`. Python scaffolds use
`worker/worker.py` plus a vendored `fengyu_plugin_sdk`; Go scaffolds use `worker/main.go` plus the
vendored SDK module and build one native executable. Packaging normalizes them to
`backend/worker.py` and `backend/worker[.exe]`.

Run the UI simulator through the unified command:

```bash
fengyu dev
```

For a Java plugin, also debug `PluginDevMain.main()` in the IDE. It exposes the same handlers over
loopback TCP, so breakpoints fire without the CLI owning the Worker process. Open
`http://127.0.0.1:5173/__fengyu` for the simulator.

Check and package the project:

```bash
fengyu check
fengyu build
```

The package and checksum are written to `dist/<id>-<version>.fyp[.sha256]`. Install the `.fyp`
through the host marketplace UI. See [SDK & CLI](/en/plugins/sdk-cli) and
[Build & Deploy](/en/plugins/build-deploy) for the contracts.
