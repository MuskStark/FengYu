# Tauri → Electron Desktop Shell Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Tauri 2.0 desktop shell with an Electron 43.x shell, faithfully porting the backend orchestration lifecycle, adding single-instance/tray/file-logging/auto-update, refactoring the frontend bridge to `contextBridge`, and shipping with-JRE and without-JRE installer variants per platform.

**Architecture:** A TypeScript Electron main process spawns the bundled Java backend (`java -cp <jar> HeadlessLauncher`), reads `FENGYU_PORT` from stdout, polls `/api/health`, and supervises the SETUP→APP restart. A preload script exposes `window.fengyu` via `contextBridge` (token/apiBase as read-only snapshots, plus native file-picker IPC). electron-builder produces two variants per platform; CI generates a jlink-minimized JRE for the "with-JRE" variant.

**Tech Stack:** Electron 43.x (Node 24.17), TypeScript, electron-builder, electron-updater, electron-log, vitest (unit), @playwright/test (e2e).

## Global Constraints

- **Electron version:** `electron` pinned to `^43` (Node 24.17 runtime). Pin compatible versions of electron-builder, electron-updater, electron-log.
- **Java target:** JRE floor Java 21 (bytecode target). jlink built with JDK 21 LTS in CI.
- **Backend handshake is INVARIANT:** spawn `java -cp <jar> fan.summer.fengyu.HeadlessLauncher --port=<n> --token=<t> -Dfengyu.plugins.official-directory=<dir>`; read `FENGYU_PORT=<n>` from stdout; poll `GET /api/health` (header `X-FengYu-Token`); probe `GET /api/setup/status`. SETUP→APP signaled by exit code 0. Bind always `127.0.0.1`.
- **Default port:** `24056` (read back from stdout; OS-assigned fallback if taken).
- **Health polling timing:** 30s overall deadline, 300ms interval, 2s per-request timeout, HTTP 200 = ready. Cancellable.
- **Port-announce timeout:** 30s to read `FENGYU_PORT=<n>`; 200ms poll granularity; cancellable.
- **Token format:** `zf-{hex(nanos_since_epoch)}-{hex(pid)}` — identical to Rust `gen_token()`.
- **Commit convention:** conventional commits with emojis — `✨` feat, `🐛` fix, `♻️` refactor, `📝` docs, `⬆️` deps, `🔥` removal, `✅` test, `🔧` chore, `👷` ci.
- **No unrelated rewrites** — match surrounding style. Preserve user work outside the requested change.
- **Run from repo root** unless a step says otherwise. Prefer `./mvnw` over system Maven.

---

## File Structure

**Create (`desktop/electron/`):**
- `package.json` — deps + scripts (build/dev/test/test:e2e)
- `tsconfig.json` — main-process TS (commonjs, ES2022, node types, strict)
- `electron-builder.yml` — base packaging config
- `src/main.ts` — entry: whenReady → orchestrate → create window
- `src/backend/runtime-layout.ts` — resolve jar/plugins/jre paths (dev vs packaged)
- `src/backend/spawn.ts` — spawn java child + read FENGYU_PORT
- `src/backend/handshake.ts` — health poll + setup-mode probe
- `src/backend/supervisor.ts` — SETUP→APP restart watcher + `shouldRestartSetup`
- `src/backend/orchestrator.ts` — start_backend: spawn→health→setup
- `src/util/token.ts` — `genToken()` matching Rust format
- `src/util/health.ts` — fetch-based health polling primitive
- `src/window/create-window.ts` — BrowserWindow factory
- `src/window/preload.ts` — contextBridge `window.fengyu`
- `src/desktop/single-instance.ts` — requestSingleInstanceLock
- `src/desktop/tray.ts` — Tray + menu
- `src/desktop/logger.ts` — electron-log setup
- `src/updater/auto-updater.ts` — electron-updater wiring
- `src/ipc/dialog.ts` — `dialog:open` IPC handler
- `test/*.test.ts` — vitest unit tests
- `test/e2e/launch.spec.ts` — playwright-electron e2e
- `resources/icon.png`, `resources/icon.ico` — migrated icons

**Modify (frontend):**
- `frontend/src/api/config.ts` — read `window.fengyu?.apiBase()`/`token()`
- `frontend/src/mf/desktop.ts` — `isDesktop()` via `window.fengyu?.desktop`; pickFile/pickDirectory via `window.fengyu`
- `frontend/src/env.d.ts` — remove `__TAURI_*`, add `Window.fengyu`
- `frontend/package.json` — remove `@tauri-apps/plugin-dialog`

**Modify (release/CI):**
- `.github/workflows/fengyu-release.yml` — replace `desktop:` job (Rust→Electron), two variants
- `scripts/release-workflow.test.mjs` — rewrite assertions for electron-builder.yml + electron job

**Modify (docs):**
- `desktop/README.md`, `desktop/.gitignore`
- `docs/en/architecture/desktop.md`, `docs/zh/architecture/desktop.md` — full rewrite
- `docs/{en,zh}/architecture/overview.md` — Tauri→Electron
- ~20 other docs with incidental tauri mentions
- `AGENTS.md`, root `README.md`
- `.agents/skills/app-release/SKILL.md`, `.agents/skills/docs-updater/SKILL.md`

**Delete:**
- `desktop/src-tauri/` (entire directory)

---

## Task 1: Branch, scaffold, and remove Tauri

**Files:**
- Create: `desktop/electron/package.json`, `desktop/electron/tsconfig.json`, `desktop/electron/.gitignore`
- Modify: `desktop/.gitignore`, `desktop/README.md` (placeholder; full rewrite in Task 7)
- Delete: `desktop/src-tauri/` (entire directory)

**Interfaces:**
- Produces: the `desktop/electron/` project skeleton with installable deps; the git branch `4.0.0-electron`.

- [ ] **Step 1: Create the migration branch from 4.0.0-FengYu**

```bash
git checkout 4.0.0-FengYu
git checkout -b 4.0.0-electron
```

Verify: `git branch --show-current` prints `4.0.0-electron`.

- [ ] **Step 2: Delete the Tauri implementation**

```bash
git rm -r desktop/src-tauri
```

- [ ] **Step 3: Create the electron project skeleton directories**

```bash
mkdir -p desktop/electron/src/backend desktop/electron/src/window desktop/electron/src/desktop desktop/electron/src/updater desktop/electron/src/ipc desktop/electron/src/util desktop/electron/test/e2e desktop/electron/resources
```

- [ ] **Step 4: Write `desktop/electron/package.json`**

```json
{
  "name": "fengyu-desktop",
  "private": true,
  "version": "4.0.0-alpha.2",
  "description": "FengYu/Infinia desktop shell (Electron)",
  "main": "dist/main.js",
  "scripts": {
    "build:ts": "tsc -p tsconfig.json",
    "dev": "npm run build:ts && electron .",
    "build": "npm run build:ts && electron-builder",
    "build:win": "npm run build:ts && electron-builder --win",
    "build:mac": "npm run build:ts && electron-builder --mac",
    "build:linux": "npm run build:ts && electron-builder --linux",
    "test": "vitest run",
    "test:watch": "vitest",
    "test:e2e": "playwright test -c test/e2e/playwright.config.ts"
  },
  "devDependencies": {
    "@playwright/test": "^1.50.0",
    "@types/node": "^24.0.0",
    "electron": "^43.0.0",
    "electron-builder": "^26.0.0",
    "playwright": "^1.50.0",
    "typescript": "^5.7.0",
    "vitest": "^3.0.0"
  },
  "dependencies": {
    "electron-log": "^5.2.0",
    "electron-updater": "^6.3.9"
  }
}
```

Note: `version` mirrors the Maven `${revision}` (currently `4.0.0-alpha.2`); electron-builder reads it for the artifact version. The `app-release`/`docs-updater` skills will mirror version bumps here.

- [ ] **Step 5: Write `desktop/electron/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "commonjs",
    "moduleResolution": "node",
    "lib": ["ES2022"],
    "types": ["node"],
    "outDir": "dist",
    "rootDir": "src",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "forceConsistentCasingInFileNames": true,
    "resolveJsonModule": true,
    "sourceMap": true,
    "declaration": false
  },
  "include": ["src/**/*.ts"],
  "exclude": ["node_modules", "dist", "test"]
}
```

- [ ] **Step 6: Write `desktop/electron/.gitignore`**

```
node_modules/
dist/
out/
resources/binaries/
resources/jre/
*.log
.playwright/
```

- [ ] **Step 7: Update `desktop/.gitignore`**

The existing `desktop/.gitignore` references `src-tauri/` paths. Replace its contents with:

```
electron/node_modules/
electron/dist/
electron/out/
electron/resources/binaries/
electron/resources/jre/
```

- [ ] **Step 8: Migrate icons from the deleted src-tauri**

The icons were deleted with `desktop/src-tauri/`. Recover them from git and place under the new resources dir:

```bash
git checkout 4.0.0-FengYu -- desktop/src-tauri/icons/icon.png desktop/src-tauri/icons/icon.ico
mkdir -p desktop/electron/resources
mv desktop/src-tauri/icons/icon.png desktop/electron/resources/icon.png
mv desktop/src-tauri/icons/icon.ico desktop/electron/resources/icon.ico
# also grab the larger pngs if present for sharper rendering
git checkout 4.0.0-FengYu -- desktop/src-tauri/icons/128x128.png desktop/src-tauri/icons/32x32.png 2>/dev/null || true
mv desktop/src-tauri/icons/128x128.png desktop/electron/resources/icon-128.png 2>/dev/null || true
mv desktop/src-tauri/icons/32x32.png desktop/electron/resources/icon-32.png 2>/dev/null || true
# remove the now-empty recovered icons dir
rm -rf desktop/src-tauri
```

- [ ] **Step 9: Write a placeholder `desktop/README.md`**

```markdown
# FengYu Desktop (Electron)

The desktop shell is being migrated from Tauri to Electron. Full developer docs
will land in the final task of the migration.

- Entry point: `desktop/electron/src/main.ts`
- Build config: `desktop/electron/electron-builder.yml`
```

- [ ] **Step 10: Verify the skeleton**

