---
title: Build & Deploy
description: Build a conventional FengYu plugin into an atomic .fyp package and checksum.
lang: en
---

# Build & Deploy

Toolchain 2 builds every plugin from a standard layout. The legacy per-plugin build-config file
and arbitrary build command arrays have been removed.

## Project and package layouts

A Worker source plugin contains `manifest.base.json`, a language contract, optional Flow/i18n
overlays, and a UI at `ui-src/package.json`. A manifest-first UI-only plugin may instead contain
`manifest.json`. A packaged `.fyp` always contains one compiled `manifest.json` plus:

```text
manifest.json
ui/
  index.html
backend/
  worker.jar | worker.py | worker[.exe]
```

UI commands come from the project's standard scripts `dev`, optional `test`, and `build` — npm for
scaffolded projects, Yarn 4 (pinned via `packageManager`) for the in-repo official plugins. Worker commands
are runtime-conventional: Maven via the nearest wrapper, Python tests plus `worker.py`, or Go tests
plus one native executable.

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

Install a resulting `.fyp` through the host marketplace UI or `POST /api/plugin-packages/upload`
(the deprecated `/api/plugin-market/upload` alias still forwards). The host validates archive limits, paths, manifest, UI entry, and Worker structure before
registering it.
