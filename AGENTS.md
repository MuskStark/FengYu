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
- **Desktop** — an Electron 43.x shell in `desktop/electron/` that sidecar-launches the backend JAR
  (release builds only), waits for health, exposes the auth token + api-base to the renderer via a
  `contextBridge` preload, and owns the window, system tray, logger, and auto-updater.
- **Plugins** — isolated **`.fyp`** packages: a `manifest.json` + a sandboxed iframe UI (talking to
  the host over the `@infinia/plugin-sdk` `postMessage` bridge) + an out-of-process worker that
  speaks newline-delimited **JSON-RPC 2.0** over stdio. A worker crash can never take down the host.

## Maven reactor

Root `pom.xml` is the parent (`FengYu-parent`, version via the `${revision}` property). Modules,
in build order:

| Module | Role |
|---|---|
| `toolchain/sdk-java` | **Independently versioned** Java Worker SDK (`fan.summer.fengyu.sdk:fengyu-plugin-sdk`). Does not inherit the parent; kept in the reactor only so local installs work. |
| `toolchain/devkit-java` | **Independently versioned** Java dev kit (`fan.summer.fengyu.sdk:fengyu-plugin-devkit`) — the in-IDE loopback dev server. Same versioning rules as `sdk-java`. |
| `OfficialPlugins` | Aggregator for official plugins (`plugin-markdown`, `plugin-excel`, `plugin-email`, `plugin-offlinepython`). Browser automation is a host-embedded backend capability (`BrowserTool`), not a plugin. |
| `FengYu` | The headless Spring Boot app; shaded fat JAR, main class `fan.summer.fengyu.HeadlessLauncher`. |

Non-Maven top-level directories: `frontend/` (Vue), `desktop/` (Electron), plus the plugin toolchain
(`toolchain/sdk-ts/`, `toolchain/ui/`, `toolchain/dev/`, `toolchain/cli/`, `toolchain/spec/`).

## Two version lines (do not conflate)