```bash
cd desktop/electron && npm install
```

Expected: install succeeds (electron 43.x downloads). `npm run build:ts` should fail with "no main.ts yet" or succeed with an empty emit — that's fine; Task 2 adds `main.ts`.

- [ ] **Step 11: Commit**

```bash
git add desktop/
git commit -m "🔥 feat(desktop): remove Tauri shell, scaffold Electron project"
```

---

## Task 2: Backend orchestration + unit tests

Faithful port of `desktop/src-tauri/src/main.rs` prod lifecycle. Each module is decoupled from `electron` so it's unit-testable in isolation (deps like `fetch` and `now()` are injected).

**Files:**
- Create: `desktop/electron/src/util/token.ts`, `desktop/electron/src/util/health.ts`, `desktop/electron/src/backend/runtime-layout.ts`, `desktop/electron/src/backend/spawn.ts`, `desktop/electron/src/backend/handshake.ts`, `desktop/electron/src/backend/supervisor.ts`, `desktop/electron/src/backend/orchestrator.ts`
- Test: `desktop/electron/test/token.test.ts`, `desktop/electron/test/runtime-layout.test.ts`, `desktop/electron/test/supervisor.test.ts`, `desktop/electron/test/handshake.test.ts`, `desktop/electron/test/health.test.ts`
- Create: `desktop/electron/vitest.config.ts`

**Interfaces:**
- `genToken(): string` — `zf-{hex}-{hex}`
- `RuntimeLayout { jar: string; plugins: string; jre?: string }`
- `resolveLayout(isPackaged: boolean, resourcesPath: string, env: Record<string,string|undefined>): RuntimeLayout`
- `shouldRestartSetup(shuttingDown: boolean, exitCode: number | null): boolean`
- `parseFengyuPort(line: string): number | null`
- `detectSetupMode(body: string): boolean`
- `pollHealth(opts: { port, token, fetchImpl?, now?, sleep?, shouldCancel?, deadlineMs?, intervalMs?, requestTimeoutMs? }): Promise<void>`
- `BackendChild` — wraps a `ChildProcess`-like with `.kill()` + `.pid` + stdout
- `spawnBackend(opts): Promise<{ child, port }>`
- `startBackend(opts): Promise<{ child, port, setupMode }>`

### Task 2a: token util + test

- [ ] **Step 1: Write `desktop/electron/test/token.test.ts`**

```ts
import { describe, it, expect } from 'vitest'
import { genToken } from '../src/util/token'

describe('genToken', () => {
  it('matches the zf-{hex}-{hex} format', () => {
    expect(genToken()).toMatch(/^zf-[0-9a-f]+-[0-9a-f]+$/)
  })

  it('changes across calls (per-launch variance)', () => {
    expect(genToken()).not.toBe(genToken())
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd desktop/electron && npx vitest run test/token.test.ts
```
Expected: FAIL — `Cannot find module '../src/util/token'`.

- [ ] **Step 3: Write `desktop/electron/src/util/token.ts`**

Mirrors Rust `gen_token()`:

```ts
/**
 * Per-launch auth token. Format: `zf-{hex(nanos)}-{hex(pid)}`.
 *
 * Mirrors the Rust `gen_token()` exactly so backend token validation is unchanged.
 * No uuid dependency — just wall-clock nanos + process id, both hex-encoded.
 */
export function genToken(): string {
  const nanos = BigInt(Date.now()) * 1_000_000n
  const pid = BigInt(process.pid)
  return `zf-${nanos.toString(16)}-${pid.toString(16)}`
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
npx vitest run test/token.test.ts
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add desktop/electron/src/util/token.ts desktop/electron/test/token.test.ts
git commit -m "✨ feat(desktop): port token generator from Rust"
```

### Task 2b: runtime-layout + test

- [ ] **Step 1: Write `desktop/electron/test/runtime-layout.test.ts`**

Mirrors the Rust `runtime_layout_uses_tauri_resource_directory` test:

```ts
import { describe, it, expect } from 'vitest'
import { resolveLayout } from '../src/backend/runtime-layout'

describe('resolveLayout', () => {
  it('resolves jar + plugins under the packaged resource dir', () => {
    const layout = resolveLayout(true, '/app/resources', {})
    expect(layout.jar).toBe('/app/resources/binaries/FengYu.jar')
    expect(layout.plugins).toBe('/app/resources/plugins')
    expect(layout.jre).toBe('/app/resources/jre/bin/java')
  })

  it('resolves jar + plugins from FENGYU_JAR env in dev', () => {
    const layout = resolveLayout(false, '/unused', {
      FENGYU_JAR: '/local/FengYu.jar',
      FENGYU_PLUGINS: '/local/plugins',
    })
    expect(layout.jar).toBe('/local/FengYu.jar')
    expect(layout.plugins).toBe('/local/plugins')
    expect(layout.jre).toBeUndefined()
  })

  it('appends .exe to the bundled java on Windows', () => {
    const originalPlatform = Object.getOwnPropertyDescriptor(process, 'platform')
    Object.defineProperty(process, 'platform', { value: 'win32' })
    try {
      const layout = resolveLayout(true, 'C:\\app\\resources', {})
      expect(layout.jre).toBe('C:\\app\\resources\\jre\\bin\\java.exe')
    } finally {
      if (originalPlatform) Object.defineProperty(process, 'platform', originalPlatform)
    }
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
npx vitest run test/runtime-layout.test.ts
```
Expected: FAIL — module not found.

- [ ] **Step 3: Write `desktop/electron/src/backend/runtime-layout.ts`**

```ts
/**
 * Where the bundled runtime assets live.
 *
 * Packaged: under `process.resourcesPath` (electron-builder `extraResources`):
 *   <resources>/binaries/FengYu.jar, <resources>/plugins/, <resources>/jre/bin/java (with-JRE variant).
 * Dev: resolved from FENGYU_JAR / FENGYU_PLUGINS env (the backend runs externally on :24056).
 */
export interface RuntimeLayout {
  /** Absolute path to the shaded FengYu jar. */
  jar: string
  /** Absolute path to the official .fyp plugins directory. */
  plugins: string
  /** Absolute path to the bundled java binary (with-JRE variant only); undefined when relying on PATH. */
  jre?: string
}

import { join } from 'node:path'

/**
 * Resolve the runtime layout.
 *
 * @param isPackaged `app.isPackaged` in prod; false in dev.
 * @param resourcesPath `process.resourcesPath` (packaged only).
 * @param env process.env (or a subset) — dev reads FENGYU_JAR / FENGYU_PLUGINS.
 */
export function resolveLayout(
  isPackaged: boolean,
  resourcesPath: string,
  env: Record<string, string | undefined>,
): RuntimeLayout {
  if (isPackaged) {
    const javaName = process.platform === 'win32' ? 'java.exe' : 'java'
    return {
      jar: join(resourcesPath, 'binaries', 'FengYu.jar'),
      plugins: join(resourcesPath, 'plugins'),
      jre: join(resourcesPath, 'jre', 'bin', javaName),
    }
  }
  const jar = env.FENGYU_JAR
  const plugins = env.FENGYU_PLUGINS ?? ''
  if (!jar) {
    throw new Error(
      'Dev mode requires FENGYU_JAR (path to the shaded jar). ' +
        'Run `mvn -pl FengYu -am package -DskipTests` and set FENGYU_JAR to the resulting jar, ' +
        'or start the backend externally on :24056 and omit the jar.',
    )
  }
  return { jar, plugins, jre: undefined }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
npx vitest run test/runtime-layout.test.ts
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add desktop/electron/src/backend/runtime-layout.ts desktop/electron/test/runtime-layout.test.ts
git commit -m "✨ feat(desktop): port runtime layout resolution"
```

### Task 2c: supervisor pure logic + test

- [ ] **Step 1: Write `desktop/electron/test/supervisor.test.ts`**

Mirrors the three Rust `should_restart_setup` tests:

```ts
import { describe, it, expect } from 'vitest'
import { shouldRestartSetup, StartupAction, startupAction } from '../src/backend/supervisor'

describe('shouldRestartSetup', () => {
  it('shutdown prevents a setup restart', () => {
    expect(shouldRestartSetup(true, 0)).toBe(false)
  })

  it('restarts only on exit code 0 while running', () => {
    expect(shouldRestartSetup(false, 0)).toBe(true)
    expect(shouldRestartSetup(false, 1)).toBe(false)
    expect(shouldRestartSetup(false, null)).toBe(false)
  })
})

describe('startupAction', () => {
  it('APP mode shows the window without supervision', () => {
    expect(startupAction(false, 24056)).toBe(StartupAction.ShowWindow)
  })

  it('SETUP mode shows the window and supervises the same port', () => {
    expect(startupAction(true, 43123)).toEqual(StartupAction.ShowWindowAndSupervise)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
npx vitest run test/supervisor.test.ts
```
Expected: FAIL — module not found.

- [ ] **Step 3: Write `desktop/electron/src/backend/supervisor.ts`**

The `shouldRestartSetup` + `startupAction` are pure. The runtime watcher (`superviseSetupRestart`) is in Task 2f's orchestrator; keep this file pure + export the enum + the runtime watcher together.

