---
title: Build & Deploy
description: Build a conventional FengYu plugin into an atomic .fyp package and checksum.
lang: en
---

# Build & Deploy

Toolchain 2 builds every plugin from a standard layout. `fengyu.plugin.json` and arbitrary build
command arrays have been removed.

## Project and package layouts

A source plugin contains `manifest.json`, a UI at `ui-src/package.json` (or prebuilt `ui/`), and an
optional Worker at `worker/pom.xml` or root `pom.xml`. A packaged `.fyp` contains only:

```text
manifest.json
ui/
  index.html
backend/
  worker.jar
```

UI commands come from the standard npm scripts `dev`, optional `test`, and `build`. Worker commands
are Maven `test` and `package`, run through the nearest Maven Wrapper. A Worker build must produce
exactly one `target/*-worker.jar`.

## Lifecycle

```bash
fengyu check .
fengyu build .
```

`build` installs UI dependencies when needed, runs tests unless `--skip-tests` is set, builds the
UI and Worker, stages runtime-only files, validates the manifest/UI/JAR, and atomically writes:

```text
dist/<plugin-id>-<version>.fyp
dist/<plugin-id>-<version>.fyp.sha256
```

Use `--out <file>` to select another archive path. A failed build removes temporary staging and
archive files.

External Java plugins resolve the independently versioned Worker SDK through the generated
`.mvn/settings.xml`. Set `FENGYU_GITHUB_TOKEN` or `GITHUB_TOKEN` with `read:packages`; credentials
are passed through the environment and never written into the project.

## Official plugins

```bash
node toolchain/cli/bin/fengyu.mjs build OfficialPlugins/plugin-markdown
node toolchain/cli/bin/fengyu.mjs build OfficialPlugins/plugin-excel
node toolchain/cli/bin/fengyu.mjs build OfficialPlugins/plugin-email
node toolchain/cli/bin/fengyu.mjs build OfficialPlugins/plugin-offlinepython
```

Install a resulting `.fyp` through the host marketplace UI or `POST /api/plugin-market/upload`.
The host validates archive limits, paths, manifest, UI entry, and Worker structure before
registering it.
