# Tauri → Electron Desktop Shell Migration — Design

- **Date:** 2026-07-24
- **Branch:** new branch cut from `4.0.0-FengYu` (e.g. `4.0.0-electron`)
- **Scope:** Replace the Tauri 2.0 desktop shell entirely with an Electron 43.x shell, plus code review of the current Tauri implementation folded into the migration. Ship two installer variants per platform (with-JRE and without-JRE). Add single-instance lock, system tray, file logging, and auto-update. Refactor the frontend bridge to a `contextBridge` API. Sync all docs.
- **Out of scope:** code signing (Alpha stays unsigned); bundling a DB file (wizard creates it on first run); rewriting the plugin toolchain (verified shell-neutral — see §10).

---

## 1. Decisions (locked during brainstorming)

| Dimension | Decision |
|---|---|
| Delivery shape | Code review + migration completed together on the new branch |
| Branch origin | Cut from `4.0.0-FengYu` |
| Tauri disposition | **Fully removed** (`desktop/src-tauri/` deleted; recoverable via git history) |
| Stack | Electron **43.x** (Node 24.17) + TypeScript main process + **electron-builder** |
| JRE strategy | Ship **two** variants per platform: without-JRE (lightweight) and with-JRE (jlink-minimized, CI-generated) |
| Java detection (no-JRE variant) | `which java`; missing → native error dialog → exit |
| Enhancements | **All four** added: single-instance lock, system tray, file logging, auto-update |
| Auto-update source | GitHub Releases (`latest.yml` / `latest-mac.yml` / `latest-linux.yml`) |
| Signing | **Alpha unsigned** (Windows NSIS; macOS user-allowed Gatekeeper bypass) |
| Frontend bridge | `contextBridge` API on `window.fengyu`; **token exposed as read-only snapshot function** (not full IPC proxy) |
| Close semantics | Close button → **minimize to tray** (backend stays alive); tray "Quit" / `before-quit` → kill backend & exit |
| Testing | Unit tests (vitest) + e2e launch test (`@playwright/test` + electron) |
| Docs | **All** synced in-branch (desktop/README, docs/{en,zh}, AGENTS.md, root README, 2 skills) |

### Why token-snapshot over full IPC proxy

The frontend's hardest paths — AI chat **SSE streaming** (`sse.ts`), file uploads (`uploadNativeSkill`, plugin install FormData), and the **plugin micro-frontend host** that proxies plugin fetches — all rely on browser-native `fetch`/`EventSource`/`FormData` hitting `127.0.0.1`. Moving all HTTP through IPC would require re-implementing SSE chunking, upload progress, and abort semantics over `ipcRenderer` — weeks of fragile work concentrated exactly where regressions hurt most. The token is per-launch, loopback-only, and the backend enforces endpoint ACLs regardless, so the security gain from hiding it is marginal while the engineering cost is front-loaded on the riskiest surfaces. Token-snapshot keeps those paths zero-change.

---

## 2. Backend contract (unchanged — the Electron port must replicate this)

The Java backend's CLI/stdout/HTTP handshake is **not** changing. This is the contract the Electron main process must faithfully reproduce (ported 1:1 from `desktop/src-tauri/src/main.rs`):

- **Entry class:** `fan.summer.fengyu.HeadlessLauncher`, launched as `java -cp <jar> fan.summer.fengyu.HeadlessLauncher`.
- **CLI args:** `--port=<n>` (default `24056`), `--token=<t>`.
- **System property:** `-Dfengyu.plugins.official-directory=<dir>` — where official `.fyp` plugins live.
- **Port discovery:** backend prints `FENGYU_PORT=<actual>` to **stdout** once Tomcat is up. If the requested port is taken, it retries with OS-assigned (`--server.port=0`) and announces that port. The shell **must** read this line.
- **Bind:** always `127.0.0.1` (loopback-only), enforced by `runtimeDefaults()` + `application.yml`.
- **Two modes** (decided by probing `~/.fengyu/config/datasource.properties`):
  - **SETUP** (no/stale config): boots `SetupApplication` (minimal wizard context). When the wizard finishes, the process **exits with code `0`** (`SETUP_DONE`) so the supervisor restarts it into APP mode.
  - **APP** (config present + `SELECT 1` ok): boots `FengYuApplication` with `fengyu.mode=app`.