```ts
import { spawn, type ChildProcess } from 'node:child_process'

/** What the orchestrator does after the backend first boots. */
export enum StartupAction {
  ShowWindow = 'ShowWindow',
  ShowWindowAndSupervise = 'ShowWindowAndSupervise',
}

/**
 * Decide whether to show the window, and whether to supervise a SETUP→APP restart.
 * Mirrors Rust `startup_action`.
 */
export function startupAction(setupMode: boolean, _port: number): StartupAction {
  return setupMode ? StartupAction.ShowWindowAndSupervise : StartupAction.ShowWindow
}

/**
 * Should the supervisor respawn the backend after it exits during SETUP?
 * Only when not shutting down AND the exit code is exactly 0 (Java SETUP_DONE signal).
 * Mirrors Rust `should_restart_setup`.
 */
export function shouldRestartSetup(shuttingDown: boolean, exitCode: number | null): boolean {
  return !shuttingDown && exitCode === 0
}

/** A handle to a spawned backend: the child + how to kill it. */
export interface BackendChild {
  process: ChildProcess
  kill(): void
}

/**
 * Watch a SETUP-mode backend child; on exit code 0, respawn it into APP mode.
 *
 * @param getChild  returns the current BackendChild (the supervisor swaps it after respawn)
 * @param setChild  installs a new BackendChild (called after a successful restart)
 * @param restart   re-runs startBackend (spawn→health→setup) into APP mode
 * @param isShuttingDown returns true once the app is quitting
 * @returns a `stop()` function that detaches the watcher (idempotent)
 */
export interface SupervisorConfig {
  getChild: () => BackendChild | null
  setChild: (child: BackendChild | null) => void
  restart: () => Promise<{ child: BackendChild; port: number; setupMode: boolean }>
  expectedPort: number
  isShuttingDown: () => boolean
  onFatal: (message: string) => void
}

export function superviseSetupRestart(cfg: SupervisorConfig): () => void {
  let stopped = false

  const watch = () => {
    const current = cfg.getChild()
    if (!current || stopped) return
    const proc = current.process
    proc.once('exit', (code, _signal) => {
      if (stopped) return
      if (!shouldRestartSetup(cfg.isShuttingDown(), code)) {
        if (!cfg.isShuttingDown()) {
          cfg.onFatal(`SETUP backend exited with code ${code}; not restarting`)
        }
        return
      }
      console.log('[desktop] setup complete; restarting backend into APP mode')
      cfg.restart()
        .then((restarted) => {
          if (restarted.port !== cfg.expectedPort) {
            restarted.child.kill()
            cfg.onFatal(
              `restarted backend moved from port ${cfg.expectedPort} to ${restarted.port}; ` +
                'the webview endpoint cannot change',
            )
            return
          }
          if (restarted.setupMode) {
            restarted.child.kill()
            cfg.onFatal('backend remained in SETUP mode after successful initialization')
            return
          }
          if (cfg.isShuttingDown()) {
            restarted.child.kill()
          } else {
            cfg.setChild(restarted.child)
            console.log(`[desktop] backend restarted in APP mode on port ${cfg.expectedPort}`)
            watch() // re-arm for any future exits (defensive; APP mode shouldn't exit)
          }
        })
        .catch((err) => cfg.onFatal(`failed to restart backend after setup: ${err}`))
    })
  }

  watch()
  return () => {
    stopped = true
  }
}

// re-export spawn for the orchestrator
export { spawn }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
npx vitest run test/supervisor.test.ts
```
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add desktop/electron/src/backend/supervisor.ts desktop/electron/test/supervisor.test.ts
git commit -m "✨ feat(desktop): port SETUP→APP supervisor logic"
```

### Task 2d: handshake (port parse + setup detect) + health polling primitive + tests

- [ ] **Step 1: Write `desktop/electron/test/handshake.test.ts`**

```ts
import { describe, it, expect } from 'vitest'
import { parseFengyuPort, detectSetupMode } from '../src/backend/handshake'

describe('parseFengyuPort', () => {
  it('extracts the port from a FENGYU_PORT= line', () => {
    expect(parseFengyuPort('FENGYU_PORT=24056')).toBe(24056)
  })
  it('trims surrounding whitespace', () => {
    expect(parseFengyuPort('  FENGYU_PORT=43123  ')).toBe(43123)
  })
  it('ignores unrelated stdout lines', () => {
    expect(parseFengyuPort('[main] INFO  starting Tomcat...')).toBeNull()
  })
  it('returns null for a malformed port value', () => {
    expect(parseFengyuPort('FENGYU_PORT=notanumber')).toBeNull()
  })
})

describe('detectSetupMode', () => {
  it('detects compact initialized:false', () => {
    expect(detectSetupMode('{"initialized":false,"mode":"SETUP"}')).toBe(true)
  })
  it('detects spaced initialized: false', () => {
    expect(detectSetupMode('{"initialized": false}')).toBe(true)
  })
  it('APP mode body is not SETUP', () => {
    expect(detectSetupMode('{"initialized":true,"mode":"APP"}')).toBe(false)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
npx vitest run test/handshake.test.ts
```
Expected: FAIL — module not found.

- [ ] **Step 3: Write `desktop/electron/src/backend/handshake.ts`**

Pure parsers (mirrors Rust `strip_prefix("FENGYU_PORT=")` + the `"initialized":false` body scan):

```ts
/**
 * Parse a `FENGYU_PORT=<n>` line from the backend's stdout.
 * Returns the port, or null if the line isn't a port announcement.
 * Mirrors Rust `line.strip_prefix("FENGYU_PORT=")` + `parse::<u16>()`.
 */
export function parseFengyuPort(line: string): number | null {
  const trimmed = line.trim()
  const prefix = 'FENGYU_PORT='
  if (!trimmed.startsWith(prefix)) return null
  const rest = trimmed.slice(prefix.length).trim()
  const n = Number(rest)
  if (!Number.isInteger(n) || n < 0 || n > 65535) return null
  return n
}

/**
 * Crude SETUP-mode detection from the /api/setup/status body.
 * Mirrors Rust `check_setup_mode`: SETUP if `"initialized":false` (compact or spaced).
 */
export function detectSetupMode(body: string): boolean {
  return body.includes('"initialized":false') || body.includes('"initialized": false')
}
```

- [ ] **Step 4: Write `desktop/electron/test/health.test.ts`**

```ts
import { describe, it, expect, vi } from 'vitest'
import { pollHealth } from '../src/util/health'

describe('pollHealth', () => {
  it('returns ok on HTTP 200', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    await expect(
      pollHealth({
        port: 24056,
        token: 't',
        fetchImpl: fetchImpl as unknown as typeof fetch,
      }),
    ).resolves.toBeUndefined()
    expect(fetchImpl).toHaveBeenCalledOnce()
  })

  it('retries on non-200 then succeeds', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 503 })
      .mockResolvedValueOnce({ ok: true, status: 200 })
    const sleep = vi.fn().mockResolvedValue(undefined)
    await pollHealth({
      port: 24056,
      token: 't',
      fetchImpl: fetchImpl as unknown as typeof fetch,
      sleep,
      intervalMs: 0,
    })
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })

  it('throws on timeout', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: false, status: 503 })
    const sleep = vi.fn().mockResolvedValue(undefined)
    await expect(
      pollHealth({
        port: 24056,
        token: 't',
        fetchImpl: fetchImpl as unknown as typeof fetch,
        sleep,
        intervalMs: 0,
        deadlineMs: 0, // immediate deadline
      }),
    ).rejects.toThrow(/timed out/)
  })

  it('aborts when shouldCancel returns true', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: false, status: 503 })
    await expect(
      pollHealth({
        port: 24056,
        token: 't',
        fetchImpl: fetchImpl as unknown as typeof fetch,
        shouldCancel: () => true,
      }),
    ).rejects.toThrow(/cancel/)
  })
})
```

- [ ] **Step 5: Write `desktop/electron/src/util/health.ts`**

Injectable `fetchImpl`/`sleep`/`shouldCancel` so it's testable without a live backend:

```ts
/**
 * Poll GET /api/health until 200 or the deadline.
 *
 * Timing mirrors Rust `wait_for_health`: 30s overall, 300ms interval, 2s per-request,
 * HTTP 200 = ready. Cancellable.
 */

export interface PollHealthOptions {
  port: number
  token: string
  fetchImpl?: typeof fetch
  /** Default: setTimeout-based. */
  sleep?: (ms: number) => Promise<void>
  shouldCancel?: () => boolean
  deadlineMs?: number
  intervalMs?: number
  requestTimeoutMs?: number
}

const defaultSleep = (ms: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, ms))

export async function pollHealth(opts: PollHealthOptions): Promise<void> {
  const {
    port,
    token,
    fetchImpl = fetch,
    sleep = defaultSleep,
    shouldCancel = () => false,
    deadlineMs = 30_000,
    intervalMs = 300,
    requestTimeoutMs = 2_000,
  } = opts

  const url = `http://127.0.0.1:${port}/api/health`
  const deadline = Date.now() + deadlineMs
  while (Date.now() < deadline) {
    if (shouldCancel()) throw new Error('backend health check cancelled')
    try {
      const controller = new AbortController()
      const timer = setTimeout(() => controller.abort(), requestTimeoutMs)
      const resp = await fetchImpl(url, {
        headers: { 'X-FengYu-Token': token },
        signal: controller.signal,
      })
      clearTimeout(timer)
      if (resp.status === 200) return
    } catch {
      // network error / abort → keep polling until deadline
    }
    await sleep(intervalMs)
  }
  if (shouldCancel()) throw new Error('backend health check cancelled')
  throw new Error('backend health check timed out')
}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
npx vitest run test/handshake.test.ts test/health.test.ts
```
Expected: PASS (handshake 7, health 4).

- [ ] **Step 7: Commit**

```bash
git add desktop/electron/src/backend/handshake.ts desktop/electron/test/handshake.test.ts desktop/electron/src/util/health.ts desktop/electron/test/health.test.ts
git commit -m "✨ feat(desktop): port port-parse, setup-detect, and health polling"
```

### Task 2e: spawn + orchestrator

- [ ] **Step 1: Write `desktop/electron/src/backend/spawn.ts`**

Port of Rust `spawn_backend` + `start_backend`. Uses Node `child_process.spawn`; reads `FENGYU_PORT` line-by-line from stdout with a 30s deadline, cancellable.

```ts
import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync } from 'node:fs'
import { resolveJava } from './runtime-layout-helpers'
import type { RuntimeLayout } from './runtime-layout'
import { parseFengyuPort } from './handshake'
import type { BackendChild } from './supervisor'

export interface SpawnOptions {
  layout: RuntimeLayout
  token: string
  requestedPort: number
  shouldCancel?: () => boolean
  onLine?: (line: string) => void
  deadlineMs?: number
  pollIntervalMs?: number
}

export interface SpawnedBackend {
  child: BackendChild
  port: number
}

/**
 * Spawn the Java backend and read the bound port from stdout (`FENGYU_PORT=<n>`).
 * Mirrors Rust `spawn_backend`. 30s deadline, cancellable.
 *
 * The Java executable is resolved by `resolveJava`: bundled jre/bin/java for the
 * with-JRE variant, else PATH lookup (caller handles the not-found error).
 */
