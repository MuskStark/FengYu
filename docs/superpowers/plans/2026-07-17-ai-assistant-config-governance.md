# AI Assistant Configuration Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the repo's duplicated, drifted AI-assistant config with ten canonical/adapter files that reflect the FengYu 4.0.0 headless architecture, supporting both Codex-style assistants and Claude Code from one source of truth.

**Architecture:** `AGENTS.md` is the single canonical repository-guidance file. Four canonical skills live under `.agents/skills/<name>/SKILL.md`. `CLAUDE.md` and four `.claude/skills/<name>/SKILL.md` files are thin adapters that point Claude Code at the canonical file — they never duplicate it. Everything else under `.agents/`, `.claude/`, `.claude-plugin/`, plus the old `AGENTS.md`/`CLAUDE.md`, is deleted first.

**Tech Stack:** Markdown only. No code, no new scripts, no build tooling. Verification is repository search + `git diff --check` (the spec explicitly forbids adding a new validation/sync script).

## Global Constraints

Copied verbatim from the approved spec (`docs/superpowers/specs/2026-07-17-ai-assistant-config-governance-design.md`); every task implicitly includes these:

- Keep assistant configuration in **English** for consistent parsing across supported tools.
- **Do not add** custom agents, command copies, assistant plugins, marketplace metadata, or generated standards snapshots.
- Prefer **links to authoritative repository files** over copied schemas, inventories, or templates.
- The rebuilt guidance must contain **no affirmative** legacy JavaFX, `FengYuPluginV2`, SPI, SwissKitJ, or ZhiFlow instructions. **Explicit legacy prohibitions are allowed** (e.g. "do not generate JavaFX views").
- **Never** make `.claude/` a second source of truth.
- **No rebuilt file may reference** `.claude-plugin/` or `scripts/sync-plugin-standards.sh`.
- Do not delete or rewrite `docs/superpowers/`, `.superpowers/`, `final-branch-review`, product docs under `docs/en/` and `docs/zh/`, application source, build output, or ordinary scripts (including `scripts/sync-plugin-standards.sh` — it becomes an orphaned historical script and must not be referenced).
- Commits follow the repo's conventional-commit convention with emojis (e.g. `📝`, `♻️`, `🔥` for removals).

**Authoritative facts used to write content** (verified from the live repo, 2026-07-17 — do not re-derive, but if a cited path no longer exists, re-verify before writing):

- Maven reactor modules (root `pom.xml`): `FengYu-Api`, `FengYu-Plugin-Sdk` (independently versioned, no parent), `OfficialPlugins` (`plugin-markdown`, `plugin-excel`, `plugin-email`, `plugin-offlinepython`), `FengYu`. Version = `${revision}` property. Wrapper: `./mvnw`.
- Backend entry: `fan.summer.fengyu.HeadlessLauncher`; loopback (`127.0.0.1`), default port `24056`, args `--port=<n>` / `--token=<t>`. SETUP/APP two-mode boot.
- Frontend: `frontend/` Vue 3.5.39 + TS + Pinia + vue-router 4 + vue-i18n 10 + Vuetify 3 (MD3). Scripts: `npm run dev|build|test|test:unit|typecheck`.
- Desktop: `desktop/` Tauri 2.0, sidecar-launches the JAR (release build only), injects `window.__FENGYU_TOKEN__`/`__FENGYU_PORT__`/`__FENGYU_API_BASE__`.
- Plugin model: `.fyp` zip = `manifest.json` + `ui/` + `backend/worker.jar`. UI = sandboxed iframe + `@infinia/plugin-sdk` `postMessage` bridge. Backend = out-of-process worker, newline-delimited JSON-RPC 2.0 over stdio. Schema: `plugin-spec/manifest.schema.json`. Worker SDK: `FengYu-Plugin-Sdk/`. TS SDK: `plugin-sdk/typescript/`. UI kit: `plugin-ui/vue/`. CLI: `plugin-cli/` (`fengyu plugin <create|dev|build|validate|install>`).
- Two version lines: app (`${revision}`, mirrors in frontend/package.json, desktop Cargo/tauri, official-plugin manifests) and plugin toolchain (`1.0.0` in FengYu-Plugin-Sdk/pom.xml + the three `@infinia/*` package.jsons).
- App release resolver: `scripts/resolve-release-version.mjs` (tags `vX.Y.Z`, `vX.Y.Z-{alpha|beta|rc}.N`). App release workflow: `.github/workflows/fengyu-release.yml`.
- Plugin-tooling release resolver: `plugin-cli/scripts/resolve-tooling-version.mjs` (tags `plugin-tooling-vX.Y.Z`). Workflow: `.github/workflows/plugin-tooling-release.yml`.
- Smoke: `scripts/e2e-smoke.sh`. Docs build: `npm run docs:build` (root `package.json`, VitePress, EN+ZH under `docs/en/` `docs/zh/`).
- `scripts/sync-plugin-standards.sh` is **broken/orphaned** (references pre-rename `SwissKitJ-Api`); never cite it.

---

## File Structure

Final intended surface (exactly 10 files):

```text
AGENTS.md                                    # canonical repo guidance (Codex + others)
CLAUDE.md                                    # adapter → AGENTS.md
.agents/skills/
  fengyu-plugin-dev/SKILL.md                 # canonical: plugin scaffold→install
  docs-updater/SKILL.md                      # canonical: diff-driven docs sync
  app-release/SKILL.md                       # canonical: main app release
  plugin-tooling-release/SKILL.md            # canonical: plugin toolchain release
.claude/skills/
  fengyu-plugin-dev/SKILL.md                 # adapter → .agents canonical
  docs-updater/SKILL.md                      # adapter → .agents canonical
  app-release/SKILL.md                       # adapter → .agents canonical
  plugin-tooling-release/SKILL.md            # adapter → .agents canonical
```

Deleted before recreation: `AGENTS.md`, `CLAUDE.md`, `.agents/` (entire tree incl. `create-builtin-tool/`), `.claude/` (entire tree incl. `agents/`, `commands/`, `skills/release.md`), `.claude-plugin/` (entire tree).

---

## Task 1: Delete the old assistant configuration

**Files:**
- Delete: `AGENTS.md`
- Delete: `CLAUDE.md`
- Delete: `.agents/` (entire directory — incl. `skills/create-builtin-tool/`, `skills/docs-updater/`, `skills/fengyu-plugin-dev/` with all references/assets/templates)
- Delete: `.claude/` (entire directory — incl. `agents/`, `commands/`, `skills/`, `skills/release.md`)
- Delete: `.claude-plugin/` (entire directory — marketplace.json, plugin/, agents, scripts, skills, standards, templates)

**Interfaces:**
- Consumes: nothing (this is the teardown step).
- Produces: a clean slate so Tasks 2–7 recreate exactly the ten target files.

- [ ] **Step 1: Confirm the deletion targets exist and nothing out of scope is present**