- **App version** — the Maven `${revision}` property (mirrored in `frontend/package.json`,
  `desktop/electron/package.json`, and each official plugin's `manifest.json`).
- **Plugin toolchain version** — independent of the app; lives in `toolchain/sdk-java/pom.xml`,
  `toolchain/devkit-java/pom.xml`, and the four `@infinia/*` `package.json` files
  (`plugin-sdk`, `plugin-ui`, `plugin-dev`, `plugin-cli`). Releasing the toolchain must never bump
  the app version, and vice-versa.

When a version number matters, **read it from its source file**; do not copy a literal here.

## Source is authoritative

When prose guidance conflicts with the repository, **the repository wins**. Inspect the actual
file rather than trusting a summary:

- Plugin runtime contract → `toolchain/spec/manifest.schema.json`, a plugin's `manifest.json`, its
  conventional `ui-src/package.json` + optional Maven Worker, and
  `FengYu/src/main/java/fan/summer/fengyu/plugin/` (market + runtime).
- Skill runtime contract → `FengYu/src/main/java/fan/summer/fengyu/ai/skill/` (discovery,
  registry, progressive-disclosure `skill` tool) and built-in skill bodies under
  `FengYu/src/main/resources/skills/`. Skills are a **peer extension surface to plugins**, not
  a plugin feature — they never touch `toolchain/spec/` or a plugin manifest. User skills live at
  `~/.fengyu/skills/<id>/SKILL.md`.
- REST/SSE surface → controllers under `FengYu/src/main/java/fan/summer/fengyu/web/controller/`.
- Build/release contracts → `pom.xml`, `package.json` files, `scripts/`, `.github/workflows/`.
- Module docs → focused pages under `docs/en/` and `docs/zh/` (structurally mirrored).

## Build, run, verify

Use the repository wrappers and package scripts that already exist. Run the exact commands from
`README.md` ("Quick Start"); in summary:

```bash
# Backend
./mvnw clean package -f FengYu/pom.xml -DskipTests
java -jar FengYu/target/FengYu-*.jar --token=<t>     # loopback, port 24056 by default

# Frontend (dev)
cd frontend && npm install && npm run dev            # Vite proxies /api + /plugin-runtime → :24056

# Desktop (dev)
cd desktop/electron && npm install && npm run dev   # DEFAULT: connects to an IDE-started backend at
                                                    #   http://127.0.0.1:24056 (start it without --token → auth disabled)
                                                    # To spawn its own backend: FENGYU_JAR=<built shaded jar> or FENGYU_DEV_BACKEND=disabled

# End-to-end smoke (boots the JAR, probes every endpoint)
scripts/e2e-smoke.sh

# Docs site (VitePress, EN + ZH)
npm --prefix docs run build
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

### Pitfalls confirmed by 4.0.0-beta.1

These cost real release cycles; do not repeat them.

- **Desktop E2E (`desktop/electron/test/e2e/*.spec.ts`) runs in CI and is gating on macOS + Linux.**
  `launch.spec.ts` is the stable one. `browser-bridge.spec.ts` is **opt-in**
  (`FENGYU_E2E_BROWSER_BRIDGE=1`) because it opens a real `BrowserWindow`, navigates a live URL,
  and runs CDP capture — fragile under xvfb/sandboxed runners, and a teardown timeout there also
  kills `launch.spec.ts` in the same Playwright worker. The release workflow does NOT set the
  opt-in env var, so only `launch.spec.ts` runs there. When adding a new desktop E2E, either keep
  it gating-stable or gate it behind its own opt-in env var — never let an unstable spec take down
  the stable one.
- **A Playwright Electron `app.evaluate` that reads `process.env.*` set during main-process init
  races that init.** Wait for the main window's `domcontentloaded` first (skip splash + devtools
  windows, as `launch.spec.ts` does) — that guarantees `main.ts` async init (including
  `startBrowserBridge()`, which sets `FENGYU_BROWSER_BRIDGE_PORT/TOKEN` before the JVM spawn) has
  completed. Reading earlier yields `undefined` or "Execution context was destroyed".
- **The local `scripts/e2e-smoke.sh` does NOT cover the Electron launch chain.** It boots the bare
  JAR and probes REST endpoints; it cannot catch desktop-shell regressions (bridge startup, window
  lifecycle, preload bridge). A green e2e-smoke is necessary but NOT sufficient before a desktop
  release — the CI desktop E2E is the real gate.
- **When you change `desktop/electron/electron-builder.yml`, update
  `scripts/release-workflow.test.mjs` in the same change.** That contract test pins the packaging
  config (targets, artifact names, sidecars). A config edit with a stale test passes locally but
  fails the release job. Run `node --test scripts/release-workflow.test.mjs` after editing the
  builder config. (Example: the auto-updater change added a macOS `zip` target + comments; the test
  regex needed to allow comment lines and assert the new target.)
- **`scripts/release-workflow.test.mjs` and the `app-release` SKILL.md still mention "five" official
  plugins / `browser`; there are four** (`markdown`, `excel`, `email`, `offlinepython`). Browser
  automation is a host-embedded `BrowserTool`, not a plugin. Trust `OfficialPlugins/` and
  `package-web-release.sh`'s `OFFICIAL_PLUGINS=(markdown excel email offlinepython)` over that
  stale count when verifying version consistency.
- **Reissuing an existing prerelease tag (e.g. re-running beta.1) is a force-tag**
  (`git tag -f <tag> HEAD` then `git push origin <tag> --force`). It re-triggers
  `fengyu-release.yml`, which overwrites the GitHub Release's assets. Only do this for prereleases
  not yet promoted to a real audience; otherwise cut the next prerelease (e.g. beta.2).
- **The docs changelog mirrors are generated, not hand-edited.** After editing root `CHANGELOG.md`,
  run `npm --prefix docs run sync:changelog` to regenerate `docs/{en,zh}/reference/changelog.md`.
  That script is also a pre-hook for docs dev/build/preview, but run it explicitly in a release so
  the mirrors land in the same commit as the CHANGELOG change.

## Legacy that does NOT describe 4.0.0

The following are historical and must not be generated or recommended. (They appear in old plans,
deleted skills and historical preview classes, but they are not the running app.)

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
| `toolchain-release` | Cut an independently versioned plugin-toolchain release (`plugin-tooling-vX.Y.Z`). |

Claude Code reaches the same skills through short adapters in `.claude/skills/`.