export async function spawnBackend(opts: SpawnOptions): Promise<SpawnedBackend> {
  const { layout, token, requestedPort, shouldCancel = () => false } = opts
  const deadlineMs = opts.deadlineMs ?? 30_000
  const pollIntervalMs = opts.pollIntervalMs ?? 200

  if (!existsSync(layout.jar)) {
    throw new Error(
      `FengYu jar not found at ${layout.jar}. Build it and stage it at desktop/electron/resources/binaries/FengYu.jar (see desktop/README.md).`,
    )
  }
  const javaBin = resolveJava(layout)

  const args = [
    `-Dfengyu.plugins.official-directory=${layout.plugins}`,
    '-cp',
    layout.jar,
    'fan.summer.fengyu.HeadlessLauncher',
    `--port=${requestedPort}`,
    `--token=${token}`,
  ]

  const proc = spawn(javaBin, args, {
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  })

  const child: BackendChild = {
    process: proc,
    kill() {
      if (!proc.killed) {
        proc.kill('SIGTERM')
        // SIGKILL fallback after grace
        setTimeout(() => {
          if (!proc.killed) proc.kill('SIGKILL')
        }, 5_000)
      }
    },
  }

  // Reject fast if java couldn't even start (e.g. ENOENT — wrong PATH).
  await new Promise<void>((resolve, reject) => {
    proc.once('error', reject)
    setImmediate(resolve) // give spawn a tick to emit error
  }).catch((err) => {
    throw new Error(`failed to spawn java: ${err.message}`)
  })

  const port = await readPort(proc, { deadlineMs, pollIntervalMs, shouldCancel, onLine: opts.onLine })

  return { child, port }
}

async function readPort(
  proc: ChildProcess,
  opts: {
    deadlineMs: number
    pollIntervalMs: number
    shouldCancel: () => boolean
    onLine?: (line: string) => void
  },
): Promise<number> {
  return new Promise<number>((resolve, reject) => {
    const { deadlineMs, pollIntervalMs, shouldCancel, onLine } = opts
    const deadline = Date.now() + deadlineMs
    let buffer = ''

    const onStdout = (chunk: Buffer) => {
      buffer += chunk.toString('utf8')
      let nl: number
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl)
        buffer = buffer.slice(nl + 1)
        onLine?.(line)
        const port = parseFengyuPort(line)
        if (port !== null) {
          cleanup()
          resolve(port)
        }
      }
    }
    const onStdoutClose = () => {
      cleanup()
      reject(new Error('backend exited before reporting FENGYU_PORT'))
    }
    const poll = setInterval(() => {
      if (shouldCancel()) {
        cleanup()
        reject(new Error('backend startup cancelled'))
      } else if (Date.now() >= deadline) {
        cleanup()
        reject(new Error('backend did not report FENGYU_PORT within 30s'))
      }
    }, pollIntervalMs)
    const cleanup = () => {
      proc.stdout?.off('data', onStdout)
      proc.stdout?.off('end', onStdoutClose)
      clearInterval(poll)
    }
    proc.stdout?.on('data', onStdout)
    proc.stdout?.on('end', onStdoutClose)
  })
}
```

- [ ] **Step 2: Write `desktop/electron/src/backend/runtime-layout-helpers.ts`**

`resolveJava` + `resolveJavaOrFail` (the without-JRE "missing java → error exit" path). Kept separate from `runtime-layout.ts` so the pure path resolver stays side-effect-free.

```ts
import { existsSync } from 'node:fs'
import { lookup } from 'node:dns/promises'
import type { RuntimeLayout } from './runtime-layout'

/**
 * Resolve the Java executable to run the backend with.
 * Order: bundled jre/bin/java (with-JRE variant) → PATH lookup (without-JRE).
 * Returns the absolute path, or 'java' as a PATH fallback (caller verifies via spawn error).
 */
export function resolveJava(layout: RuntimeLayout): string {
  if (layout.jre && existsSync(layout.jre)) {
    return layout.jre
  }
  // Defer to PATH; spawn() will surface ENOENT if java isn't installed.
  return 'java'
}
```

Note: PATH resolution is delegated to `spawn` (Node uses the OS PATH). The caller (`orchestrator`) catches the spawn `ENOENT` and routes to the error-exit path. We do NOT pre-flight with `which`/`where` because the spawn error is authoritative and cross-platform.

- [ ] **Step 3: Write `desktop/electron/src/backend/orchestrator.ts`**

Port of Rust `start_backend` (spawn → health → setup probe):

```ts
import { spawnBackend } from './spawn'
import { pollHealth } from '../util/health'
import { detectSetupMode } from './handshake'
import type { RuntimeLayout } from './runtime-layout'
import type { BackendChild } from './supervisor'

export interface StartedBackend {
  child: BackendChild
  port: number
  setupMode: boolean
}

export interface StartBackendOptions {
  layout: RuntimeLayout
  token: string
  requestedPort: number
  shouldCancel?: () => boolean
  fetchImpl?: typeof fetch
  onBackendLine?: (line: string) => void
}

/**
 * Spawn the backend, wait for /api/health, probe SETUP mode.
 * Mirrors Rust `start_backend`. Any failure terminates the child and throws.
 */
export async function startBackend(opts: StartBackendOptions): Promise<StartedBackend> {
  const { layout, token, requestedPort } = opts
  const { child, port } = await spawnBackend({
    layout,
    token,
    requestedPort,
    shouldCancel: opts.shouldCancel,
    onLine: opts.onBackendLine,
  })

  try {
    await pollHealth({ port, token, shouldCancel: opts.shouldCancel, fetchImpl: opts.fetchImpl })
  } catch (err) {
    child.kill()
    throw err
  }

  const setupMode = await checkSetupMode(port, token, opts.fetchImpl).catch((err) => {
    child.kill()
    throw err
  })

  if (opts.shouldCancel?.()) {
    child.kill()
    throw new Error('backend startup cancelled')
  }

  return { child, port, setupMode }
}

async function checkSetupMode(
  port: number,
  token: string,
  fetchImpl: typeof fetch = fetch,
): Promise<boolean> {
  const url = `http://127.0.0.1:${port}/api/setup/status`
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 2_000)
  try {
    const resp = await fetchImpl(url, {
      headers: { 'X-FengYu-Token': token },
      signal: controller.signal,
    })
    if (!resp.ok) {
      throw new Error(`setup status request failed: HTTP ${resp.status}`)
    }
    const body = await resp.text()
    return detectSetupMode(body)
  } finally {
    clearTimeout(timer)
  }
}
```

- [ ] **Step 4: Add `vitest.config.ts`**

```ts
import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    exclude: ['test/e2e/**'],
    globals: false,
  },
})
```

- [ ] **Step 5: Verify the whole suite still passes + type-checks**

```bash
npx vitest run
npx tsc -p tsconfig.json --noEmit
```
Expected: all unit tests pass; tsc reports no errors (it will warn `src/main.ts` not found yet — add an empty placeholder if tsc errors on missing entry; Task 3 fills it).

- [ ] **Step 6: Commit**

```bash
git add desktop/electron/src/backend/spawn.ts desktop/electron/src/backend/runtime-layout-helpers.ts desktop/electron/src/backend/orchestrator.ts desktop/electron/vitest.config.ts
git commit -m "✨ feat(desktop): port backend spawn + orchestration lifecycle"
```

---

## Task 3: Window, preload, and frontend bridge refactor

**Files:**
- Create: `desktop/electron/src/window/create-window.ts`, `desktop/electron/src/window/preload.ts`, `desktop/electron/src/main.ts`, `desktop/electron/src/ipc/dialog.ts`
- Modify: `frontend/src/api/config.ts`, `frontend/src/mf/desktop.ts`, `frontend/src/env.d.ts`, `frontend/package.json`

**Interfaces:**
- `window.fengyu` shape (preload): `{ apiBase(): string; token(): string; pickFile(opts?): Promise<string|null>; pickDirectory(): Promise<string|null>; desktop: true }`
- IPC channel: `dialog:open` → `{ directory: boolean; filters?: ... }` → `string | null`
- `createMainWindow(opts: { apiBase: string; token: string; onClose: () => void }): BrowserWindow`

### Task 3a: window + preload + main entry

- [ ] **Step 1: Write `desktop/electron/src/window/preload.ts`**

```ts
import { contextBridge, ipcRenderer } from 'electron'

/**
 * Bridge between the main process and the renderer (the Vue SPA).
 *
 * `apiBase`/`token` are read-only snapshots captured at startup — the SPA fetches
 * the backend directly over loopback (SSE, uploads, plugin host all need native
 * fetch/EventSource/FormData, which IPC can't carry). The token is per-launch and
 * loopback-only, so exposing it as a snapshot is low-risk.
 *
 * `pickFile`/`pickDirectory` go through IPC to use Electron's native dialog.
 */
export function installFengyuBridge(apiBase: string, token: string): void {
  contextBridge.exposeInMainWorld('fengyu', {
    apiBase: () => apiBase,
    token: () => token,
    desktop: true,
    pickFile: (filters?: { name: string; extensions: string[] }[]) =>
      ipcRenderer.invoke('dialog:open', { directory: false, filters }),
    pickDirectory: () => ipcRenderer.invoke('dialog:open', { directory: true }),
  })
}
```

- [ ] **Step 2: Write `desktop/electron/src/ipc/dialog.ts`**

```ts
import { ipcMain, dialog, BrowserWindow } from 'electron'

/**
 * Register the `dialog:open` IPC handler. Returns the chosen path or null.
 * Filters shape matches the SDK's PluginContext.desktop.pickFile signature.
 */
export function registerDialogIpc(): void {
  ipcMain.handle('dialog:open', async (event, opts: { directory: boolean; filters?: { name: string; extensions: string[] }[] }) => {
    const win = BrowserWindow.fromWebContents(event.sender) ?? undefined
    const result = await dialog.showOpenDialog(win!, {
      properties: opts.directory ? ['openDirectory'] : ['openFile'],
      filters: opts.filters?.map((f) => ({ name: f.name, extensions: f.extensions })),
    })
    if (result.canceled || result.filePaths.length === 0) return null
    return result.filePaths[0]
  })
}
```

- [ ] **Step 3: Write `desktop/electron/src/window/create-window.ts`**

```ts
import { BrowserWindow } from 'electron'
import { join } from 'node:path'
import { installFengyuBridge } from './preload'