Run from repo root:
```bash
ls -d AGENTS.md CLAUDE.md .agents .claude .claude-plugin 2>&1
```
Expected: all five paths listed (no "No such file or directory").

- [ ] **Step 2: Delete the five targets with git**

```bash
git rm -r AGENTS.md CLAUDE.md .agents .claude .claude-plugin
```
Expected: staged deletions for every file under those five paths.

- [ ] **Step 3: Confirm out-of-scope trees are untouched**

Run:
```bash
ls -d docs/superpowers .superpowers scripts/sync-plugin-standards.sh docs/en docs/zh final-branch-review 2>&1
```
Expected: all present (`final-branch-review` may be absent if already removed historically — if so, that is fine; do not create it).

- [ ] **Step 4: Commit the deletion**

```bash
git commit -m "🔥 chore(agents): remove legacy AI-assistant config

Delete AGENTS.md, CLAUDE.md, .agents/, .claude/, and .claude-plugin/
ahead of rebuilding a single canonical configuration that reflects the
4.0.0 headless architecture. Per ai-assistant-config-governance design."
```

---

## Task 2: Create canonical `AGENTS.md`

**Files:**
- Create: `AGENTS.md`

**Interfaces:**
- Consumes: Task 1 (paths are clear).
- Produces: the canonical guidance that `CLAUDE.md` (Task 7) points at, and the reference target named by every canonical skill (Tasks 3–6).

**Content rules (from spec):** only cross-task guidance; no version inventories, no endpoint lists, no copied schemas. Current source/schemas/scripts/workflows are authoritative. Explicit legacy prohibitions allowed.

- [ ] **Step 1: Write `AGENTS.md` with the content below**

Create `AGENTS.md`:

````markdown
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
- The product codenames **SwissKitJ / ZhiFlow / fengyuj** and any `sync-plugin-standards.sh` /
  `.claude-plugin/` workflow. `scripts/sync-plugin-standards.sh` is an orphaned, broken script;
  do not reference or revive it.

## Skills

Workflow skills live in `.agents/skills/` and are the canonical procedures for their domain:

| Skill | When to use |
|---|---|
| `fengyu-plugin-dev` | Scaffold, develop, validate, build, package, or install a plugin (official or third-party). |
| `docs-updater` | Sync `README.md`, `CHANGELOG.md`, `docs/en/`, `docs/zh/` after a code or release change. |
| `app-release` | Cut a main application release (`vX.Y.Z[-{alpha|beta|rc}.N]`). |
| `plugin-tooling-release` | Cut an independently versioned plugin-toolchain release (`plugin-tooling-vX.Y.Z`). |

Claude Code reaches the same skills through short adapters in `.claude/skills/`.
````

- [ ] **Step 2: Verify the file is clean and references no forbidden paths**

Run:
```bash
test -f AGENTS.md && echo OK
grep -n -E '\.claude-plugin/|sync-plugin-standards' AGENTS.md || echo "no forbidden refs"
grep -n -E 'createView|FengYuPluginV2|ServiceLoader' AGENTS.md
```
Expected: `OK`; `no forbidden refs`; the last grep returns **only** the explicit-prohibition lines
(they are allowed because they say these are legacy/must-not — confirm each hit is prohibitive, not
instructive).

- [ ] **Step 3: Commit**

```bash
git add AGENTS.md
git commit -m "📝 docs(agents): add canonical AGENTS.md for 4.0.0 headless architecture"
```

---

## Task 3: Create canonical skill `.agents/skills/fengyu-plugin-dev/SKILL.md`

**Files:**
- Create: `.agents/skills/fengyu-plugin-dev/SKILL.md`

**Interfaces:**
- Consumes: the plugin toolchain paths (`plugin-spec/manifest.schema.json`, `plugin-cli/`,
  `plugin-sdk/typescript/`, `plugin-ui/vue/`, `FengYu-Plugin-Sdk/`) and the official plugins under
  `OfficialPlugins/`. CLI contract: `fengyu plugin <create|dev|build|validate|install> [path] [options]`,
  where `create` requires `--id <id>` (optional `--ui-only`, `--no-install`), `build` takes optional
  `--out` / `--skip-tests`, `dev` takes optional `--port`, `install` takes optional `--host` / `--token`.
- Produces: the canonical skill that `.claude/skills/fengyu-plugin-dev/SKILL.md` (Task 7) points at.

**Content rules (from spec §"fengyu-plugin-dev"):** inspect the target plugin; route by UI-only vs
Java Worker; authoritative inputs are the listed paths + `docs/{en,zh}/plugins/`; cover scaffold →
install via the CLI; enforce iframe isolation, SDK bridge, Worker JSON-RPC, manifest permissions,
packaging boundaries, focused UI/Worker verification; must NOT generate JavaFX / `FengYuPluginV2` /
SPI / in-process beans / host-provided worker deps.

- [ ] **Step 1: Write the canonical skill with the content below**

Create `.agents/skills/fengyu-plugin-dev/SKILL.md`:

````markdown
---
name: fengyu-plugin-dev
description: Build, scaffold, develop, validate, build, package, and install FengYu plugins (official or third-party) against the 4.0.0 .fyp + iframe + JSON-RPC Worker model. Use whenever the user wants to create or work on a plugin, mentions `.fyp`, `manifest.json`, `fengyu.plugin.json`, the plugin CLI (`fengyu plugin ...`), `@infinia/plugin-sdk`, `@infinia/plugin-ui`, `FengYu-Plugin-Sdk`, plugin workers, or the plugin marketplace.
---

# FengYu Plugin Development

End-to-end workflow for authoring FengYu plugins against the **4.0.0** model: `.fyp` packages
containing a sandboxed iframe UI and an out-of-process JSON-RPC worker. Covers official plugins
(in this repo under `OfficialPlugins/`) and third-party plugins (scaffolded elsewhere).

## Step 0 — Load authoritative inputs BEFORE acting

Do not write plugin code from memory. Read the current contract first:

- `plugin-spec/manifest.schema.json` — the canonical manifest JSON schema (required fields,
  permission enum, `aiTools` shape).
- The target plugin's `manifest.json` (runtime contract) and `fengyu.plugin.json` (toolchain /
  build contract: `ui.{root,output,prepare,install,test,build}`, `worker.{root,test,build,artifact,mainClass}`, `package.outputDirectory`).
- `FengYu-Plugin-Sdk/` — the Java Worker SDK (`JsonRpcWorker`, `PluginHandler`, `PluginEnvironment`,
  `FileRef`).
- `plugin-sdk/typescript/` — `@infinia/plugin-sdk`, the browser `postMessage` bridge the iframe UI uses.
- `plugin-ui/vue/` — `@infinia/plugin-ui`, the Vue/Vuetify component kit for plugin UIs.
- `plugin-cli/` — the `fengyu` CLI source (`src/cli.mjs`, `src/args.mjs`) — the real subcommand set.
- A reference official plugin, e.g. `OfficialPlugins/plugin-markdown/` (UI-only-style) or
  `OfficialPlugins/plugin-excel/` (Vue UI + Java worker).
