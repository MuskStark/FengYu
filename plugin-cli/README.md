# FengYu Plugin CLI

The verified end-to-end path for creating, developing, testing, validating, packaging, and installing FengYu plugins.

```bash
# Scaffold a complete Vue + Java plugin (default), or --ui-only for the lightweight template
fengyu plugin create my-plugin --id com.example.my-plugin
fengyu plugin create my-plugin --id com.example.my-plugin --no-install   # skip npm install
fengyu plugin create my-plugin --id com.example.my-plugin --ui-only      # no Java worker

# Develop against a REAL Java JSON-RPC worker (rebuilds/restarts on Java edits)
fengyu plugin dev my-plugin --port 4173

# Validate the source manifest
fengyu plugin validate my-plugin

# Build the .fyp (staged lifecycle: prepare → install → test → build → validate → package)
fengyu plugin build my-plugin
fengyu plugin build my-plugin --skip-tests   # skip tests only, never type checking or packaging

# Install (validates offline first, then uploads)
fengyu plugin install my-plugin/dist-package/com.example.my-plugin-1.0.0.fyp --host http://127.0.0.1:24056
```

## Scaffolding

`create` produces a Vue 3 + Vuetify UI backed by a Java JSON-RPC worker by default. The generated project
contains `manifest.json` (runtime), `fengyu.plugin.json` (build orchestration), `ui-src/` (Vue/Vite),
`worker/` (Java + the Maven Wrapper), and `.mvn/settings.xml` (GitHub Packages auth via env vars, no
committed token). `npm install` runs inside `ui-src` by default; `--no-install` skips it. If installation
fails, the generated files are left in place and the CLI prints the exact command to retry.

## Build declaration (`fengyu.plugin.json`)

`manifest.json` stays runtime-only; build commands and source paths live in `fengyu.plugin.json`. The
logical command `maven` resolves to the project's Maven Wrapper — there is never a silent fallback to a
system Maven. Configured paths must resolve inside the plugin root; absolute paths, `..` escapes, and
symlink escapes are rejected. Zero-config projects (no `fengyu.plugin.json`) still build: a `vite.config.*`
is detected as Vue/Vite, anything else as static `ui/`.

## Build lifecycle

`build` runs an ordered, atomic pipeline: `ui.prepare` → `ui.install` → [`ui.test`, `worker.test`] →
`ui.build` → `worker.build` → assemble staging → validate staging → atomically package. Staging copies
only `manifest.json`, the UI output, `backend/worker.jar`, and declared resources — never source,
`node_modules`, or credentials. Any failure leaves no `.fyp`, no `.tmp-*`, and no staging directory.

## GitHub Packages authentication

The Java Worker SDK (`fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.0.0`) is published to GitHub Packages.
External worker builds authenticate via `FENGYU_GITHUB_TOKEN` (or `GITHUB_TOKEN`, with `read:packages`).
If the wrapper root's `settings.xml` references `maven.pkg.github.com` and neither token is set, the CLI
throws a precise auth message rather than failing ambiguously.

## Install

`install` validates the archive **offline first** — inspecting limits/paths and the archived manifest
before any network access — then uploads to the marketplace. An unsafe or invalid package is rejected
with zero fetch calls.