export interface CreateWindowOptions {
  apiBase: string
  token: string
  /** Called when the user clicks the close button (we hide-to-tray instead of closing). */
  onHideToTray: () => void
  isDev: boolean
}

/**
 * Create the main BrowserWindow. 1280×820, min 960×640, matches the Rust window.
 * contextIsolation + sandbox on; nodeIntegration off — standard secure posture.
 */
export function createMainWindow(opts: CreateWindowOptions): BrowserWindow {
  const win = new BrowserWindow({
    title: 'FengYu',
    width: 1280,
    height: 820,
    minWidth: 960,
    minHeight: 640,
    resizable: true,
    webPreferences: {
      preload: join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })

  // The preload runs before page scripts; capture apiBase/token at creation.
  // (installFengyuBridge is invoked by the compiled preload entry — see preload-entry below.)
  // Because preload must be a standalone file referenced by webPreferences.preload,
  // the bridge is installed there directly. We pass the values via process.env at launch.

  // Hide-to-tray instead of closing (Task 4 wires the tray).
  win.on('close', (e) => {
    e.preventDefault()
    opts.onHideToTray()
    win.hide()
  })

  if (opts.isDev) {
    void win.loadURL('http://localhost:5173')
  } else {
    void win.loadFile(join(__dirname, '../../frontend-dist/index.html'))
  }
  return win
}
```

**Important note on preload wiring:** Because `webPreferences.preload` points at a single compiled JS file, the bridge values must reach the preload via a channel that exists before the renderer loads. Use `process.env.FENGYU_*` set in main before window creation. Add a tiny preload entry:

- [ ] **Step 4: Rewrite preload as the standalone entry**

Replace the `preload.ts` content from Step 1 with a single self-contained entry (the function form above is inlined):

```ts
import { contextBridge, ipcRenderer } from 'electron'

const apiBase = process.env.FENGYU_API_BASE ?? ''
const token = process.env.FENGYU_TOKEN ?? ''

contextBridge.exposeInMainWorld('fengyu', {
  apiBase: () => apiBase,
  token: () => token,
  desktop: true,
  pickFile: (filters?: { name: string; extensions: string[] }[]) =>
    ipcRenderer.invoke('dialog:open', { directory: false, filters }),
  pickDirectory: () => ipcRenderer.invoke('dialog:open', { directory: true }),
})
```

And drop the `installFengyuBridge` import from `create-window.ts` (the env-var handoff replaces it). Remove the `installFengyuBridge` call comment from `create-window.ts`.

- [ ] **Step 5: Write `desktop/electron/src/main.ts` (initial — orchestration only; enhancements land in Task 4)**

```ts
// Prevents an extra console window on Windows in release builds.
if (process.platform === 'win32') app.setAppUserModelId('fan.summer.fengyu')

import { app, dialog } from 'electron'
import { resolveLayout } from './backend/runtime-layout'
import { genToken } from './util/token'
import { startBackend } from './backend/orchestrator'
import { startupAction, StartupAction, superviseSetupRestart } from './backend/supervisor'
import { registerDialogIpc } from './ipc/dialog'
import { createMainWindow } from './window/create-window'

let backendChild: import('./backend/supervisor').BackendChild | null = null
let shuttingDown = false

async function bootstrap(): Promise<void> {
  registerDialogIpc()

  const isPackaged = app.isPackaged
  const layout = resolveLayout(isPackaged, process.resourcesPath, process.env)

  const token = genToken()
  process.env.FENGYU_TOKEN = token
  process.env.FENGYU_API_BASE = '' // set after we know the port

  const onBackendLine = (line: string) => console.log(`[backend] ${line}`)

  let started
  try {
    started = await startBackend({
      layout,
      token,
      requestedPort: 24056,
      onBackendLine,
      shouldCancel: () => shuttingDown,
    })
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    if (/spawn.*java|ENOENT/i.test(msg)) {
      dialog.showErrorBox(
        'Java not found',
        'FengYu requires Java 21+ on your PATH. Please install a JRE (https://adoptium.net) ' +
          'or use the Infinia build that bundles a JRE.',
      )
    } else {
      dialog.showErrorBox('Failed to start backend', msg)
    }
    app.quit()
    return
  }

  const apiBase = `http://127.0.0.1:${started.port}`
  process.env.FENGYU_API_BASE = apiBase
  backendChild = started.child

  const action = startupAction(started.setupMode, started.port)

  if (action === StartupAction.ShowWindowAndSupervise) {
    console.log('[desktop] backend in SETUP mode; opening setup wizard')
    superviseSetupRestart({
      getChild: () => backendChild,
      setChild: (c) => {
        backendChild = c
      },
      expectedPort: started.port,
      isShuttingDown: () => shuttingDown,
      onFatal: (m) => console.error(`FATAL: ${m}`),
      restart: () =>
        startBackend({ layout, token, requestedPort: started.port, onBackendLine, shouldCancel: () => shuttingDown })
          .then((r) => ({ child: r.child, port: r.port, setupMode: r.setupMode })),
    })
  }

  createMainWindow({
    apiBase,
    token,
    onHideToTray: () => {
      /* Task 4 wires the tray; for now hiding is the no-op stub */
    },
    isDev: !isPackaged,
  })
}

app.whenReady().then(() => {
  void bootstrap()
})

app.on('before-quit', () => {
  shuttingDown = true
  backendChild?.kill()
})
```

Note: this `main.ts` will be extended in Task 4 (single-instance, tray, logger, updater) — Task 3 just establishes the orchestration → window flow with a tray stub.

- [ ] **Step 6: Type-check + build the TS**

```bash
npx tsc -p tsconfig.json
```
Expected: compiles to `dist/` (main.js, preload.js, etc.). Fix any type errors.

- [ ] **Step 7: Commit**

```bash
git add desktop/electron/src/
git commit -m "✨ feat(desktop): wire backend orchestration to Electron window + preload bridge"
```

### Task 3b: frontend bridge refactor

- [ ] **Step 1: Modify `frontend/src/api/config.ts`**

Replace `getApiBase`/`getToken` to prefer `window.fengyu`:

```ts
import type { FengyuBridge } from '../electron-env'

declare global {
  interface Window { fengyu?: FengyuBridge }
}

export function getApiBase(): string {
  if (typeof window !== 'undefined' && window.fengyu) {
    return window.fengyu.apiBase()
  }
  return import.meta.env.VITE_FENGYU_API_BASE ?? ''
}

export function getToken(): string {
  if (typeof window !== 'undefined' && window.fengyu) {
    return window.fengyu.token()
  }
  return import.meta.env.VITE_FENGYU_TOKEN ?? ''
}
```

Leave `backendUrl` and `pluginAssetUrl` unchanged (they consume `getApiBase()`, so they automatically pick up the new source).

- [ ] **Step 2: Create `frontend/src/electron-env.d.ts`**

```ts
/** The shape exposed by the Electron preload via contextBridge. Undefined in web mode. */
export interface FengyuBridge {
  apiBase(): string
  token(): string
  pickFile(filters?: { name: string; extensions: string[] }[]): Promise<string | null>
  pickDirectory(): Promise<string | null>
  desktop: true
}
```

- [ ] **Step 3: Modify `frontend/src/env.d.ts`**

Remove the `__TAURI_*` and `__FENGYU_*` declarations (the bridge is now typed via `electron-env.d.ts`):

```ts
/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

interface ImportMetaEnv {
  readonly VITE_FENGYU_TOKEN?: string
  readonly VITE_FENGYU_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

/** Build-time app version, injected from package.json by vite.config.ts `define`. */
declare const __APP_VERSION__: string

/** Build timestamp (ISO-8601), captured at build / dev-server start by vite.config.ts `define`. */
declare const __APP_BUILD_TIME__: string
```

(The `Window.fengyu` declaration lives in `config.ts` via `declare global` — or you may consolidate it into `electron-env.d.ts` with its own `declare global`. Pick one; ensure no duplicate.)

- [ ] **Step 4: Rewrite `frontend/src/mf/desktop.ts`**

```ts
import type { PluginContext } from './loader'

/** True when running inside the Electron desktop shell. */
export function isDesktop(): boolean {
  return typeof window !== 'undefined' && window.fengyu?.desktop === true
}

/** Build the native-dialog facade, or undefined when not under Electron. */
export function makeDesktop(): PluginContext['desktop'] {
  if (!isDesktop()) return undefined
  return {
    async pickFile(filters) {
      return (await window.fengyu!.pickFile(filters)) ?? null
    },
    async pickDirectory() {
      return (await window.fengyu!.pickDirectory()) ?? null
    },
  }
}
```

- [ ] **Step 5: Remove the Tauri dialog dependency**

Edit `frontend/package.json` — delete the line:
```json
"@tauri-apps/plugin-dialog": "^2.7.1",
```
Then:
```bash
cd frontend && npm install
```
This updates `package-lock.json`. Verify no remaining imports of `@tauri-apps/`:
```bash
cd .. && grep -rn "@tauri-apps" frontend/src/ || echo "clean"
```
Expected: `clean`.

- [ ] **Step 6: Verify the frontend still builds + type-checks**

```bash
cd frontend && npm run build
```
Expected: build succeeds. (The SPA no longer references any Tauri symbol.)

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/config.ts frontend/src/electron-env.d.ts frontend/src/env.d.ts frontend/src/mf/desktop.ts frontend/package.json frontend/package-lock.json
git commit -m "♻️ refactor(frontend): swap Tauri bridge for Electron contextBridge API"
```

---

## Task 4: Desktop enhancements (single-instance, tray, logger, auto-updater) + shutdown semantics

**Files:**
- Create: `desktop/electron/src/desktop/single-instance.ts`, `desktop/electron/src/desktop/tray.ts`, `desktop/electron/src/desktop/logger.ts`, `desktop/electron/src/updater/auto-updater.ts`
- Modify: `desktop/electron/src/main.ts`

**Interfaces:**
- `acquireSingleInstanceLock(onSecondInstance: (win: BrowserWindow|null) => void): boolean`
- `createTray(win: BrowserWindow, onQuit: () => void): Tray`
- `initLogger(): { info, error, backendLine(line) }`
- `checkForUpdates(opts): Promise<void>`

- [ ] **Step 1: Write `desktop/electron/src/desktop/logger.ts`**

```ts
import log from 'electron-log'
import { join } from 'node:path'
import { homedir } from 'node:os'

/** Configure electron-log to write alongside the backend logs (~/.fengyu/logs). */
export function initLogger() {
  const logDir = join(homedir(), '.fengyu', 'logs')
  log.transports.file.resolvePathFn = () => join(logDir, 'desktop.log')
  log.transports.file.maxSize = 5 * 1024 * 1024 // 5 MB rotation
  log.transports.console.level = 'info'
  log.transports.file.level = 'info'
  log.info('[desktop] logger initialized')

  const backendLine = (line: string) => {
    // Tee backend stdout to its own file (mirrors Rust println!("[backend] ...")).
    log.transports.file.getFile().write(`[backend] ${line}\n`)
  }
  return { info: log.info, error: log.error, warn: log.warn, backendLine }
}

export type DesktopLogger = ReturnType<typeof initLogger>
```

- [ ] **Step 2: Write `desktop/electron/src/desktop/single-instance.ts`**

```ts
import { app, BrowserWindow } from 'electron'

/**
 * Acquire the single-instance lock. If a second instance launches, the first
 * instance's `second-instance` handler shows + focuses the existing window
 * (also restoring it from the tray). Returns false (and quits) when not the primary.
 */
export function acquireSingleInstanceLock(
  onSecondInstance: (win: BrowserWindow | null) => void,
): boolean {
  const gotLock = app.requestSingleInstanceLock()
  if (!gotLock) {
    app.quit()
    return false
  }
  app.on('second-instance', () => {
    onSecondInstance(BrowserWindow.getAllWindows()[0] ?? null)
  })
  return true
}
```

- [ ] **Step 3: Write `desktop/electron/src/desktop/tray.ts`**

```ts
import { app, Tray, Menu, nativeImage, BrowserWindow } from 'electron'
import { join } from 'node:path'

let tray: Tray | null = null

/**
 * Create the system tray icon + menu (Show / Hide / Quit).
 * The window's close button hides to tray; only "Quit" tears down the backend.
 */
export function createTray(win: BrowserWindow, onQuit: () => void): Tray {
  const iconPath = join(__dirname, '../resources/icon-32.png')
  const image = nativeImage.createFromPath(iconPath)
  tray = new Tray(image.isEmpty() ? nativeImage.createFromPath(join(__dirname, '../resources/icon.png')) : image)
  tray.setToolTip('FengYu')

  const menu = Menu.buildFromTemplate([
    { label: 'Show', click: () => { win.show(); win.focus() } },
    { label: 'Hide', click: () => win.hide() },
    { type: 'separator' },
    {
      label: 'Quit',
      click: () => {
        onQuit()
        app.quit()
      },
    },
  ])
  tray.setContextMenu(menu)
  tray.on('click', () => {
    if (win.isVisible()) win.hide()
    else { win.show(); win.focus() }
  })
  return tray
}
```

- [ ] **Step 4: Write `desktop/electron/src/updater/auto-updater.ts`**

```ts
import { autoUpdater } from 'electron-updater'
import { dialog } from 'electron'

/**
 * Check for updates (async, non-blocking). Source: GitHub Releases (latest*.yml).
 * Alpha builds are unsigned — electron-updater supports unsigned updates on Windows
 * (NSIS); macOS users must allow Gatekeeper manually.
 */
export async function checkForUpdates(): Promise<void> {
  try {
    const result = await autoUpdater.checkForUpdates()
    if (!result?.updateInfo) return
    const choice = await dialog.showMessageBox({
      type: 'question',
      buttons: ['Download & install', 'Later'],
      defaultId: 0,
      title: 'Update available',
      message: `Infinia ${result.updateInfo.version} is available. Download and install now?`,
    })
    if (choice.response === 0) {
      await autoUpdater.downloadUpdate()
      autoUpdater.quitAndInstall()
    }
  } catch (err) {
    console.error('[updater] check failed:', err)
  }
}
```

- [ ] **Step 5: Wire all four enhancements into `desktop/electron/src/main.ts`**

Edit `main.ts`: replace the Task-3 stub with the full version. Key changes: add `initLogger()`, `acquireSingleInstanceLock()`, `createTray()`, and call `checkForUpdates()` after the window shows. Pass `logger.backendLine` to `startBackend`.

```ts
// Prevents an extra console window on Windows in release builds.
if (process.platform === 'win32') app.setAppUserModelId('fan.summer.fengyu')

import { app, dialog, BrowserWindow } from 'electron'
import { resolveLayout } from './backend/runtime-layout'
import { genToken } from './util/token'
import { startBackend } from './backend/orchestrator'
import { startupAction, StartupAction, superviseSetupRestart, type BackendChild } from './backend/supervisor'
import { registerDialogIpc } from './ipc/dialog'
import { createMainWindow } from './window/create-window'
import { initLogger } from './desktop/logger'
import { acquireSingleInstanceLock } from './desktop/single-instance'
import { createTray } from './desktop/tray'
import { checkForUpdates } from './updater/auto-updater'

const logger = initLogger()
let backendChild: BackendChild | null = null
let shuttingDown = false

function killBackend() {
  shuttingDown = true
  backendChild?.kill()
}

async function bootstrap(): Promise<void> {
  registerDialogIpc()

  const isPackaged = app.isPackaged
  const layout = resolveLayout(isPackaged, process.resourcesPath, process.env)

  const token = genToken()
  process.env.FENGYU_TOKEN = token
  process.env.FENGYU_API_BASE = ''

  let started
  try {
    started = await startBackend({
      layout,
      token,
      requestedPort: 24056,
      onBackendLine: logger.backendLine,
      shouldCancel: () => shuttingDown,
    })
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    if (/spawn.*java|ENOENT/i.test(msg)) {
      dialog.showErrorBox(
        'Java not found',
        'FengYu requires Java 21+ on your PATH. Please install a JRE (https://adoptium.net) ' +
          'or use the Infinia build that bundles a JRE.',
      )
    } else {
      dialog.showErrorBox('Failed to start backend', msg)
    }
    app.quit()
    return
  }

  const apiBase = `http://127.0.0.1:${started.port}`
  process.env.FENGYU_API_BASE = apiBase
  backendChild = started.child

  const action = startupAction(started.setupMode, started.port)

  if (action === StartupAction.ShowWindowAndSupervise) {
    logger.info('[desktop] backend in SETUP mode; opening setup wizard')
    superviseSetupRestart({
      getChild: () => backendChild,
      setChild: (c) => { backendChild = c },
      expectedPort: started.port,
      isShuttingDown: () => shuttingDown,
      onFatal: (m) => logger.error(`FATAL: ${m}`),
      restart: () =>
        startBackend({ layout, token, requestedPort: started.port, onBackendLine: logger.backendLine, shouldCancel: () => shuttingDown })
          .then((r) => ({ child: r.child, port: r.port, setupMode: r.setupMode })),
    })
  }

  const win = createMainWindow({
    apiBase,
    token,
    onHideToTray: () => logger.info('[desktop] window hidden to tray'),
    isDev: !isPackaged,
  })

  createTray(win, killBackend)

  // Non-blocking update check.
  if (isPackaged) void checkForUpdates()
}

app.whenReady().then(() => {
  const locked = acquireSingleInstanceLock((existing) => {
    if (existing) { existing.show(); existing.focus() }
  })
  if (!locked) return
  void bootstrap()
})

app.on('before-quit', killBackend)

// Keep one tray/menu reference alive on macOS even with no windows.
app.on('window-all-closed', (e: Event) => {
  if (process.platform === 'darwin') e.preventDefault()
})
```

- [ ] **Step 6: Verify type-check + tests still pass**

```bash
cd desktop/electron && npx tsc -p tsconfig.json && npx vitest run
```
Expected: tsc clean; unit tests pass.

- [ ] **Step 7: Manual smoke (dev)**

With the backend running externally (`mvn -pl FengYu spring-boot:run` on :24056) is NOT enough for `bootstrap()` — that path spawns its own. For a dev smoke, set `FENGYU_JAR` to a built jar and run:
```bash
FENGYU_JAR=$(ls ../../FengYu/target/FengYu-*.jar | head -1) npm run dev
```
Expected: window opens, loads the SPA, SPA reaches `/api/health`. (Full e2e is Task 6.)

- [ ] **Step 8: Commit**

```bash
git add desktop/electron/src/
git commit -m "✨ feat(desktop): add single-instance, tray, file logging, and auto-update"
```

---

## Task 5: Packaging config + CI (electron-builder.yml, jlink, workflow, release test)

**Files:**
- Create: `desktop/electron/electron-builder.yml`, `desktop/electron/scripts/build-jre.sh`
- Modify: `.github/workflows/fengyu-release.yml`, `scripts/release-workflow.test.mjs`

### Task 5a: electron-builder config

- [ ] **Step 1: Write `desktop/electron/electron-builder.yml`**

Base config. CI overrides `extraResources` + `artifactName` for the with-JRE variant via `--config`.

```yaml
appId: fan.summer.fengyu
productName: Infinia
copyright: Copyright © 2026 FengYu

directories:
  output: ../dist-electron
  buildResources: resources

files:
  - dist/**/*
  - resources/icon.png
  - resources/icon.ico
  - resources/icon-32.png
  - resources/icon-128.png
  - package.json

# Frontend SPA, built by `cd ../frontend && npm run build` before packaging.
extraMetadata:
  main: dist/main.js

extraResources:
  - from: resources/binaries/FengYu.jar
    to: binaries/FengYu.jar
    filter: ['**/*']
  - from: resources/binaries/plugins
    to: plugins
    filter: ['**/*']

# The with-JRE variant adds (via CI --config):
#   extraResources:
#     - { from: resources/jre, to: jre, filter: ['**/*'] }

win:
  target: nsis
  icon: resources/icon.ico

mac:
  target:
    - target: dmg
      arch: [arm64, x64]
  icon: resources/icon.png
  category: public.app-category.developer-tools

linux:
  target: AppImage
  icon: resources/icon.png
  category: Development

nsis:
  oneClick: false
  perMachine: false
  allowToChangeInstallationDirectory: true

publish:
  provider: github
  owner: MaskStark
  repo: FengYu
```

Note: verify `publish.owner`/`repo` against the actual GitHub repo (check `git remote -v`); correct if different.

- [ ] **Step 2: Write `desktop/electron/scripts/build-jre.sh`**

CI runs this per-platform runner to generate the jlink JRE.

```bash
#!/usr/bin/env bash
# Generate a jlink-minimized JRE for the with-JRE build variant.
# Run on each platform runner with JDK 21 LTS on PATH.
set -euo pipefail

JAR="${1:?usage: build-jre.sh <path/to/FengYu.jar>}"
OUT="${2:-resources/jre}"

EXPLICIT="java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.scripting,java.sql,java.sql.rowset,java.transaction.xa,java.xml,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.unsupported,jdk.zipfs,jdk.management"

JLINK_MODS=$(jdeps --multi-release 21 --ignore-missing-deps --print-module-deps -cp "$JAR" "$JAR" || echo "")

if [ -z "$JLINK_MODS" ]; then
  MODS="$EXPLICIT"
else
  MODS="$JLINK_MODS,$EXPLICIT"
fi

echo "[build-jre] modules: $MODS"
rm -rf "$OUT"
jlink --no-header-files --no-man-pages --strip-debug \
  --add-modules "$MODS" \
  --output "$OUT"
echo "[build-jre] JRE written to $OUT"
```

```bash
chmod +x desktop/electron/scripts/build-jre.sh
```

### Task 5b: release workflow + contract test

- [ ] **Step 1: Replace the `desktop:` job in `.github/workflows/fengyu-release.yml`**

Replace the entire `desktop:` job (the Tauri one) with an Electron job that builds both variants. New job:

```yaml
  # ==========================================================================
  # Desktop — build the Electron bundle for each platform (with + without JRE).
  # CI stages the tested JAR + .fyp plugins into desktop/electron/resources/binaries/,
  # generates a jlink JRE for the with-JRE variant, then runs electron-builder twice.
  # ==========================================================================
  desktop:
    needs: [setup, build-runtime]
    strategy:
      fail-fast: false
      matrix:
        include:
          - { os: windows-latest, artifact: infinia-windows }
          - { os: macos-latest, artifact: infinia-macos }
          - { os: ubuntu-22.04, artifact: infinia-linux }
    runs-on: ${{ matrix.os }}
    env:
      APP_VERSION: ${{ needs.setup.outputs.app_version }}
      FENGYU_RELEASE_VERSION: ${{ needs.setup.outputs.version }}
    steps:
      - name: Checkout
        uses: actions/checkout@v5
        with:
          ref: ${{ inputs.tag || github.ref }}

      - name: Set up Node 24.18.0
        uses: actions/setup-node@v5
        with:
          node-version: '24.18.0'
          cache: npm
          cache-dependency-path: desktop/electron/package-lock.json

      - name: Set up JDK 21 (for jlink)
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Install frontend deps
        run: npm ci
        working-directory: frontend

      - name: Build frontend SPA
        run: npm run build
        working-directory: frontend

      - name: Install desktop deps
        run: npm ci
        working-directory: desktop/electron

      - name: Run desktop unit tests
        run: npm test
        working-directory: desktop/electron

      - name: Download shared inputs
        uses: actions/download-artifact@v5
        with:
          path: inputs
          merge-multiple: true

      - name: Stage runtime resources
        shell: bash
        run: |
          mkdir -p desktop/electron/resources/binaries/plugins
          cp inputs/Infinia.jar desktop/electron/resources/binaries/FengYu.jar
          cp inputs/*.fyp desktop/electron/resources/binaries/plugins/
          ls -R desktop/electron/resources/binaries

      - name: Build Electron bundle (without JRE)
        working-directory: desktop/electron
        shell: bash
        run: npx electron-builder --config.directories.output=../dist-electron-lite

      - name: Generate jlink JRE
        shell: bash
        run: |
          desktop/electron/scripts/build-jre.sh \
            desktop/electron/resources/binaries/FengYu.jar \
            desktop/electron/resources/jre

      - name: Build Electron bundle (with JRE)
        working-directory: desktop/electron
        shell: bash
        run: |
          npx electron-builder --config.directories.output=../dist-electron-jre \
            --config.extraMetadata.name=Infinia-JRE \
            --config.extraResources="{\"from\":\"resources/jre\",\"to\":\"jre\",\"filter\":[\"**/*\"]}"

      - name: Upload desktop bundles
        uses: actions/upload-artifact@v4
        with:
          name: ${{ matrix.artifact }}
          path: |
            desktop/dist-electron-lite/**
            desktop/dist-electron-jre/**
          if-no-files-found: error
```

Also update the `release:` job's `needs:` (already lists `desktop`) and the release-body copy:
- Replace `### Desktop (unsigned Tauri packages)` → `### Desktop (unsigned Electron packages)`.
- Update the copy to mention two variants ("with bundled JRE" and "requires Java 21+").
- The artifact glob already covers `*.dmg`, `*.exe`, `*.AppImage` — add `*-jre.*` if electron-builder's naming differs (verify after first build).

- [ ] **Step 2: Rewrite `scripts/release-workflow.test.mjs`**

Replace the Tauri assertions with Electron ones. Keep the tests that aren't Tauri-specific unchanged.

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const workflow = readFileSync(new URL('../.github/workflows/fengyu-release.yml', import.meta.url), 'utf8')
const builderConfig = readFileSync(new URL('../desktop/electron/electron-builder.yml', import.meta.url), 'utf8')
const desktopJob = workflow.slice(
  workflow.indexOf('\n  desktop:'),
  workflow.indexOf('\n  release:'),
)

test('uses the runner-provided GITHUB_OUTPUT file', () => {
  assert.doesNotMatch(workflow, /^\s+GITHUB_OUTPUT:/m)
})

test('runs release contract tests in the shared runtime job', () => {
  assert.match(
    workflow,
    /node --test scripts\/resolve-release-version\.test\.mjs scripts\/release-workflow\.test\.mjs scripts\/node-version\.test\.mjs/,
  )
})

test('installs plugin-cli dependencies before building plugins', () => {
  assert.match(
    workflow,
    /- name: Install plugin-cli deps\s+run: npm ci\s+working-directory: plugin-cli/,
  )
})

test('builds Maven artifacts with the full release version', () => {
  assert.match(workflow, /\.\/mvnw -am test package -Drevision="\$VERSION"/)
  assert.doesNotMatch(workflow, /\.\/mvnw -am test package -Drevision="\$APP_VERSION"/)
})

test('electron-builder targets NSIS on Windows, DMG on macOS, AppImage on Linux', () => {
  assert.match(builderConfig, /win:\s*\n\s*target:\s*nsis/)
  assert.match(builderConfig, /mac:\s*\n\s*target:/)
  assert.match(builderConfig, /linux:\s*\n\s*target:\s*AppImage/)
})

test('electron-builder bundles the FengYu jar + plugins as extraResources', () => {
  assert.match(builderConfig, /from: resources\/binaries\/FengYu\.jar/)
  assert.match(builderConfig, /from: resources\/binaries\/plugins/)
})

test('desktop job builds two variants and runs unit tests', () => {
  assert.match(desktopJob, /FENGYU_RELEASE_VERSION: \${{ needs\.setup\.outputs\.version }}/)
  assert.match(desktopJob, /- name: Install frontend deps\s+run: npm ci\s+working-directory: frontend/)
  assert.match(desktopJob, /- name: Run desktop unit tests\s+run: npm test\s+working-directory: desktop\/electron/)
  assert.match(desktopJob, /Build Electron bundle \(without JRE\)/)
  assert.match(desktopJob, /Build Electron bundle \(with JRE\)/)
  assert.match(desktopJob, /Generate jlink JRE/)
})

test('flattens nested desktop installers before checksums and release upload', () => {
  assert.match(workflow, /find artifacts -type f/)
  assert.match(workflow, /release-files\/\$\(basename "\$file"\)/)
  assert.match(workflow, /files: \|\s+release-files\/\*/)
})
```

- [ ] **Step 3: Run the contract test**

```bash
node --test scripts/release-workflow.test.mjs
```
Expected: all tests pass. (If `appId`/`owner`/`repo` differ from the YAML, fix the regex or the config — don't weaken the assertion.)

- [ ] **Step 4: Commit**

```bash
git add desktop/electron/electron-builder.yml desktop/electron/scripts/build-jre.sh .github/workflows/fengyu-release.yml scripts/release-workflow.test.mjs
git commit -m "👷 ci(desktop): add electron-builder config, jlink JRE generation, and release workflow"
```

---

## Task 6: e2e launch test + verification

**Files:**
- Create: `desktop/electron/test/e2e/playwright.config.ts`, `desktop/electron/test/e2e/launch.spec.ts`

- [ ] **Step 1: Write `desktop/electron/test/e2e/playwright.config.ts`**

```ts
import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  timeout: 120_000,
  retries: 0,
  use: {
    trace: 'on-first-retry',
  },
})
```

- [ ] **Step 2: Write `desktop/electron/test/e2e/launch.spec.ts`**

Uses `_electron` from Playwright to launch the built app against a prebuilt JAR. Expects `FENGYU_JAR` env to point at a real shaded jar (CI runs `mvn package` first).

```ts
import { test, expect, _electron as electron } from '@playwright/test'
import { join } from 'node:path'
import { existsSync } from 'node:fs'