- **Readiness endpoints:** `GET /api/health` (expect HTTP 200); `GET /api/setup/status` (body contains `"initialized":false` in SETUP mode).
- **Token header:** all backend calls carry `X-FengYu-Token: <token>`.
- **Log dir:** backend primes `~/.fengyu/logs` itself (Java-side); independent of the shell.

---

## 3. Directory structure

```
desktop/
├── README.md                    # rewritten: Electron dev/build
├── .gitignore                   # dist/ out/ node_modules/ resources/jre/ resources/binaries/
└── electron/
    ├── package.json             # electron ^43, electron-builder, electron-updater, electron-log, vitest, @playwright/test
    ├── tsconfig.json            # main-process TS (module=commonjs, target=ES2022, node types)
    ├── electron-builder.yml     # base config; CI overrides via --config for with/without-JRE variants
    ├── src/
    │   ├── main.ts              # entry: app.whenReady → orchestrate backend → create window
    │   ├── backend/
    │   │   ├── runtime-layout.ts   # resolve resource paths: jar / plugins / jre (dev vs packaged)
    │   │   ├── spawn.ts            # spawn(javaBin, [-D..., -cp, jar, HeadlessLauncher, --port, --token])
    │   │   ├── handshake.ts        # read FENGYU_PORT stdout → /api/health → /api/setup/status
    │   │   └── supervisor.ts       # SETUP→APP restart supervisor (exit code 0 → respawn)
    │   ├── window/
    │   │   ├── create-window.ts    # BrowserWindow(1280×820, min 960×640), contextIsolation+sandbox
    │   │   └── preload.ts          # contextBridge.exposeInMainWorld('fengyu', {...})
    │   ├── desktop/
    │   │   ├── single-instance.ts  # app.requestSingleInstanceLock()
    │   │   ├── tray.ts             # Tray + menu (Show / Hide / Quit); close→tray semantics
    │   │   └── logger.ts           # electron-log → ~/.fengyu/logs/desktop.log + backend-stdout.log
    │   ├── updater/
    │   │   └── auto-updater.ts     # electron-updater, GitHub Releases source, async non-blocking check
    │   └── util/
    │       ├── token.ts            # gen token: zf-{hex(nanos)}-{hex(pid)} — matches Rust format
    │       └── health.ts           # fetch polling (30s/300ms, X-FengYu-Token header, 2s per-request)
    ├── resources/
    │   ├── icon.png / icon.ico     # migrated from src-tauri/icons/
    │   └── (CI injects) binaries/FengYu.jar, plugins/*.fyp, jre/ (with-JRE variant only)
    └── test/
        ├── runtime-layout.test.ts
        ├── supervisor.test.ts         # shouldRestartSetup(shuttingDown, exitCode) — 3 cases
        ├── handshake.test.ts          # SETUP/APP detection + FENGYU_PORT parse
        ├── token.test.ts              # format + per-launch variance
        ├── health.test.ts             # 200 ready / non-200 retry / timeout fatal (fake timers + mock fetch)
        └── e2e/
            └── launch.spec.ts         # @playwright/test + electron: real window + prebuilt JAR
```

**Stack pinned:** Electron `^43` (Node 24.17 runtime), electron-builder (latest compatible), electron-updater, electron-log, vitest (unit), `@playwright/test` (e2e). **Not introduced:** electron-forge, electron-vite, nx — keep the main process decoupled from the existing Vite frontend.

---

## 4. Backend orchestration (main.ts — faithful port of the Rust prod lifecycle)

This is the correctness-critical core. Timings and timeouts are preserved verbatim from the Rust implementation.

**`app.whenReady()` flow:**

