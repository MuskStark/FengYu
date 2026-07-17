# AGENTS.md

Canonical repository guidance for AI coding assistants (Codex-style and others).
Claude Code reads `CLAUDE.md`, which points here. Keep this file the single source
of project guidance; do not duplicate it elsewhere.

## What FengYu 4.0.0 is

Infinia (蜂语 / FengYu) is a **headless web + desktop application**, not a JavaFX app:

- **Backend** — a headless Spring Boot web server that binds **loopback only** (`127.0.0.1`,
  default port `24056`). No window, no JavaFX. Entry point: `fan.summer.fengyu.HeadlessLauncher`
  in the `FengYu` module (CLI: `--port=<n>`, `--token=<t>`). It has a first-launch **SETUP mode**
  (database wizard) that restarts into **APP mode**.
- **Frontend** — a Vue 3.5 + TypeScript SPA in `frontend/` (Vuetify 3 / Material Design 3,
  Pinia, vue-router, vue-i18n). It runs identically in a browser or inside the desktop webview.
- **Desktop** — a Tauri 2.0 shell in `desktop/` that sidecar-launches the backend JAR (release
  builds only), waits for health, and injects the auth token into the webview.
- **Plugins** — isolated **`.fyp`** packages: a `manifest.json` + a sandboxed iframe UI (talking to
  the host over the `@infinia/plugin-sdk` `postMessage` bridge) + an out-of-process worker that
  speaks newline-delimited **JSON-RPC 2.0** over stdio. A worker crash can never take down the host.

## Maven reactor

Root `pom.xml` is the parent (`FengYu-parent`, version via the `${revision}` property). Modules,
in build order:

| Module | Role |
|---|---|
| `FengYu-Api` | Plugin + AI contracts (manifest schema, worker JSON-RPC protocol, `AiTool`). Other modules depend on it. |
| `FengYu-Plugin-Sdk` | **Independently versioned** Java Worker SDK (`fan.summer.fengyu.sdk:fengyu-plugin-sdk`). Does not inherit the parent; kept in the reactor only so local installs work. |
| `OfficialPlugins` | Aggregator for official plugins (`plugin-markdown`, `plugin-excel`, `plugin-email`, `plugin-offlinepython`). |
| `FengYu` | The headless Spring Boot app; shaded fat JAR, main class `fan.summer.fengyu.HeadlessLauncher`. |

Non-Maven top-level directories: `frontend/` (Vue), `desktop/` (Tauri), plus the plugin toolchain
(`FengYu-Plugin-Sdk/`, `plugin-sdk/typescript/`, `plugin-ui/vue/`, `plugin-cli/`, `plugin-spec/`).

## Two version lines (do not conflate)

- **App version** — the Maven `${revision}` property (mirrored in `frontend/package.json`,
  `desktop/src-tauri/Cargo.toml` + `tauri.conf.json`, and each official plugin's `manifest.json`).
- **Plugin toolchain version** — independent of the app; lives in `FengYu-Plugin-Sdk/pom.xml` and
  the three `@infinia/*` `package.json` files. Releasing the toolchain must never bump the app
  version, and vice-versa.

When a version number matters, **read it from its source file**; do not copy a literal here.

## Source is authoritative

When prose guidance conflicts with the repository, **the repository wins**. Inspect the actual
file rather than trusting a summary:

- Plugin runtime contract → `plugin-spec/manifest.schema.json`, a plugin's `manifest.json` and
  `fengyu.plugin.json`, and `FengYu/src/main/java/fan/summer/fengyu/plugin/` (market + runtime).
- REST/SSE surface → controllers under `FengYu/src/main/java/fan/summer/fengyu/web/controller/`.
- Build/release contracts → `pom.xml`, `package.json` files, `scripts/`, `.github/workflows/`.
- Module docs → focused pages under `docs/en/` and `docs/zh/` (structurally mirrored).

## Build, run, verify

Use the repository wrappers and package scripts that already exist. Run the exact commands from
`README.md` ("Quick Start"); in summary:

```bash
# Backend (API must be installed first; standalone POMs, no parent inheritance)
mvn install -f FengYu-Api/pom.xml -DskipTests
mvn clean package -f FengYu/pom.xml -DskipTests
java -jar FengYu/target/FengYu-*.jar --token=<t>     # loopback, port 24056 by default

# Frontend (dev)
cd frontend && npm install && npm run dev            # Vite proxies /api + /plugin-runtime → :24056

# Desktop (dev)
cd desktop && cargo tauri dev

# End-to-end smoke (boots the JAR, probes every endpoint)
scripts/e2e-smoke.sh

# Docs site (VitePress, EN + ZH)
npm run docs:build
```

Prefer `./mvnw` over a system Maven when running from a shell.

## Working rules

- **Preserve user work.** Do not delete or rewrite files outside the requested change.
- **Focused verification.** Run the smallest check that proves the change (the relevant module
  build, the relevant `npm` script, `scripts/e2e-smoke.sh`, or `git diff --check`). Do not run the
  whole reactor "just in case."
- **No unrelated rewrites.** Match surrounding style, naming, and comment density.
- **Commit convention:** conventional commits with emojis — `✨` feat, `🐛` fix, `♻️` refactor,
  `📝` docs, `⬆️` deps, `🔥` removal. Commit, push, tag, or publish only when the user asks.

## Legacy that does NOT describe 4.0.0

The following are historical and must not be generated or recommended. (They appear in old plans,
deleted skills, and the still-JavaFX `FengYu-Api` preview classes, but they are not the running app.)

- **JavaFX** UI, `createView()`, `StepWizard`, `-sk-*`/`.glass-*` CSS tokens, scene/Stage code.
- **`FengYuPluginV2`** and the in-process Spring `@Component` plugin bean model.
- **Java `ServiceLoader` / `META-INF/services/fan.summer.api.FengYuPlugin`** SPI registration.
- **In-process plugins** sharing the host classpath or host Spring/JPA context.
- Host-provided worker dependencies — workers are out-of-process and bring their own classpath.
- The legacy product codenames **SwissKitJ / ZhiFlow / fengyuj** — they describe earlier versions
  and must not be regenerated or recommended as current.

## Skills

Workflow skills live in `.agents/skills/` and are the canonical procedures for their domain:

| Skill | When to use |
|---|---|
| `fengyu-plugin-dev` | Scaffold, develop, validate, build, package, or install a plugin (official or third-party). |
| `docs-updater` | Sync `README.md`, `CHANGELOG.md`, `docs/en/`, `docs/zh/` after a code or release change. |
| `app-release` | Cut a main application release (`vX.Y.Z[-{alpha|beta|rc}.N]`). |
| `plugin-tooling-release` | Cut an independently versioned plugin-toolchain release (`plugin-tooling-vX.Y.Z`). |

Claude Code reaches the same skills through short adapters in `.claude/skills/`.