const JAR = process.env.FENGYU_JAR ?? ''

test.describe.skipif(!JAR || !existsSync(JAR))('desktop launch', () => {
  test('window opens and reaches the backend', async () => {
    const app = await electron.launch({
      args: [join(__dirname, '../../dist/main.js')],
      env: {
        ...process.env,
        FENGYU_JAR: JAR,
        FENGYU_PLUGINS: process.env.FENGYU_PLUGINS ?? '',
        NODE_ENV: 'test',
      },
    })
    try {
      const win = await app.firstWindow()
      await win.waitForLoadState('domcontentloaded', { timeout: 60_000 })

      // The preload injects window.fengyu with apiBase/token.
      const apiBase = await win.evaluate(() => (window as any).fengyu?.apiBase?.())
      expect(apiBase).toMatch(/^http:\/\/127\.0\.0\.1:\d+$/)

      // The backend is reachable at that base.
      const ok = await win.evaluate(async (base: string) => {
        const r = await fetch(`${base}/api/health`, { headers: { 'X-FengYu-Token': (window as any).fengyu.token() } })
        return r.status === 200
      }, apiBase)
      expect(ok).toBe(true)
    } finally {
      await app.close()
    }
  })
})
```

The env var names (`FENGYU_JAR`/`FENGYU_PLUGINS`) are canonical — they match Task 2b's `resolveLayout`. Do not use mixed-case variants.

- [ ] **Step 3: Run unit + e2e**

```bash
cd desktop/electron
npm run build:ts   # build main.js for e2e
npm test           # unit
FENGYU_JAR=$(ls ../../FengYu/target/FengYu-*.jar 2>/dev/null | head -1) npm run test:e2e
```
Expected: unit pass; e2e window opens and `/api/health` returns 200 (requires a built jar).

- [ ] **Step 4: Commit**

```bash
git add desktop/electron/test/e2e/
git commit -m "✅ test(desktop): add playwright-electron e2e launch test"
```

---

## Task 7: Docs sync

**Files (modify):**
- `desktop/README.md` (full rewrite)
- `docs/en/architecture/desktop.md`, `docs/zh/architecture/desktop.md` (full rewrite)
- `docs/en/architecture/overview.md`, `docs/zh/architecture/overview.md`
- `docs/en/quickstart.md`, `docs/zh/quickstart.md`
- `docs/en/architecture/backend.md`, `docs/zh/architecture/backend.md`
- `docs/en/architecture/frontend.md`, `docs/zh/architecture/frontend.md`
- `docs/en/index.md`, `docs/zh/index.md`, `docs/en/features.md`, `docs/zh/features.md`, `docs/en/design-system.md`, `docs/zh/design-system.md`
- `docs/en/plugins/file-io.md`, `docs/zh/plugins/file-io.md`, `docs/en/plugins/ui-microfrontend.md`, `docs/zh/plugins/ui-microfrontend.md`
- `docs/en/skills/index.md`, `docs/zh/skills/index.md`
- `AGENTS.md`, root `README.md`
- `.agents/skills/app-release/SKILL.md`, `.agents/skills/docs-updater/SKILL.md`
- `frontend/src/views/About.vue`, `frontend/src/i18n/en.json`, `frontend/src/i18n/zh.json`

- [ ] **Step 1: Find every remaining tauri reference**

```bash
grep -rli "tauri" --include="*.md" docs/ AGENTS.md README.md desktop/ .agents/skills/ frontend/ 2>/dev/null
```
This is the master list of files to update.

- [ ] **Step 2: Rewrite `desktop/README.md`**

Full dev/build guide for Electron: prerequisites (Node, JDK 21 for backend), `npm install`, `npm run dev` (set `FENGYU_JAR` to a built shaded jar, or run the backend externally on :24056), staging `resources/binaries/FengYu.jar` + `plugins/`, `npm run build` + `electron-builder`, the two-variant build, tray semantics (close→tray, quit→kill backend), troubleshooting (Java not found error). Mirror the structure of the deleted Tauri README.

- [ ] **Step 3: Rewrite `docs/en/architecture/desktop.md` + `docs/zh/architecture/desktop.md`**

Cover: Electron main process architecture, the backend lifecycle (spawn → FENGYU_PORT → health → setup → supervisor), the contextBridge preload + `window.fengyu`, single-instance/tray/logger/auto-update, the close→tray semantics change (call out the difference from the old Tauri "close kills backend"), packaging (electron-builder, with/without JRE, jlink). Keep EN/ZH structurally mirrored.

- [ ] **Step 4: Update `docs/{en,zh}/architecture/overview.md`**

Replace "Tauri 2.0 desktop shell" → "Electron desktop shell"; update the ASCII diagram's shell layer label.

- [ ] **Step 5: Update the remaining doc files**

For each file from Step 1: replace `Tauri`/`cargo tauri dev`/`cargo tauri build`/`Rust` shell references with Electron equivalents; update prerequisite tables (`tauri-cli` → `electron`); keep the backend/frontend sections (they're shell-agnostic) but update any "Tauri webview" wording. Verify EN/ZH parity after each pair.

- [ ] **Step 6: Update `AGENTS.md`**

- §"What FengYu 4.0.0 is" → Desktop line: "a Tauri 2.0 shell" → "an Electron shell".
- "sidecar-launches the backend JAR" stays (Electron also sidecars it).
- The `cargo tauri dev` quickstart → Electron dev command.
- Update any version-mirror mention (`desktop/src-tauri/Cargo.toml` → `desktop/electron/package.json`).

- [ ] **Step 7: Update root `README.md`**

Replace Tauri mentions: prerequisites, `cargo tauri dev`/`cargo tauri build` → `npm run dev`/`npm run build` under `desktop/electron/`; "Built with ... Tauri" → Electron; the desktop build instructions.

- [ ] **Step 8: Update the two skills**

`.agents/skills/app-release/SKILL.md`:
- Line ~9-10 version-mirror list: `desktop/src-tauri/Cargo.toml + tauri.conf.json` → `desktop/electron/package.json`.
- Line ~40-42: same repoint.
- Line ~93-95: "unsigned Tauri packages" → "unsigned Electron packages".

`.agents/skills/docs-updater/SKILL.md`:
- Line ~24-25: `desktop/src-tauri/Cargo.toml + tauri.conf.json` → `desktop/electron/package.json`.

- [ ] **Step 9: Update frontend About + i18n**

`frontend/src/views/About.vue` line ~54: `"tauri": "Desktop shell (Rust)"` → `"electron": "Desktop shell (Electron)"`.
`frontend/src/i18n/en.json` + `zh.json` line ~142: the dependency credit label — update both languages.

- [ ] **Step 10: Verify docs build + no stale references**

```bash
npm run docs:build
grep -rli "tauri" --include="*.md" docs/ AGENTS.md README.md desktop/README.md 2>/dev/null | grep -v "docs/superpowers/" || echo "no active tauri refs"
```
Expected: docs build succeeds; the grep returns only historical `docs/superpowers/` planning docs (which we intentionally keep).

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "📝 docs: sync Tauri→Electron across docs, README, AGENTS, skills, About"
```

