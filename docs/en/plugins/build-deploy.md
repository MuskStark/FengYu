---
title: Build & Deploy
description: Produce a .fyp package — fengyu.plugin.json build orchestration, the staged lifecycle (prepare → install → test → build → validate → package), GitHub Packages auth, offline install validation, and the .fyp layout.
lang: en
---

# Build & Deploy

A `.fyp` is a zip archive with a fixed runtime layout. There is **one** build flow for every plugin — third-party and official alike — driven by `fengyu plugin build` and the `fengyu.plugin.json` declaration. The legacy `OfficialPlugins/build-packages.sh` script has been removed; the three shipped plugins are now built by the same CLI.

## The `.fyp` layout

A produced `.fyp` contains exactly the runtime files — never source, build tooling, `node_modules`, or credentials:

```
my-plugin-1.0.0.fyp
├── manifest.json          # runtime metadata, permissions, aiTools
├── ui/                    # the Vite build output (ui-src/dist)
│   ├── index.html
│   └── assets/…
└── backend/
    └── worker.jar         # the shaded JSON-RPC worker executable
```

`manifest.json` declares `ui.entry` (`ui/index.html`) and `backend.command` (`java -jar backend/worker.jar`). UI-only plugins may omit `backend` entirely.

## `fengyu.plugin.json` — the build declaration

`manifest.json` stays **runtime-only**. Build orchestration (source paths, commands, output directories) lives in a separate `fengyu.plugin.json` at the project root, which the CLI resolves into a normalized project model:

```json
{
  "schemaVersion": 1,
  "ui": {
    "root": "ui-src",
    "output": "dist",
    "prepare": [["npm", "--prefix", "../shared", "run", "build"]],
    "install": ["npm", "ci"],
    "test": ["npm", "test"],
    "build": ["npm", "run", "build"]
  },
  "worker": {
    "root": "worker",
    "test": ["maven", "test"],
    "build": ["maven", "package", "-DskipTests"],
    "artifact": "target/my-worker.jar",
    "mainClass": "com.example.MyWorkerMain"
  },
  "package": { "outputDirectory": "dist-package" }
}
```

- `ui.prepare` is an ordered list of command arrays run **before** the plugin's own `npm ci` (e.g. to build shared `file:` dependencies). Omit it when not needed.
- The logical command `maven` is resolved to the project's **Maven Wrapper** (`mvnw` / `mvnw.cmd`). There is **never** a silent fallback to a system `mvn` — if no wrapper is found, the build fails with a precise message.
- Every configured path is resolved inside the plugin root; absolute paths, `..` escapes, and symlink escapes are rejected with the JSON field path in the error.

Zero-config projects (no `fengyu.plugin.json`) still build: a `vite.config.*` is detected as a Vue/Vite project (run `npm run build`, then package), and anything else is treated as a static `ui/` project.

## The staged lifecycle

`fengyu plugin build` runs an ordered, atomic pipeline for a declared project:

1. **ui.prepare** — each `ui.prepare` command, in order.
2. **ui.install** — `npm ci` when a `package-lock.json` exists (or `npm install` to generate one on a fresh scaffold). Skipped when `node_modules` is present and its lockfile fingerprint is unchanged.
3. **ui.test**, **worker.test** — run unless `--skip-tests` is passed.
4. **ui.build** — the Vite build (includes `vue-tsc --noEmit` type checking).
5. **worker.build** — the Maven Wrapper build producing the shaded JAR.
6. **assemble staging** — copy only `manifest.json`, the UI output, `backend/worker.jar`, and declared resources into an isolated temp directory.
7. **validate staging** — manifest object rules, `ui.entry` resolves to a real file, the backend command references `backend/worker.jar`, the worker JAR carries the configured `Main-Class` and class entry, and the runtime tree contains no source / `node_modules` / token-bearing files / symlinks.
8. **package** — write to `<output>.tmp-<pid>-<random>`, inspect the completed archive, then atomically rename to the final `.fyp`.

`--skip-tests` skips tests only — never type checking or packaging. Any failure (UI build, worker build, validation, rename) leaves **no** `.fyp`, no `.tmp-*`, and no staging directory behind.

### GitHub Packages authentication

The Java Worker SDK (`fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.0.0`) is published to GitHub Packages. External consumers resolve it through the `.mvn/settings.xml` the scaffold generates, which reads credentials from the environment only:

```bash
export FENGYU_GITHUB_TOKEN='<a GitHub token with read:packages>'
# GITHUB_TOKEN is also accepted; the CLI maps it to FENGYU_GITHUB_TOKEN for the child process.
```

Generated files never contain a token. If the wrapper root's `settings.xml` references `maven.pkg.github.com` and neither token is set, the CLI throws:

```
GitHub Packages authentication is required. Set FENGYU_GITHUB_TOKEN or GITHUB_TOKEN with read:packages.
```

Repository-internal builds (the official plugins) resolve the SDK from the local reactor install, so they need **no** token.

## Build the official plugins

The three shipped plugins are built by the same CLI — there is no separate script:

```bash
node plugin-cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-markdown
node plugin-cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-excel
node plugin-cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-email
```

Each writes `OfficialPlugins/plugin-<name>/dist-package/fan.summer.<name>-4.0.0.fyp`. CI builds them as a matrix in `.github/workflows/plugin-tooling.yml`.

## Install the result

`fengyu plugin install` validates the package **offline first** — it inspects archive limits and paths, validates the archived manifest and UI entry, and structurally checks any declared `backend/worker.jar` before network access — then uploads to the marketplace. In `fengyu.plugin.json`, every `package.resources[].to` value is a POSIX archive-relative path:

```bash
fengyu plugin install ./dist-package/com.example.my-plugin-1.0.0.fyp \
  --host http://127.0.0.1:24056 --token "$FENGYU_TOKEN"
```

An unsafe or invalid package is rejected with zero fetch calls. See [Marketplace](/en/plugins/marketplace) for the install/update/enable/uninstall endpoints.

## Next steps

- [SDK & CLI](/en/plugins/sdk-cli) — the full command reference, including `--ui-only`, `--no-install`, and `--skip-tests`.
- [Worker (JSON-RPC)](/en/plugins/worker) — what goes into `worker.jar`.
- [Marketplace](/en/plugins/marketplace) — installing the `.fyp` you built.