- Current plugin docs: `docs/en/plugins/` and `docs/zh/plugins/` (especially `overview.md`,
  `manifest.md`, `worker.md`, `ui-microfrontend.md`, `build-deploy.md`, `sdk-cli.md`,
  `ai-tools.md`, `database.md`, `file-io.md`, `pitfalls.md`).

If any of these disagree with this skill, **the repo wins** — follow the file.

## Step 1 — Classify the plugin

Every plugin is one of two shapes. The workflow diverges here:

| Shape | Has `backend`? | Worker | Reference |
|---|---|---|---|
| **UI-only** | `manifest.json` has no `backend` (or no worker) | none | Markdown-style: UI calls host/SDK only |
| **UI + Java Worker** | `manifest.json` `backend.protocol == "json-rpc-2.0"` | a shaded worker JAR | Excel-style: UI ↔ host ↔ out-of-process worker |

Read the target plugin's `manifest.json` and decide. Everything below branches on this.

## Step 2 — Scaffold (third-party) or locate (official)

**Third-party:** use the CLI to scaffold, never hand-roll:

```bash
# UI + Java worker (default)
fengyu plugin create my-plugin --id com.example.my-plugin
# UI-only (no backend worker)
fengyu plugin create my-plugin --id com.example.my-plugin --ui-only
# Skip auto-installing SDK/UI deps during scaffold
fengyu plugin create my-plugin --id com.example.my-plugin --no-install
```

`--id` is required and must match the manifest id pattern `^[a-z0-9]+(?:[.-][a-z0-9]+)+$`. Official
plugin ids start with `fan.summer.`.

**Official (in this repo):** the plugin already lives under `OfficialPlugins/<name>/` with
`manifest.json`, `fengyu.plugin.json`, `pom.xml` (if it has a worker), `src/`, `ui-src/`, and a
`dist-package/` output dir. Do not re-scaffold; edit in place.

## Step 3 — Develop

**UI (always):** a Vue micro-frontend built from `ui-src/`. It runs inside a **sandboxed iframe**
served by the host under a strict Content-Security-Policy (`connect-src 'none'` — the UI cannot call
out directly). To reach the backend or host capabilities, the UI uses `@infinia/plugin-sdk` to
`postMessage` to the host shell, which proxies via the plugin-runtime invoke path. The UI **never**
sees absolute filesystem paths — file access flows through opaque file-reference objects mediated by
the host.

**Worker (UI + Java Worker only):** a Java `main()` that links `FengYu-Plugin-Sdk` and speaks
newline-delimited JSON-RPC 2.0 over stdin/stdout (one request object per line, responses matched by
`id`). The worker runs in **its own process** with its own classpath; it must not assume any
host-provided dependency beyond the SDK. The host sets env vars `FENGYU_PLUGIN_ID`,
`FENGYU_PLUGIN_ROOT`, and (for plugins with the `database` permission) an injected datasource.

**Dev loop:** run the headless backend, then use the CLI dev server for hot UI work:

```bash
fengyu plugin dev ./my-plugin              # optional --port <n> (default 4173)
```

## Step 4 — Declare permissions and (optional) AI tools

Edit `manifest.json`:

- `permissions[]` — request the **minimum** set from the allowed enum only: `files.read`,
  `files.write`, `network`, `network.email`, `clipboard.read`, `clipboard.write`, `notifications`,
  `database`. The host enforces these; do not attempt capability access you did not declare.
- `aiTools[]` (optional) — each entry needs `name`, `description`, `method` (the JSON-RPC method
  the worker implements), and `inputSchema`. Keep `description` short and model-readable; it is
  what the AI primarily reads.

Confirm the manifest validates against `plugin-spec/manifest.schema.json` and matches the runtime
loader's rules in `FengYu/src/main/java/fan/summer/fengyu/plugin/market/` (`.fyp` only,
`schemaVersion == 1`, semver `version`, official ids start `fan.summer.`).

## Step 5 — Validate, build, package, install

All through the CLI — do not hand-zip:

```bash
fengyu plugin validate ./my-plugin         # manifest + layout checks
fengyu plugin build ./my-plugin            # → .fyp in the configured package.outputDirectory
fengyu plugin build ./my-plugin --out dist/x.fyp --skip-tests
fengyu plugin install ./my-plugin          # optional --host http://127.0.0.1:24056 --token <t>
```

For official plugins, prefer the repo's Maven-driven worker build declared in `fengyu.plugin.json`
(`worker.build` runs `mvn ... -pl OfficialPlugins/<name> -am package -DskipTests`) so the worker JAR
is fresh before the CLI packages the `.fyp`.

## Step 6 — Focused verification

- **UI-only:** `cd ui-src && npm install && npm test` (and `npm run build`) — verify the UI builds
  and its unit/visual tests pass.
- **UI + Java Worker:** also build/test the worker (`mvn -f OfficialPlugins/<name>/pom.xml test` or
  the `fengyu.plugin.json` `worker.test` command) and confirm the worker's JSON-RPC methods round-trip.
- **End to end:** with the backend running, `fengyu plugin install` the built `.fyp`, open it in the
  UI, and exercise the documented methods. Optionally run `scripts/e2e-smoke.sh` to confirm the host
  is healthy.

## Hard prohibitions (these describe legacy versions, not 4.0.0)

Do **not** generate or recommend any of:

- JavaFX views, `createView()`, `StepWizard`, `-sk-*` / `.glass-*` CSS, or any `javafx.*` code.
- `FengYuPluginV2` / `FengYuPlugin` Java interface implementations, or in-process Spring `@Component`
  plugin beans.
- `META-INF/services/...` Java `ServiceLoader` SPI registration files.
- Any assumption that the worker shares the host classpath, host Spring context, or host JPA session.
  Workers are out-of-process; bring every dependency in the worker's own shaded JAR.
- Direct `fetch`/`connect-src` from the iframe UI — route through the `@infinia/plugin-sdk`
  `postMessage` bridge instead.
````

- [ ] **Step 2: Verify frontmatter + no legacy instructions**

Run:
```bash
head -4 .agents/skills/fengyu-plugin-dev/SKILL.md
grep -n -E 'FengYuPluginV2|ServiceLoader|createView|JavaFX' .agents/skills/fengyu-plugin-dev/SKILL.md
grep -n -E '\.claude-plugin/|sync-plugin-standards' .agents/skills/fengyu-plugin-dev/SKILL.md || echo "no forbidden refs"
```
Expected: valid `---` frontmatter with `name:` and `description:`; the legacy grep hits appear
**only** in the "Hard prohibitions" section (prohibitive, not instructive); `no forbidden refs`.

- [ ] **Step 3: Commit**

```bash
git add .agents/skills/fengyu-plugin-dev/SKILL.md
git commit -m "📝 docs(agents): add canonical fengyu-plugin-dev skill (.fyp + JSON-RPC model)"
```