1. **Resolve runtime layout** (`runtime-layout.ts`):
   - Packaged: `process.resourcesPath` → `binaries/FengYu.jar`, `plugins/`, (with-JRE variant) `jre/`.
   - Dev: JAR path via `FENGYU_JAR` env or external `mvn -pl FengYu spring-boot:run` on `:24056`. `isPackaged = app.isPackaged` distinguishes (mirrors Rust's `debug_assertions` split).

2. **Generate token** (`token.ts`): `zf-{hex(nanos_since_epoch)}-{hex(pid)}` — identical format to Rust's `gen_token`. Passed to backend `--token=` and to preload.

3. **Resolve Java executable** (new — Rust didn't need this):
   - With-JRE variant: prefer `<resourcesPath>/jre/bin/java` (`.exe` on Windows).
   - Without-JRE variant: `which java` / `where java`; not found → `dialog.showErrorBox` → `app.quit()` (locked decision).

4. **Spawn backend** (`spawn.ts`):
   ```ts
   spawn(javaBin, [
     `-Dfengyu.plugins.official-directory=${layout.plugins}`,
     '-cp', layout.jar,
     'fan.summer.fengyu.HeadlessLauncher',
     `--port=${requestedPort}`,   // 24056
     `--token=${token}`,
   ], { stdio: ['ignore', 'pipe', 'pipe'] })
   ```
   Line-split `child.stdout`, scan for `FENGYU_PORT=<n>`, parse port. **30s deadline, cancellable** (cancel checked each poll iteration so window-close during slow boot can't hang) — mirrors Rust's `recv_timeout(200ms)` loop. Backend stdout/stderr lines are tee'd to `backend-stdout.log` via electron-log.

5. **Health polling** (`health.ts`): `GET http://127.0.0.1:{port}/api/health` with `X-FengYu-Token` header. **2s per-request timeout, 300ms interval, 30s overall deadline, only HTTP 200 = ready, cancellable.** Uses Node 24.17's built-in `fetch` + `AbortController`.

6. **SETUP probe** (`handshake.ts`): `GET /api/setup/status`; body contains `"initialized":false` → SETUP mode.

7. **Orchestrate**: `spawn → handshake`. Any failure → `child.kill()` + fatal error. Success returns `{ child, port, setupMode }`.

8. **SETUP→APP restart supervisor** (`supervisor.ts`) — the most subtle piece, ported faithfully:
   - Only launches a background watcher when backend booted in SETUP mode.
   - Listens on child `close` event (Node event model; equivalent to Rust's `try_wait` poll).
   - Exit code **=== 0** (Java `SETUP_DONE` signal) → **respawn backend** (re-run spawn→handshake).
   - After respawn: **validate port unchanged** (change = fatal: "webview endpoint cannot change") + **validate now in APP mode** (still-SETUP = fatal) → swap new child into state.
   - Non-zero exit, or close while `shuttingDown` → log fatal, do not restart.
   - Unit-tested: `shouldRestartSetup(shuttingDown, exitCode)` covers the 3 Rust cases.

9. **Inject bridge** — see §5 (preload).

10. **Shutdown semantics** (changed from Tauri due to tray — see §7):
    - Window close button → `window.hide()` (minimize to tray); backend **stays alive**.
    - Tray "Quit" / `before-quit` event → `shuttingDown = true` → `child.kill()` (SIGTERM, SIGKILL fallback) → join supervisor → `app.quit()`.
    - **Net change from Tauri:** backend lifetime is now tied to **app quit**, not window close. Documented prominently in desktop.md.

---

## 5. Frontend bridge refactor (contextBridge)

**Current (Tauri):** `main.rs` injects `window.__FENGYU_TOKEN__`/`__PORT__`/`__API_BASE__` before page load; `config.ts` reads them directly.

**Electron:** preload script uses `contextBridge` to expose a controlled API.

```ts
// preload.ts
contextBridge.exposeInMainWorld('fengyu', {
  apiBase: () => 'http://127.0.0.1:' + port,                    // read-only snapshot
  token: () => token,                                            // read-only snapshot
  pickFile: (opts) => ipcRenderer.invoke('dialog:open', { ...opts, directory: false }),
  pickDirectory: () => ipcRenderer.invoke('dialog:open', { directory: true }),
  desktop: true,                                                 // feature flag, replaces isTauri()
})
```

**BrowserWindow config:** `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`, `webSecurity: true` (default, not disabled) — standard Electron secure posture. CSP governed by backend SPA response headers, not set to `null` in main.

**Frontend changes (3 files + types):**

1. `frontend/src/api/config.ts` — `getApiBase()`/`getToken()` prefer `window.fengyu?.apiBase()`/`token()`, fall back to `import.meta.env.VITE_FENGYU_API_BASE`/`VITE_FENGYU_TOKEN` (web mode). `pluginAssetUrl()` localhost↔127.0.0.1 swap logic **preserved** (plugin iframe isolation unchanged).

2. `frontend/src/mf/desktop.ts` — `isDesktop()` becomes `window.fengyu?.desktop === true` (replaces `__TAURI_INTERNALS__` probe). `makeDesktop()` `pickFile`/`pickDirectory` call `window.fengyu.pickFile()`; **remove** `@tauri-apps/plugin-dialog` dynamic import.

3. `frontend/package.json` — remove `@tauri-apps/plugin-dialog`; the SPA does **not** depend on `electron` at runtime (preload runs in the main-process context, not the renderer bundle).

4. `frontend/src/electron-env.d.ts` (new) — declare `Window.fengyu` type. `frontend/src/env.d.ts` — remove `__TAURI_INTERNALS__`/`__TAURI__` declarations (keep `__FENGYU_*` removed too since they're gone).

**Web mode unaffected:** `window.fengyu` is `undefined` in a browser; `config.ts` and `desktop.ts` fall through to env vars, identical to today.

---

## 6. Desktop enhancements (all four added)

**1. Single-instance lock** (`single-instance.ts`): `app.requestSingleInstanceLock()`. On `second-instance`, `show()` + `focus()` the existing window (also restores from tray). No-lock → `app.quit()`.

**2. System tray** (`tray.ts`): icon migrated from `src-tauri/icons/`. Menu: Show / Hide / Quit. **Close button → hide to tray** (backend alive); tray Quit / `before-quit` → kill backend & exit (locked decision).

**3. File logging** (`logger.ts`): `electron-log` writes main-process logs to `~/.fengyu/logs/desktop.log` (same dir as backend logs). Backend stdout/stderr tee'd to `~/.fengyu/logs/backend-stdout.log` (replaces Rust's `println!("[backend] {line}")`, but persisted). Built-in size/date rotation.

**4. Auto-update** (`auto-updater.ts`): `electron-updater`, source = GitHub Releases (owner/repo in config). Alpha unsigned: Windows via NSIS (electron-updater supports unsigned updates); macOS requires user Gatekeeper allow; Linux no signing concept. Trigger: async check after `app.whenReady()` (non-blocking, doesn't delay window); on update available → native `dialog` confirm → `downloadAndInstall` → `quitAndInstall`; user decline → no nag. `latest*.yml` generated by electron-builder and uploaded with the Release.

---

## 7. Shutdown semantics change (important — document prominently)

| | Tauri (current) | Electron (new) |
|---|---|---|
| Window close button | kills backend, exits | **hides to tray**, backend alive |
| Tray "Quit" / Cmd+Q / Alt+F4 | n/a | kills backend, exits |

This is a deliberate consequence of adding the tray. `main.ts` kills the backend on `before-quit` (not on `window.on('closed')`). The `desktop.md` doc and README will call this out explicitly so users understand the backend now persists across window close.

---

## 8. Packaging & artifact matrix

**Per-platform, two variants:**

| Platform | Without JRE (lightweight) | With JRE (complete) |
|---|---|---|
| macOS (arm64 + x64) | `Infinia-<ver>-mac.dmg` | `Infinia-<ver>-mac-jre.dmg` |
| Windows (x64) | `Infinia-<ver>-win.exe` (NSIS) | `Infinia-<ver>-win-jre.exe` |
| Linux (x64) | `Infinia-<ver>.AppImage` | `Infinia-<ver>-jre.AppImage` |

**Implementation:** single `electron-builder.yml` base config; CI runs the build twice with `--config` overrides for `extraResources` (adds `jre/`) and `artifactName` (adds `-jre` suffix). One config file, not two.

**JRE generation (CI, per-platform runner):**
```bash
# JDK 21 LTS (matches bytecode target 21; avoids pulling newer-platform modules)
# 1. jdeps derives modules from the shaded jar
JLINK_MODS=$(jdeps --multi-release 21 --ignore-missing-deps --print-module-deps \
    -cp FengYu.jar FengYu.jar)
# 2. union with explicit fallback list (guards against reflection blind spots)
EXPLICIT="java.base,java.compiler,java.desktop,java.instrument,java.management,\
java.naming,java.net.http,java.scripting,java.sql,java.sql.rowset,\
java.transaction.xa,java.xml,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.unsupported,\
jdk.zipfs,jdk.management"
# 3. jlink (strip-debug shrinks size)
jlink --no-header-files --no-man-pages --strip-debug \
    --add-modules "$JLINK_MODS,$EXPLICIT" \
    --output desktop/electron/resources/jre/
```

**Module-list rationale** (from dependency audit): Spring Boot 4.1 / Spring Framework 7, servlet+Tomcat stack, JPA+Hibernate+ByteBuddy, Jackson, JAXB (glassfish) → `java.xml`, Jakarta Mail/Tomcat JNDI → `java.naming`, `java.net.http.HttpClient` used directly by marketplace services, TLS to remote DBs + OpenAI/Anthropic/Ollama → `jdk.crypto.cryptoki`+`jdk.crypto.ec`, ByteBuddy/Jackson/HikariCP `sun.misc.Unsafe` → `jdk.unsupported`, `.fyp` zip loading → `jdk.zipfs`. sqlite-jdbc native libs are classpath-loaded (work under jlink, no extra module). No `module-info.java` anywhere (non-modular).

**Runtime path resolution:**
- Without-JRE: `<resourcesPath>/binaries/FengYu.jar` + `plugins/`; java from PATH (missing → error exit).
- With-JRE: same + `<resourcesPath>/jre/bin/java`, preferred.

**Size estimate:** without-JRE ~170MB (Electron ~120MB + JAR ~166MB compressed); with-JRE adds ~40–60MB (jlink image).

---

## 9. Testing strategy

**Unit tests** (`desktop/electron/test/*.test.ts`, vitest) — faithful alignment with the 3 Rust unit tests + IPC/utility coverage:
- `runtime-layout.test.ts` — path resolution (dev/prod branches); mirrors Rust `runtime_layout`.
- `supervisor.test.ts` — `shouldRestartSetup(shuttingDown, exitCode)`: shuttingDown blocks restart / only exit===0 restarts / non-zero doesn't; mirrors Rust cases.
- `handshake.test.ts` — SETUP/APP detection (body `"initialized":false` → SETUP); `FENGYU_PORT=24056` line extraction.
- `token.test.ts` — format `zf-{hex}-{hex}`, per-launch variance.
- `health.test.ts` — polling: 200 ready / non-200 retry / timeout fatal (fake timers + mock fetch).

**Isolation principle:** pure-logic functions extracted from main-process code into independently-importable modules (`backend/*.ts` don't import `electron` directly; receive injected deps like `fetch`, `now()`). Unit tests run in-memory mocks, no Electron, no Java.

**e2e launch test** (`desktop/electron/test/e2e/launch.spec.ts`, `@playwright/test` + electron): real `BrowserWindow` against a **prebuilt shaded JAR** (CI runs `mvn package` first). Asserts: window appears → preload injects `window.fengyu` → frontend `/api/health` reachable → SETUP mode (empty DB) loads wizard. **Does not** cover supervisor restart full timing (too slow/fragile in CI) — supervisor correctness stays in unit tests; e2e only verifies "it comes up."

**CI integration:** `desktop` matrix job runs `npm test` (unit) + `npm run test:e2e` (e2e, Linux-only to save matrix time) before packaging. `scripts/release-workflow.test.mjs` assertions rewritten to validate `electron-builder.yml` shape.

**Acceptance bar:** unit green + e2e passes on Linux = packaging prerequisite for that platform.

---

## 10. Toolchain compatibility (the explicitly-requested audit)

**Plugin toolchain is essentially shell-neutral — verified:**

| Tool/module | Tauri coupling | Migration impact |
|---|---|---|
| `plugin-cli/` | **None** | Zero changes. Talks to backend HTTP API (`/api/plugin-market/upload`); dev worker port 24057, unrelated to shell |
| `plugin-sdk/typescript/` | `Environment.platform: 'web'\|'desktop'` field only | Electron reports `'desktop'`; zero changes |
| `plugin-ui/vue/` | CSS class names / Playwright snapshot names only | Zero functional impact; **renaming would force regenerating 6 baseline PNGs — don't rename** |
| `plugin-spec/` schema | **None** | permissions enum is shell-neutral; zero changes |
| `FengYu-Plugin-Sdk/` (Java) | **None** | Pure stdio JSON-RPC; zero changes |
| `OfficialPlugins/` | excel plugin `platform === 'desktop'` check | Electron reports `'desktop'`; logic unchanged |

**Real coupling to fix (4 spots, in release chain + frontend bridge):**

1. **`scripts/release-workflow.test.mjs`** — hard-reads `desktop/src-tauri/tauri.conf.json` + regex-matches the `desktop:` workflow job. **Must rewrite** to assert `electron-builder.yml` shape + new electron workflow job. CI red/green prerequisite.

2. **`.github/workflows/fengyu-release.yml`** `desktop:` matrix job — entire Rust/cargo/tauri-cli/webkit-apt block replaced with electron-builder job (two variants: with/without JRE). `release:` job `needs:` and artifact globs follow.

3. **`.agents/skills/app-release/SKILL.md` + `docs-updater/SKILL.md`** — point at `desktop/src-tauri/Cargo.toml` + `tauri.conf.json` for version mirroring. Repoint to `desktop/electron/package.json` (electron-builder version carrier).

4. **Frontend bridge** (covered in §5) — `desktop.ts`/`env.d.ts`/`config.ts`/`vite.config.ts` + `package.json` remove `@tauri-apps/plugin-dialog`.

**Incidental fixes:**
- `frontend/src/views/About.vue` + i18n: "Desktop shell (Rust)" → "Desktop shell (Electron)".
- Vite dev proxy `24056` **unchanged** — that's the backend port, shell-agnostic.

**Historical planning docs** (`docs/superpowers/{plans,specs}/2026-07-09-tauri-*.md` etc., 4 files): kept as history, not edited — they're completed planning snapshots.

---

## 11. Docs sync (all in-branch — locked decision)

- `desktop/README.md` — rewritten: Electron dev/build, with/without-JRE, tray semantics.
- `docs/en/architecture/desktop.md` + `docs/zh/architecture/desktop.md` — rewritten end-to-end.
- `docs/{en,zh}/architecture/overview.md` — "Tauri 2.0 desktop shell" → "Electron desktop shell"; ASCII diagram updated.
- `docs/{en,zh}/{quickstart,features,design-system,index}.md`, `docs/{en,zh}/architecture/{backend,frontend}.md`, `docs/{en,zh}/plugins/{file-io,ui-microfrontend}.md`, `docs/{en,zh}/skills/index.md` — each tauri mention updated (~22 doc files total).
- `AGENTS.md` + root `README.md` — architecture description, `cargo tauri dev` → electron dev command, "Built with ... Tauri" → Electron.
- `.agents/skills/app-release/SKILL.md` + `docs-updater/SKILL.md` — version-mirror paths repointed (see §10.3).

---

## 12. Migration phases (high-level — detailed plan follows via writing-plans)

1. **Branch + scaffold** — cut branch, create `desktop/electron/` skeleton (package.json, tsconfig, electron-builder.yml), delete `desktop/src-tauri/`.
2. **Backend orchestration** — port `runtime-layout`/`spawn`/`handshake`/`supervisor`/`health`/`token` (§4). Unit tests alongside.
3. **Window + preload + frontend bridge** — `create-window`/`preload`, refactor `config.ts`/`desktop.ts`/`env.d.ts`, drop `@tauri-apps/plugin-dialog` (§5).
4. **Enhancements** — single-instance, tray, logger, auto-updater (§6). Adjust shutdown semantics (§7).
5. **Packaging + CI** — electron-builder.yml, JRE jlink CI step, rewrite `desktop:` workflow job + `release-workflow.test.mjs` (§8, §10.1–10.2).
6. **e2e + verification** — playwright-electron launch spec; local manual smoke on each platform.
7. **Docs sync** — all docs, README, AGENTS.md, 2 skills (§11).
8. **Final review** — full diff pass, confirm no stale `tauri`/`src-tauri`/`cargo`/`__TAURI__` references remain in active code/config/docs.

---

## 13. Open questions

None remaining — all decisions locked during brainstorming. The writing-plans step will decompose §12 into concrete, sequenced tasks.
