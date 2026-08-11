# FengYu Plugin CLI

The verified end-to-end path for creating and packaging FengYu plugins. Development happens in your
IDE via [`@infinia/plugin-dev`](../dev) — see that package's README for the dev guide.

```bash
# Scaffold a complete Vue + Java plugin (default), or --ui-only for the lightweight template
fengyu plugin create my-plugin --id com.example.my-plugin
fengyu plugin create my-plugin --id com.example.my-plugin --no-install   # skip npm install
fengyu plugin create my-plugin --id com.example.my-plugin --ui-only      # no Java worker

# Develop: open the project in your IDE
#   UI:     cd ui-src && npm run dev                 # → http://127.0.0.1:5173/__fengyu
#   Worker: Debug PluginDevMain (in worker/src/test/java)   # → listens on 127.0.0.1:24057
# See @infinia/plugin-dev for the full IDE debugging guide.

# Build the .fyp (staged lifecycle: prepare → install → test → build → validate staging → package)
fengyu plugin build my-plugin
fengyu plugin build my-plugin --skip-tests   # skip tests only, never type checking, validation, or packaging
```

There are exactly **two** subcommands — `create` and `build`. `dev`, `validate`, and `install` are
no longer CLI commands:

- **`dev`** moved to the IDE via `@infinia/plugin-dev` (Vite plugin) + `fengyu-plugin-devkit`
  (`PluginDevMain`) — you get real breakpoints in your worker handlers instead of a CLI-managed
  process.
- **`validate`** is now a built-in step of `build` (the staging tree is always validated before
  packaging).
- **`install`** is done through the host's plugin marketplace UI (`POST /api/plugin-market/upload`).

## Scaffolding

`create` produces a Vue 3 + Vuetify UI backed by a Java JSON-RPC worker by default. The generated project
contains `manifest.json` (runtime), `fengyu.plugin.json` (build orchestration), `ui-src/` (Vue/Vite,
with `@infinia/plugin-dev` wired into `vite.config.ts`), `worker/` (Java + the Maven Wrapper, with
`PluginDevMain` scaffolded under `src/test/java`), and `.mvn/settings.xml` (GitHub Packages auth via
env vars, no committed token). `npm install` runs inside `ui-src` by default; `--no-install` skips it.
If installation fails, the generated files are left in place and the CLI prints the exact command to retry.

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

The Java Worker SDK (`fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.3.0`) and the devkit
(`fan.summer.fengyu.sdk:fengyu-plugin-devkit:1.3.0`) are published to GitHub Packages. External worker
builds authenticate via `FENGYU_GITHUB_TOKEN` (or `GITHUB_TOKEN`, with `read:packages`). If the wrapper
root's `settings.xml` references `maven.pkg.github.com` and neither token is set, the CLI throws a
precise auth message rather than failing ambiguously.