---

## Task 4: Create canonical skill `.agents/skills/docs-updater/SKILL.md`

**Files:**
- Create: `.agents/skills/docs-updater/SKILL.md`

**Interfaces:**
- Consumes: `README.md`, `CHANGELOG.md`, `docs/en/`, `docs/zh/`, `git log`/`git diff`, the two
  version sources (app `${revision}`; plugin toolchain in `FengYu-Plugin-Sdk/pom.xml` + `@infinia/*`
  package.jsons), and `npm run docs:build` (root `package.json`).
- Produces: the canonical skill that `.claude/skills/docs-updater/SKILL.md` (Task 7) points at, and
  that the `app-release` skill (Task 5) invokes.

**Content rules (from spec §"docs-updater"):** diff-driven, not editorial; determine the comparison
range from release type + existing tags; map implementation changes to doc sections; keep EN/ZH
structurally aligned; preserve historical changelog; treat app and plugin-tooling as separate version
lines; no broad rewrites without a code/release change; skip `docs/superpowers/` and generated
VitePress output; validate via stale-reference search, link/path checks where practical, and
`npm run docs:build` for doc changes that affect the published site.

- [ ] **Step 1: Write the canonical skill with the content below**

Create `.agents/skills/docs-updater/SKILL.md`:

````markdown
---
name: docs-updater
description: Update README.md, CHANGELOG.md, docs/en/, and docs/zh/ after a code or release change. Diff-driven — maps concrete git changes to the documentation sections they affect, keeps English and Chinese structurally aligned, and treats the app and plugin-tooling versions as separate lines. Use whenever the user asks to update docs, sync documentation to code, or bump/sync versions after a release.
---

# Docs Updater

Sync repository documentation to concrete code and release changes. **Diff-driven, not editorial:**
every edit traces to a specific commit or release mutation. Do not review docs for general accuracy
or "improve" prose without a corresponding change.

## Scope

In scope: `README.md`, `CHANGELOG.md`, `docs/en/`, `docs/zh/`.

Out of scope (never touch here):

- `docs/superpowers/` — date-keyed planning artifacts.
- Generated VitePress output (`docs/.vitepress/dist/`).
- Application source and build output.

## Two version lines — do not conflate

- **App version** — Maven `${revision}` (root `pom.xml`), mirrored in `frontend/package.json`,
  `desktop/src-tauri/Cargo.toml` + `tauri.conf.json`, and each official plugin's `manifest.json`.
- **Plugin toolchain version** — independent; `FengYu-Plugin-Sdk/pom.xml` and the three
  `@infinia/*` `package.json` files (`plugin-cli/`, `plugin-sdk/typescript/`, `plugin-ui/vue/`).

Always read the literal from its source file before replacing it anywhere. An app release does not
touch the toolchain version, and vice-versa.

## Step 1 — Determine the comparison range

Pick the range from the release type:

- **App release** — compare from the latest app tag (`git tag --sort=-v:refname | grep -E '^v[0-9]'`)
  to `HEAD`.
- **Plugin-tooling release** — compare from the latest `plugin-tooling-v*` tag to `HEAD`.
- **Non-release doc sync** — compare from the last release tag (app or tooling, whichever is
  relevant to the changed files) to `HEAD`.

```bash
git log <previous-tag>..HEAD --oneline --no-decorate
git diff <previous-tag>..HEAD --stat
```

If the range is empty, skip content updates and only sync version numbers (Step 4).

## Step 2 — Map changes to doc sections

For each materially changed file, decide the **specific** doc section it maps to. Only edit a doc
section when a concrete change maps to it. Examples:

| Changed source | Affected doc |
|---|---|
| New official plugin module under `OfficialPlugins/` | `docs/{en,zh}/features.md` (or the plugin overview), `README.md` Features list, and a new `docs/{en,zh}/plugins/official-<name>.md` |
| `manifest.json` schema change in `plugin-spec/` | `docs/{en,zh}/plugins/manifest.md` |
| New REST/SSE controller method | `docs/{en,zh}/reference/rest-api.md` / `sse-events.md` |
| Headless boot / setup-wizard change in `FengYu/` | `docs/{en,zh}/architecture/backend.md`, `docs/{en,zh}/guide/database.md` |
| `fengyu` CLI subcommand change in `plugin-cli/` | `docs/{en,zh}/plugins/sdk-cli.md` |

If a changed file maps to no doc section, make no doc edit for it. Do not invent new patterns — copy
the formatting of the nearest existing entry.

## Step 3 — Update CHANGELOG and keep EN/ZH aligned

- Add a new section at the top of `CHANGELOG.md` (after the intro header) following the existing
  format. Dedup related commits into single bullets.
- Categorize by prefix: `feat`/`✨` → New Features; `fix`/`🐛` → Fixes; `refactor`/`♻️`,
  `deps`/`⬆️` → Changes; `docs`/`📝` → skip (already documentation).
- Keep `docs/en/` and `docs/zh/` **structurally aligned**: the same headings, the same section order,
  the same facts. Translate to 简体中文 for the `zh` tree; do not let one language drift ahead of the
  other. Preserve all historical changelog entries — never rewrite past releases.

## Step 4 — Replace version numbers (exact string matching)

Find stale references and swap with **exact string** replacement (never regex):

```bash
grep -r '<old-version>' docs/ README.md CHANGELOG.md --include='*.md' -l
```

Watch for: badge URLs, `/releases/tag/v<old>` links, JAR/`.fyp` filenames, inline bold versions. Be
careful not to change the **other** version line (e.g. do not bump the toolchain version when
syncing an app release). Skip matches inside historical CHANGELOG entries for past releases.

## Step 5 — Validate

- **Stale-reference search:** `grep -r '<old-version>' docs/ README.md 2>/dev/null` → expect empty
  (except historical changelog entries).
- **New-version presence:** `grep -r '<new-version>' docs/ README.md CHANGELOG.md` → expect it where
  intended.
- **Link/path checks where practical:** spot-check any changed internal links and file paths resolve.
- **Docs build (when the change affects the published site):** from the repo root,

  ```bash
  npm run docs:build      # VitePress; builds docs/ → docs/.vitepress/dist/
  ```

  Skip this for changes confined to `CHANGELOG.md` or `docs/superpowers/`.

## Output

Report concisely: old → new version (per line), files changed and what changed in each, and anything
skipped with the reason.
````

- [ ] **Step 2: Verify frontmatter + no forbidden refs**

Run:
```bash
head -4 .agents/skills/docs-updater/SKILL.md
grep -n -E '\.claude-plugin/|sync-plugin-standards' .agents/skills/docs-updater/SKILL.md || echo "no forbidden refs"
```
Expected: valid frontmatter; `no forbidden refs`.

- [ ] **Step 3: Commit**

```bash
git add .agents/skills/docs-updater/SKILL.md
git commit -m "📝 docs(agents): add canonical docs-updater skill (diff-driven, EN/ZH aligned)"
```