---

## Task 8: Final review — no stale references

- [ ] **Step 1: Grep for any leftover Tauri/Rust/cargo references in active code/config**

```bash
echo "=== src-tauri paths ===" && grep -rn "src-tauri" --include="*.ts" --include="*.js" --include="*.yml" --include="*.yaml" --include="*.json" --include="*.mjs" --include="*.sh" . | grep -v node_modules | grep -v "/dist/" || echo "clean"
echo "=== cargo/tauri-cli ===" && grep -rn "cargo\|tauri-cli" --include="*.yml" --include="*.yaml" --include="*.sh" --include="*.mjs" . | grep -v node_modules || echo "clean"
echo "=== __TAURI__ ===" && grep -rn "__TAURI__\|@tauri-apps" --include="*.ts" --include="*.vue" --include="*.js" frontend/ desktop/ | grep -v node_modules || echo "clean"
echo "=== window.__FENGYU_ ===" && grep -rn "__FENGYU_" --include="*.ts" --include="*.vue" frontend/ desktop/electron/src/ | grep -v node_modules || echo "clean"
```
Expected: all "clean" (only historical `docs/superpowers/` planning docs may mention tauri, which is intended).

- [ ] **Step 2: Verify the full unit suite + type-check**

```bash
cd desktop/electron && npx tsc -p tsconfig.json --noEmit && npm test
```
Expected: tsc clean; all unit tests pass.