---

## Task 5: Create canonical skill `.agents/skills/app-release/SKILL.md`

**Files:**
- Create: `.agents/skills/app-release/SKILL.md`

**Interfaces:**
- Consumes: `scripts/resolve-release-version.mjs` (app tag contract), `.github/workflows/fengyu-release.yml`
  (release contract), the app version sources, `scripts/e2e-smoke.sh`, `scripts/package-web-release.sh`,
  `scripts/test-web-release.sh`, the `frontend/` and `desktop/` package scripts, and the `docs-updater`
  skill (Task 4).
- Produces: the canonical skill that `.claude/skills/app-release/SKILL.md` (Task 7) points at.

**Content rules (from spec §"app-release"):** main app releases only; tag contract `vX.Y.Z`,
`vX.Y.Z-{alpha|beta|rc}.N` enforced by `resolve-release-version.mjs`; verify version consistency where
source manifests require a base app version; invoke `docs-updater`; review the app release workflow and
its contract tests; run frontend/Maven/packaging/smoke verification before release mutation;
commit/tag/push needs explicit user confirmation; must NOT publish the independently versioned plugin
toolchain.

- [ ] **Step 1: Write the canonical skill with the content below**

Create `.agents/skills/app-release/SKILL.md`:

````markdown
---
name: app-release
description: Cut a main FengYu/Infinia application release (tag vX.Y.Z or vX.Y.Z-{alpha|beta|rc}.N). Validates the tag against scripts/resolve-release-version.mjs, checks version consistency across the app manifests, invokes docs-updater, reviews the app release workflow and its contract tests, and runs frontend/Maven/packaging/smoke verification. Use when the user asks to release, ship, tag, or cut a version of the main app. Does NOT publish the independently versioned plugin toolchain — use plugin-tooling-release for that.
---

# App Release

Cut a **main application** release. The app version is the Maven `${revision}` property, mirrored in
`frontend/package.json`, `desktop/src-tauri/Cargo.toml` + `tauri.conf.json`, and each official
plugin's `manifest.json`.

This skill does **not** touch the plugin toolchain version (`FengYu-Plugin-Sdk/pom.xml` /
`@infinia/*`). Use the `plugin-tooling-release` skill for that.

## Step 1 — Validate the release tag

The tag contract is enforced by `scripts/resolve-release-version.mjs`:

```
vX.Y.Z
vX.Y.Z-alpha.N
vX.Y.Z-beta.N
vX.Y.Z-rc.N
```

Resolve and confirm the four derived values (tag, version, appVersion, prerelease) before doing
anything:

```bash
node scripts/resolve-release-version.mjs vX.Y.Z-alpha.1   # prints tag/version/appVersion/prerelease
```

(When run outside Actions it errors on the missing `GITHUB_OUTPUT` file — that is expected; the
important check is that it does **not** throw `Invalid release tag`.) If the tag is invalid, stop and
agree a valid tag with the user.

## Step 2 — Check version consistency

Read the current app version from root `pom.xml` (`<revision>`) and confirm the source manifests that
carry a base app version are consistent with the intended release — notably each official plugin's
`manifest.json` `version` and the desktop Tauri/Cargo manifests. Decide with the user whether the
`${revision}` property (and its mirrors) should be bumped to the release version; do not edit yet.

## Step 3 — Update documentation

Invoke the **`docs-updater`** skill for the range from the last app tag to `HEAD`: CHANGELOG entry,
EN/ZH doc sections mapped to the code changes, and version-number replacement. Confirm
`npm run docs:build` passes if the published site is affected.

## Step 4 — Review the release workflow and its contract tests

Read `.github/workflows/fengyu-release.yml` and confirm the intended tag triggers it (tag push
`v[0-9]+.[0-9]+.[0-9]+` including `-alpha.N`/`-beta.N`/`-rc.N`, or `workflow_dispatch` with a `tag`
input). Run the release-version contract test locally:

```bash
node --test scripts/resolve-release-version.test.mjs
node --test scripts/release-workflow.test.mjs
```

All must pass before any release mutation.

## Step 5 — Run pre-release verification

Run the focused set the release depends on (do not skip to save time):

```bash
# Backend
mvn install -f FengYu-Api/pom.xml -DskipTests
mvn clean package -f FengYu/pom.xml -DskipTests

# Frontend (build + typecheck + tests)
cd frontend && npm install && npm run build && npm run test:unit && npm run typecheck && cd ..

# End-to-end smoke
scripts/e2e-smoke.sh

# Portable web distribution self-check (used by the release's web job)
scripts/package-web-release.sh && scripts/test-web-release.sh
```

(Desktop packaging is exercised by the workflow's matrix; you do not need to build all three
platforms locally.)

## Step 6 — Mutate, with explicit confirmation

**Committing, tagging, pushing a branch, or pushing a tag requires explicit user confirmation at
each step.** Do not run these automatically. When the user confirms:

1. Apply the version bump (`<revision>` and its mirrors) and the docs-updater changes; commit with a
   `📝`/`⬆️` conventional message.
2. Create the tag `vX.Y.Z[-{alpha|beta|rc}.N]`.
3. Push the branch and the tag — the tag push triggers `.github/workflows/fengyu-release.yml`, which
   builds runtime JARs, the portable web archives, the unsigned Tauri packages (Win/macOS/Linux), and
   publishes the GitHub release named "Infinia <version>".

**Never** publish the plugin toolchain (`@infinia/*`, `fengyu-plugin-sdk`) as part of an app release.
````

- [ ] **Step 2: Verify frontmatter + no forbidden refs + toolchain boundary**

Run:
```bash
head -4 .agents/skills/app-release/SKILL.md
grep -n -E '\.claude-plugin/|sync-plugin-standards' .agents/skills/app-release/SKILL.md || echo "no forbidden refs"
grep -n 'plugin-tooling-release' .agents/skills/app-release/SKILL.md
```
Expected: valid frontmatter; `no forbidden refs`; the toolchain skill is referenced (for redirection),
and the body states the app release must not publish the toolchain.

- [ ] **Step 3: Commit**

```bash
git add .agents/skills/app-release/SKILL.md
git commit -m "📝 docs(agents): add canonical app-release skill"
```

---

## Task 6: Create canonical skill `.agents/skills/plugin-tooling-release/SKILL.md`

**Files:**
- Create: `.agents/skills/plugin-tooling-release/SKILL.md`

**Interfaces:**
- Consumes: `plugin-cli/scripts/resolve-tooling-version.mjs` (tooling version resolver +
  `verifyRepositoryVersion`), `.github/workflows/plugin-tooling-release.yml` (release contract), the
  four toolchain version sources (`FengYu-Plugin-Sdk/pom.xml`, `plugin-cli/package.json`,
  `plugin-sdk/typescript/package.json`, `plugin-ui/vue/package.json`), `scripts/plugin-tooling-local-smoke.sh`,
  `scripts/check-plugin-dependency-boundaries.sh`, and the official plugin build path via the CLI.
- Produces: the canonical skill that `.claude/skills/plugin-tooling-release/SKILL.md` (Task 7) points at.

**Content rules (from spec §"plugin-tooling-release"):** handles the four independently versioned
toolchain artifacts; sync the intended release version + dependent ranges; validate lockfiles +
package contents; build official plugins through the CLI; exercise the local toolchain smoke path;
trigger `plugin-tooling-vX.Y.Z` or workflow dispatch; treat the workflow + version resolver as the
release contract; explicit user confirmation before commits/tags/pushes/dispatches/registry
publication; must NOT change the main app version.

- [ ] **Step 1: Write the canonical skill with the content below**

Create `.agents/skills/plugin-tooling-release/SKILL.md`:

````markdown
---
name: plugin-tooling-release
description: Cut an independently versioned FengYu plugin-toolchain release (tag plugin-tooling-vX.Y.Z or workflow_dispatch input). Covers fan.summer.fengyu.sdk:fengyu-plugin-sdk, @infinia/plugin-sdk, @infinia/plugin-ui, and @infinia/plugin-cli. Synchronizes the release version across all four, validates lockfiles and package contents, builds official plugins through the CLI, and exercises the local toolchain smoke path. Use when the user asks to release/publish the plugin SDK, UI kit, or CLI. Does NOT change the main application version — use app-release for that.
---

# Plugin Tooling Release

Release the **independently versioned plugin toolchain** — four artifacts that move together at one
version:

| Artifact | Source of truth |
|---|---|
| `fan.summer.fengyu.sdk:fengyu-plugin-sdk` | `FengYu-Plugin-Sdk/pom.xml` |
| `@infinia/plugin-cli` | `plugin-cli/package.json` |
| `@infinia/plugin-sdk` | `plugin-sdk/typescript/package.json` |
| `@infinia/plugin-ui` | `plugin-ui/vue/package.json` |

This skill does **not** change the main app version (`${revision}`). Use the `app-release` skill for
app releases.

The release contract is `.github/workflows/plugin-tooling-release.yml` plus
`plugin-cli/scripts/resolve-tooling-version.mjs`.

## Step 1 — Resolve and verify the version

The resolver accepts a `plugin-tooling-vX.Y.Z` tag, a `--ref`, or an explicit `--input`, validates
strict semver (no leading zeros), and cross-checks all four sources via `verifyRepositoryVersion`:

```bash
node plugin-cli/scripts/resolve-tooling-version.mjs --ref plugin-tooling-vX.Y.Z   # prints the version
```

If it throws (invalid semver, or any of the four sources disagree), stop and reconcile the versions
with the user before proceeding.

## Step 2 — Synchronize the version across all four artifacts

Bump the version in exactly these four files to the intended release version and nothing else:

- `FengYu-Plugin-Sdk/pom.xml` (`<version>`)
- `plugin-cli/package.json`
- `plugin-sdk/typescript/package.json`
- `plugin-ui/vue/package.json`

Also update any **dependent range** that is meant to track the release (e.g. a `@infinia/*`
peer/dependency range that must move with the release). Do **not** touch `pom.xml` `${revision}` or
any app-side manifest.

## Step 3 — Validate lockfiles and package contents

- Regenerate/confirm lockfiles for the three npm packages are consistent with the bumped versions.
- Run each package's own checks:

```bash
# CLI
cd plugin-cli && npm install && npm test && cd ..
# TS SDK
cd plugin-sdk/typescript && npm install && npm test && cd ..
# UI kit (includes Playwright visual tests)
cd plugin-ui/vue && npm install && npm test && cd ..
# Java Worker SDK
mvn -f FengYu-Plugin-Sdk/pom.xml test
```

- Enforce packaging boundaries:

```bash
scripts/check-plugin-dependency-boundaries.sh
```

## Step 4 — Build official plugins through the CLI

Confirm the toolchain can actually produce a plugin end to end by building an official plugin with
the CLI (this is the same path the release's `consumer-smoke` job exercises against published
packages):

```bash
fengyu plugin validate OfficialPlugins/plugin-markdown
fengyu plugin build OfficialPlugins/plugin-markdown
```

Repeat for the other official plugins the toolchain must support (`plugin-excel`, `plugin-email`,
`plugin-offlinepython`).

## Step 5 — Exercise the local toolchain smoke path

```bash
scripts/plugin-tooling-local-smoke.sh
```