- [ ] **Step 3: Verify the release contract test**

```bash
cd /Users/phoebej/Develop/Java/FengYu && node --test scripts/release-workflow.test.mjs scripts/resolve-release-version.test.mjs scripts/node-version.test.mjs
```
Expected: all pass.

- [ ] **Step 4: Verify frontend builds clean**

```bash
cd frontend && npm run build
```
Expected: success.

- [ ] **Step 5: Final commit if anything was fixed**

```bash
git add -A
git commit -m "🔧 chore: sweep stray Tauri references" || echo "nothing to commit"
```

- [ ] **Step 6: Summary**

The migration is complete when:
- All unit tests + e2e pass.
- `tsc --noEmit` clean.
- Release contract test passes.
- Frontend builds.
- No active code/config/doc references Tauri/Rust/cargo/`__TAURI__`/`__FENGYU_*` (only historical `docs/superpowers/` planning docs remain).
- CI workflow builds two Electron variants per platform.

---

## Self-Review Notes

- **Spec coverage:** §1 decisions → all tasks; §2 backend contract → Task 2 (faithful timings); §3 structure → Tasks 1–4; §4 orchestration → Task 2; §5 bridge → Task 3b; §6 enhancements → Task 4; §7 shutdown change → Task 4 Step 5 (`window-all-closed` + tray); §8 packaging → Task 5; §9 testing → Tasks 2 + 6; §10 toolchain compat → Tasks 3b (frontend) + 5 (CI/test) + 7 (skills); §11 docs → Task 7; §12 phases → Tasks 1–8.
- **Type consistency:** `BackendChild`, `RuntimeLayout`, `StartedBackend`, `FengyuBridge` checked for consistent naming across tasks. The `StartupAction` enum is exported from `supervisor.ts` and consumed in `main.ts`.
- **Env var consistency:** `FENGYU_JAR` / `FENGYU_PLUGINS` / `FENGYU_TOKEN` / `FENGYU_API_BASE` are used consistently throughout (canonical uppercase).