This installs the Java SDK to local `.m2`, packs the TS SDK/UI kit, and confirms a consumer can
resolve and use them locally. It must pass before release mutation. Also confirm docs still build
(the release workflow's `verify` job runs this):

```bash
npm run docs:build
```

## Step 6 — Mutate, with explicit confirmation

**Committing, tagging, pushing, dispatching the workflow, or publishing to a registry each require
explicit user confirmation.** Do not run these automatically. When the user confirms:

1. Commit the four version bumps (+ any tracked dependent range) with a `⬆️`/`📝` conventional message.
2. Create the tag `plugin-tooling-vX.Y.Z`.
3. Push the branch and the tag — the tag push triggers
   `.github/workflows/plugin-tooling-release.yml`, which verifies, publishes
   `fengyu-plugin-sdk` to GitHub Packages and the three `@infinia/*` packages to npm (with provenance),
   then runs a consumer smoke against the just-published packages. (Manual `workflow_dispatch` with a
   `tooling_version` input is the alternative trigger.)

**Never** bump the main app version (`${revision}`) in order to release the toolchain.
````

- [ ] **Step 2: Verify frontmatter + no forbidden refs + app-version boundary**

Run:
```bash
head -4 .agents/skills/plugin-tooling-release/SKILL.md
grep -n -E '\.claude-plugin/|sync-plugin-standards' .agents/skills/plugin-tooling-release/SKILL.md || echo "no forbidden refs"
grep -n 'app-release\|app version' .agents/skills/plugin-tooling-release/SKILL.md
```
Expected: valid frontmatter; `no forbidden refs`; the body references `app-release` for redirection
and states the toolchain release must not bump the app version.

- [ ] **Step 3: Commit**

```bash
git add .agents/skills/plugin-tooling-release/SKILL.md
git commit -m "📝 docs(agents): add canonical plugin-tooling-release skill"
```

---

## Task 7: Create the Claude Code adapters (`CLAUDE.md` + four `.claude/skills/`)

**Files:**
- Create: `CLAUDE.md`
- Create: `.claude/skills/fengyu-plugin-dev/SKILL.md`
- Create: `.claude/skills/docs-updater/SKILL.md`
- Create: `.claude/skills/app-release/SKILL.md`
- Create: `.claude/skills/plugin-tooling-release/SKILL.md`

**Interfaces:**
- Consumes: the five canonical files from Tasks 2–6 (`AGENTS.md` + the four `.agents/skills/<name>/SKILL.md`).
- Produces: the complete Claude Code adapter surface — valid skill metadata + a pointer to the canonical skill, with **no** copied architecture, command sequences, templates, or release rules.

**Content rules (from spec §"Claude Code Adapters"):** `CLAUDE.md` tells Claude Code to read and
follow `AGENTS.md`. Each `.claude/skills/<name>/SKILL.md` has valid skill metadata and instructs
Claude Code to read and execute `.agents/skills/<name>/SKILL.md` **completely**. No duplication.

- [ ] **Step 1: Write `CLAUDE.md`**

Create `CLAUDE.md`:

````markdown
# CLAUDE.md

This file guides Claude Code in this repository.

**Read and follow [`AGENTS.md`](AGENTS.md).** It is the single canonical source of project guidance
for all AI assistants, including Claude Code. Do not maintain a separate copy of the architecture,
build commands, plugin model, release rules, or working conventions here — `AGENTS.md` (and the
canonical skills under `.agents/skills/`) is the source of truth.

When a workflow skill applies, Claude Code reaches the same canonical skill through the short
adapter under `.claude/skills/<name>/SKILL.md`, which points at `.agents/skills/<name>/SKILL.md`.
Execute that canonical skill completely.
````

- [ ] **Step 2: Write the four `.claude/skills/` adapters**

Create `.claude/skills/fengyu-plugin-dev/SKILL.md`:

````markdown
---
name: fengyu-plugin-dev
description: Build, scaffold, develop, validate, build, package, and install FengYu plugins. Adapter — executes the canonical skill at .agents/skills/fengyu-plugin-dev/SKILL.md.
---

# fengyu-plugin-dev (adapter)

This is a Claude Code adapter. **Read and execute the canonical skill completely:**
[`../../.agents/skills/fengyu-plugin-dev/SKILL.md`](../../.agents/skills/fengyu-plugin-dev/SKILL.md)

Do not act from this file alone — the canonical skill holds the full workflow, authoritative inputs,
CLI commands, and hard prohibitions. Follow it end to end.
````

Create `.claude/skills/docs-updater/SKILL.md`:

````markdown
---
name: docs-updater
description: Update README.md, CHANGELOG.md, docs/en/, and docs/zh/ after a code or release change. Adapter — executes the canonical skill at .agents/skills/docs-updater/SKILL.md.
---

# docs-updater (adapter)

This is a Claude Code adapter. **Read and execute the canonical skill completely:**
[`../../.agents/skills/docs-updater/SKILL.md`](../../.agents/skills/docs-updater/SKILL.md)

Do not act from this file alone — the canonical skill holds the diff-driven workflow, version-line
rules, and validation steps. Follow it end to end.
````

Create `.claude/skills/app-release/SKILL.md`:

````markdown
---
name: app-release
description: Cut a main FengYu/Infinia application release. Adapter — executes the canonical skill at .agents/skills/app-release/SKILL.md.
---

# app-release (adapter)

This is a Claude Code adapter. **Read and execute the canonical skill completely:**
[`../../.agents/skills/app-release/SKILL.md`](../../.agents/skills/app-release/SKILL.md)

Do not act from this file alone — the canonical skill holds the tag contract, verification steps, and
the confirmation gates for commits/tags/pushes. Follow it end to end.
````

Create `.claude/skills/plugin-tooling-release/SKILL.md`:

````markdown
---
name: plugin-tooling-release
description: Cut an independently versioned FengYu plugin-toolchain release. Adapter — executes the canonical skill at .agents/skills/plugin-tooling-release/SKILL.md.
---

# plugin-tooling-release (adapter)

This is a Claude Code adapter. **Read and execute the canonical skill completely:**
[`../../.agents/skills/plugin-tooling-release/SKILL.md`](../../.agents/skills/plugin-tooling-release/SKILL.md)

Do not act from this file alone — the canonical skill holds the four-artifact version sync, validation,
smoke path, and the confirmation gates for commits/tags/pushes/dispatches/publication. Follow it end
to end.
````

- [ ] **Step 3: Verify adapters have metadata, point at the right canonical file, and duplicate nothing**

Run:
```bash
for f in CLAUDE.md \
         .claude/skills/fengyu-plugin-dev/SKILL.md \
         .claude/skills/docs-updater/SKILL.md \
         .claude/skills/app-release/SKILL.md \
         .claude/skills/plugin-tooling-release/SKILL.md; do
  echo "== $f =="
  head -4 "$f"
done
echo "--- adapter body sizes (should be small) ---"
wc -l .claude/skills/*/SKILL.md CLAUDE.md
echo "--- each adapter must name its canonical target ---"
grep -L '.agents/skills/' .claude/skills/*/SKILL.md && echo "MISSING TARGET" || echo "all adapters reference canonical skill"
grep -L 'AGENTS.md' CLAUDE.md && echo "CLAUDE.md MISSING AGENTS.md ref" || echo "CLAUDE.md points at AGENTS.md"
echo "--- no forbidden refs anywhere in adapters ---"
grep -rn -E '\.claude-plugin/|sync-plugin-standards' CLAUDE.md .claude/skills/ || echo "no forbidden refs"
```
Expected: every file has valid `---` frontmatter with `name:` + `description:`; adapter line counts
are small (single-digit to low-teens); `all adapters reference canonical skill`; `CLAUDE.md points at
AGENTS.md`; `no forbidden refs`.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md .claude/skills/
git commit -m "📝 docs(agents): add Claude Code adapters (CLAUDE.md + 4 skill pointers)"
```

---

## Task 8: Full verification pass against the spec's 8 criteria

**Files:**
- Verify only (no edits unless a check fails — then return to the producing task).

This task enforces the spec's "Verification" section. No new script is added — these are repository
searches and focused command checks, exactly as the spec requires.

- [ ] **Step 1: Confirm exactly the ten target behavior files remain**

Run:
```bash
echo "--- canonical + adapter files that should exist ---"
for f in AGENTS.md \
         .agents/skills/fengyu-plugin-dev/SKILL.md \
         .agents/skills/docs-updater/SKILL.md \
         .agents/skills/app-release/SKILL.md \
         .agents/skills/plugin-tooling-release/SKILL.md \
         CLAUDE.md \
         .claude/skills/fengyu-plugin-dev/SKILL.md \
         .claude/skills/docs-updater/SKILL.md \
         .claude/skills/app-release/SKILL.md \
         .claude/skills/plugin-tooling-release/SKILL.md; do
  test -f "$f" && echo "OK  $f" || echo "MISSING $f"
done
echo "--- old config must be gone ---"
for p in .agents/skills/create-builtin-tool .agents/skills/fengyu-plugin-dev/references \
         .agents/skills/fengyu-plugin-dev/assets .agents/skills/fengyu-plugin-dev/INSTALL.md \
         .claude/agents .claude/commands .claude/skills/release.md .claude-plugin; do
  test -e "$p" && echo "STILL EXISTS $p" || echo "gone  $p"
done
echo "--- no other files under the assistant-config trees ---"
find .agents .claude -type f | sort
```
Expected: the ten files all `OK`; every old path `gone`; `find` lists **exactly** the four
`.agents/skills/*/SKILL.md` + four `.claude/skills/*/SKILL.md` (eight files total under those trees).

- [ ] **Step 2: Every skill has valid frontmatter, a unique name, and resolvable references**

Run:
```bash
echo "--- unique skill names across .agents and .claude ---"
grep -h '^name:' .agents/skills/*/SKILL.md .claude/skills/*/SKILL.md | sort
echo "--- canonical skill files referenced by adapters must exist ---"
for f in .agents/skills/fengyu-plugin-dev/SKILL.md \
         .agents/skills/docs-updater/SKILL.md \
         .agents/skills/app-release/SKILL.md \
         .agents/skills/plugin-tooling-release/SKILL.md; do
  test -f "$f" && echo "resolves $f" || echo "BROKEN REF $f"
done
echo "--- AGENTS.md referenced by CLAUDE.md must exist ---"
test -f AGENTS.md && echo "resolves AGENTS.md" || echo "BROKEN AGENTS.md"
```
Expected: skill names are unique and matching in pairs (four canonical, four adapters, same four
names); all referenced canonical files `resolves`.

- [ ] **Step 3: Claude adapters point at the correct canonical skill and do not duplicate it**

Run:
```bash
for pair in \
  "fengyu-plugin-dev" \
  "docs-updater" \
  "app-release" \
  "plugin-tooling-release"; do
  a=".claude/skills/$pair/SKILL.md"; c=".agents/skills/$pair/SKILL.md"
  echo "== $pair =="
  grep -q ".agents/skills/$pair/SKILL.md" "$a" && echo "adapter points at canonical" || echo "WRONG TARGET"
  # adapter must be materially shorter than canonical (no duplication)
  al=$(wc -l < "$a"); cl=$(wc -l < "$c")
  echo "adapter=$al lines canonical=$cl lines"
  [ "$al" -lt "$cl" ] && echo "adapter is smaller (OK)" || echo "adapter not smaller (REVIEW)"
done
```
Expected: each adapter `points at canonical` with the matching name and is materially smaller than
its canonical file.

- [ ] **Step 4: No affirmative legacy instructions (explicit prohibitions allowed)**

Run:
```bash
grep -rni -E 'SwissKitJ|ZhiFlow|fengyuj' AGENTS.md .agents/skills/ .claude/ && echo "FOUND AFFIRMATIVE LEGACY NAME" || echo "no legacy product names"
echo "--- FengYuPluginV2 / ServiceLoader / JavaFX must appear only as prohibitions ---"
grep -rni -E 'FengYuPluginV2|ServiceLoader|createView|JavaFX' AGENTS.md .agents/skills/
```
Expected: `no legacy product names`; any `FengYuPluginV2`/`ServiceLoader`/`createView`/`JavaFX` hits
are in prohibition sentences (e.g. "Do not generate", "must not", "legacy"). Manually confirm each hit
is prohibitive, not instructive.

- [ ] **Step 5: Plugin commands match the current CLI / manifest schema / official layouts**

Run:
```bash
echo "--- CLI subcommands cited in skill must match src/cli.mjs ---"
grep -oE 'fengyu plugin (create|dev|build|validate|install)' .agents/skills/fengyu-plugin-dev/SKILL.md | sort -u
grep -oE "command === '(create|dev|build|validate|install)'" plugin-cli/src/cli.mjs | sort -u
echo "--- create --id is required (per src/cli.mjs) ---"
grep -n -- '--id' .agents/skills/fengyu-plugin-dev/SKILL.md
echo "--- manifest schema permission enum must match plugin-spec ---"
grep -A12 '"permissions"' plugin-spec/manifest.schema.json | grep -E 'files\.|network|clipboard|notifications|database'
```
Expected: the skill's `fengyu plugin <...>` set matches the CLI's `command ===` branches; `--id` is
shown as required for `create`; the permissions the skill lists match the schema enum.

- [ ] **Step 6: Docs and release commands match current package scripts / resolvers / workflows**

Run:
```bash
echo "--- docs:build exists in root package.json ---"
grep -n '"docs:build"' package.json
echo "--- app release resolver + workflow present ---"
test -f scripts/resolve-release-version.mjs && echo "OK resolve-release-version.mjs"
grep -q 'fengyu-release' .github/workflows/fengyu-release.yml && echo "OK fengyu-release.yml"
echo "--- tooling resolver + workflow present ---"
test -f plugin-cli/scripts/resolve-tooling-version.mjs && echo "OK resolve-tooling-version.mjs"
grep -q 'plugin-tooling-v\*' .github/workflows/plugin-tooling-release.yml && echo "OK plugin-tooling-release.yml"
echo "--- smoke scripts referenced by skills exist ---"
for s in scripts/e2e-smoke.sh scripts/package-web-release.sh scripts/test-web-release.sh \
         scripts/plugin-tooling-local-smoke.sh scripts/check-plugin-dependency-boundaries.sh; do
  test -f "$s" && echo "OK $s" || echo "MISSING $s"
done
```
Expected: every cited script/workflow/resolver exists; `docs:build` is in root `package.json`.

- [ ] **Step 7: No rebuilt file references `.claude-plugin/` or `scripts/sync-plugin-standards.sh`**

Run:
```bash
grep -rn -E '\.claude-plugin/|sync-plugin-standards' AGENTS.md .agents/ .claude/ && echo "FORBIDDEN REF FOUND" || echo "clean"
```
Expected: `clean`.

- [ ] **Step 8: `git diff --check` passes and the working tree is as intended**

Run:
```bash
git diff --check && echo "diff-check clean"
git status --short
```
Expected: `diff-check clean`; `git status` shows only the ten new/modified tracked files (the old
paths removed, the new paths added) with no stray whitespace errors.

- [ ] **Step 9: Final commit if verification surfaced any fix**

If Steps 1–8 required edits, stage and commit them:
```bash
git add -A
git commit -m "✅ test(agents): pass assistant-config governance verification"
```
If no edits were needed, skip — the per-task commits already capture the work.

---

## Notes for the implementer

- This plan touches **only** assistant-configuration files. Do not modify application source, build
  scripts, workflows, product docs, or `docs/superpowers/` as part of these tasks.
- The `.agents/skills/fengyu-plugin-dev/references/` and `assets/plugin-template/` trees from the old
  config are **deliberately not recreated**. The spec's target structure lists only the four
  `SKILL.md` files; the canonical skill links out to authoritative repo paths instead of carrying
  copied references/templates. If a reviewer asks where the scaffold template went, the answer is
  `plugin-cli/templates/` (the CLI's own templates) — that is the authoritative scaffold source.
- Verification is intentionally search-based. The spec forbids adding a new validation/sync script,
  so do not create one even if it would be convenient.
