import { app, dialog, session } from 'electron'
import { join, dirname } from 'node:path'
import { resolveLayout } from './backend/runtime-layout'
import { resolveJava } from './backend/runtime-layout-helpers'
import {
  prepareAotLaunch,
  trainAotCacheInBackground,
  type AotJvmArgs,
  type TrainingContext,
  type TrainingHandle,
} from './backend/aot-cache'
import { runtimeRoot } from './desktop/runtime-paths'
import { genToken } from './util/token'
import { startBackend, probeSetupMode } from './backend/orchestrator'
import { spawnBackend } from './backend/spawn'
import { isAppCrash, startupAction, StartupAction, superviseSetupRestart, type BackendChild } from './backend/supervisor'
import { pollHealth } from './util/health'
import {
  classifyBootFailure,
  registerBootIpc,
  tagBootFailure,
  type BootStage,
} from './ipc/boot'
import { setMainWindowResolver } from './ipc/sender-guard'
import { registerEndpointIpc } from './ipc/endpoint'
import { registerArtifactIpc } from './ipc/artifact'
import { registerLogIpc } from './ipc/log'
import { registerDialogIpc } from './ipc/dialog'
import { registerExternalIpc } from './ipc/external'
import { registerDisplayMediaHandler } from './ipc/displayMedia'
import { registerPermissionHandlers } from './window/permission-handlers'
import { registerAppScheme, handleAppProtocol } from './window/app-protocol'
import { registerUpdateIpc } from './ipc/update'
import { registerNotificationIpc } from './ipc/notification'
import { createMainWindow } from './window/create-window'
import { initLogger, resolveLogDir } from './desktop/logger'
import { acquireSingleInstanceLock } from './desktop/single-instance'
import { createTray } from './desktop/tray'
import { createGracefulQuitHandler } from './desktop/graceful-quit'
import { checkForUpdates } from './updater/auto-updater'
import { bootstrapUpdateApiBaseFromBackend } from './updater/update-feed'
import { logUpdate } from './updater/update-log'
import { startDevFrontend, type DevFrontendHandle } from './desktop/dev-frontend'
import { initializeAppearance } from './desktop/appearance'
import { markMainLaunchWhenReady, markMainWindowLoad } from './desktop/launch-marks'
import { registerPerfIpc } from './ipc/perf'
import { applyUosLaunchPolicy } from './desktop/uos'
import { bootstrapWorkingDirectory } from './desktop/bootstrap-cwd'
import { BrowserSession } from './browser/session'
import { startBrowserBridge, type BrowserBridge } from './browser/bridge'

// Working-directory bootstrap (P1-9): must run BEFORE initLogger below — a packaged app
// launched from Finder/Dock/Linux menu starts with cwd `/` (read-only), and <cwd>/.fengyu
// (logs, config, backend cwd) would be unwritable. Windows anchors to the executable's
// directory (install root / portable extract folder, migrating a legacy %APPDATA% tree);
// macOS/Linux anchor to userData. Dev runs are untouched; the UOS policy below may
// re-anchor again to the user's home, which is why this runs first.
//
// The single-instance lock is acquired FIRST: only the lock-holding instance may run the
// Windows runtime-tree migration — a second instance racing the first's probe/copy could
// interleave filesystem mutations. requestSingleInstanceLock is valid before app.whenReady;
// the second-instance handler resolves the main window lazily, and a secondary instance
// quits here without ever touching the executable directory.
const isPrimaryInstance = acquireSingleInstanceLock(
  (existing) => {
    if (existing) {
      existing.show()
      existing.focus()
    }
  },
  () => mainWindow,
)
const cwdAnchor = bootstrapWorkingDirectory({ migrationEnabled: isPrimaryInstance })

// UOS no-sandbox policy: must run BEFORE initLogger below — it chdirs to the user's home (a
// menu-launched UOS app starts with cwd `/`, unwritable for non-root, and <cwd>/.fengyu would
// crash the logger) and appends `no-sandbox` (must precede app.whenReady). No-op unless this
// is the packaged UOS artifact (fengyu.uos baked by electron-builder.uos.yml).
const uosLaunch = applyUosLaunchPolicy()

const logger = initLogger()
if (cwdAnchor.changed) {
  logger.info(
    `[desktop] packaged launch: working directory re-anchored to ${cwdAnchor.directory}` +
      (cwdAnchor.fallbackUsed ? ' (temp-directory fallback)' : ''),
  )
}
if (cwdAnchor.migrated) {
  logger.info(
    `[desktop] runtime tree migrated from ${cwdAnchor.migrated.from} to ${cwdAnchor.migrated.to}`,
  )
}
if (uosLaunch) {
  logger.info('[desktop] UOS build: no-sandbox mode enabled, working directory re-anchored to the user home')
}
let backendChild: BackendChild | null = null
let devFrontend: DevFrontendHandle | null = null
let browserBridge: BrowserBridge | null = null
let stopSupervisor: (() => void) | null = null
let isQuitting = false
// The main window, once created (null during the pre-window startup steps). Held
// explicitly so the second-instance handler can target it directly instead of
// guessing from window URLs.
let mainWindow: Electron.BrowserWindow | null = null
// True once a system tray actually exists (createTray returned non-null). The window's
// close handler hides-to-tray ONLY while this holds — with no tray to restore it, the
// close proceeds so the app quits through the normal backend teardown instead.
let trayAvailable = false
// Exit listener for the spawn-path boot wait (main.ts): fails the health-wait race fast
// when the JVM dies before becoming healthy; removed once boot succeeds.
let onBootExit: ((code: number | null) => void) | undefined
// AOT startup cache (see backend/aot-cache.ts): the launch overlay is computed once the
// layout resolves; the trainer runs at most one background pass per session and is
// stopped on quit alongside the backend tree.
let aotJvmArgs: AotJvmArgs | undefined
let aotTrainingContext: TrainingContext | null = null
let aotTrainer: TrainingHandle | null = null
let aotTrainingScheduled = false

// Prevents an extra console window on Windows in release builds. Must run after the
// `electron` import (CommonJS require() is source-order, unlike ESM import hoisting) but
// before app.whenReady — placing it here at module top-level satisfies both.
if (process.platform === 'win32') app.setAppUserModelId('fan.summer.fengyu')

/**
 * Synchronous shell teardown for a quit: flags quitting and stops everything EXCEPT the
 * backend child (whose shutdown is sequenced separately — see the before-quit handler below).
 * Idempotent; safe to call from both before-quit and will-quit.
 */
function teardownShell() {
  isQuitting = true
  stopSupervisor?.()
  stopSupervisor = null
  // SIGTERM the idle-time AOT trainer too (orderly first — an in-flight dump still
  // lands and the trainer escalates on its own; an unstarted timer dies with the unref).
  aotTrainer?.stop()
  aotTrainer = null
  browserBridge?.close()
  browserBridge = null
  devFrontend?.stop()
}

/** Crash-path teardown: SIGTERM now — the caller force-kills immediately after (app.exit bypasses quit events). */
function killBackend() {
  teardownShell()
  backendChild?.kill()
}

// Crash backstops. A rejection (a missed `await` in some event handler) is logged and
// tolerated — the shell keeps running. An uncaught exception is unrecoverable: log it,
// tear down the sidecar (app.exit bypasses before-quit/will-quit, so cleanup must be
// explicit) and exit non-zero so the backend JVM can never be orphaned by a shell crash.
process.on('unhandledRejection', (reason) => {
  logger.error(
    `[desktop] unhandled rejection: ${reason instanceof Error ? reason.stack ?? reason.message : String(reason)}`,
  )
})
process.on('uncaughtException', (err) => {
  logger.error(`[desktop] uncaught exception: ${err instanceof Error ? err.stack ?? err.message : String(err)}`)
  try {
    killBackend()
    // killBackend signals SIGTERM and arms a 5s escalation that app.exit below will never
    // let fire — escalate synchronously so the JVM tree dies with the shell.
    backendChild?.forceKill()
  } catch {
    // Best-effort cleanup on an already-crashing process; still exit below.
  }
  app.exit(1)
})

/**
 * In dev, auto-start the Vite frontend (the old Tauri shell did this via `beforeDevCommand`).
 * Resolves once Vite is listening on :5173; throws if it fails to come up. Idempotent: if Vite is
 * already running, returns without spawning. The spawned process is stopped on app quit.
 */
async function ensureDevFrontend(): Promise<void> {
  // __dirname in dev is <repo>/desktop/electron/dist → repo root is three levels up.
  const repoRoot = join(__dirname, '..', '..', '..')
  devFrontend = await startDevFrontend({ repoRoot, log: (m) => logger.info(m), isQuitting: () => isQuitting })
  if (isQuitting) {
    devFrontend.stop()
    throw new Error('frontend startup cancelled')
  }
  // Vite serves `?v=`-versioned dev modules with `Cache-Control: immutable`, and the
  // Electron session persists that HTTP cache across dev sessions. After a Vite config or
  // dependency change, a reload can replay a module from the OLD dev-server era whose
  // imports point at optimize-deps artifacts that no longer exist — the renderer then
  // white-screens behind a wall of `*.sass` 404s (e.g. `vuetify_components.js` /
  // `.vite/deps/*.sass` after the vuetify pre-bundle exclusion fix) and stays broken on
  // every reload. The dev HTTP cache holds nothing worth keeping, so drop it on each
  // shell start and let the window fetch today's module graph fresh.
  await session.defaultSession.clearCache()
  logger.info('[desktop] dev: session HTTP cache cleared')
}

/**
 * Dev mode that connects to a backend you started yourself (IDE / `mvn spring-boot:run`),
 * instead of the shell spawning one from a jar. The shell does NOT spawn java, generate a token,
 * run the SETUP→APP supervisor, or manage the backend lifetime — you own it. Matches the backend's
 * auth-disabled-when-no-token rule: when you start the backend WITHOUT `--token=`,
 * `TokenAuthFilter` disables auth, so the shell passes an empty token and the SPA's empty-token
 * fallback lines up. If you DID start the backend with `--token=<t>`, also set FENGYU_TOKEN=<t>.
 *
 * Resolution (dev only — packaged builds always spawn their own):
 *   - FENGYU_DEV_BACKEND set        → connect to that URL (must be a valid http(s) URL).
 *   - FENGYU_DEV_BACKEND=disabled   → opt OUT of the default; fall through to the FENGYU_JAR
 *                                     spawn path (self-contained dev).
 *   - neither FENGYU_DEV_BACKEND nor FENGYU_JAR set → DEFAULT: connect to the IDE backend at
 *                                     http://127.0.0.1:24056 (the conventional dev backend port).
 * Set FENGYU_DEV_BACKEND=disabled (or just set FENGYU_JAR) to use the jar-spawn path instead.
 */
const DEFAULT_DEV_BACKEND = 'http://127.0.0.1:24056'

function devBackendUrl(): string | null {
  if (app.isPackaged) return null
  const url = process.env.FENGYU_DEV_BACKEND
  if (url === 'disabled') return null
  // An explicit jar opts into the self-contained spawn path. This also makes
  // the Playwright launch test exercise the real shell → Java lifecycle.
  if (!url && process.env.FENGYU_JAR) return null
  if (!url) return DEFAULT_DEV_BACKEND // default: connect to the IDE-started backend
  try {
    const parsed = new URL(url)
    if (!['http:', 'https:'].includes(parsed.protocol)) {
      throw new Error('unsupported protocol')
    }
    return url.replace(/\/$/, '')
  } catch {
    logger.error(`[desktop] ignoring invalid FENGYU_DEV_BACKEND="${url}" (not a URL); falling back to ${DEFAULT_DEV_BACKEND}`)
    return DEFAULT_DEV_BACKEND
  }
}

// Privileged scheme registration MUST precede app.whenReady (Electron throws if the
// scheme is already in use). The handler itself is attached later, after ready.
registerAppScheme()

async function bootstrap(): Promise<void> {
  // Sender guard for every privileged ipcMain channel (ipc/sender-guard.ts). Installed
  // FIRST so all registrations below share it; the resolver reads mainWindow lazily, and
  // no renderer can send before the window loads anyway.
  setMainWindowResolver(() => mainWindow)
  registerDialogIpc()
  registerLogIpc(logger)
  registerArtifactIpc(async artifactId => {
    // Path resolution stays server-side: the renderer sends an opaque artifact id and
    // only the backend's registry knows the confirmed saved location (7.4).
    const apiBase = process.env.FENGYU_API_BASE ?? 'http://127.0.0.1:24056'
    const token = process.env.FENGYU_TOKEN ?? ''
    const response = await fetch(
      `${apiBase}/api/ai/chat-resources/artifacts/${encodeURIComponent(artifactId)}/path`,
      token ? { headers: { 'X-FengYu-Token': token } } : undefined,
    )
    if (!response.ok) {
      throw new Error(`The backend rejected the artifact lookup (HTTP ${response.status})`)
    }
    return (await response.json()) as { path: string }
  })
  registerExternalIpc()
  handleAppProtocol(join(__dirname, '../frontend-dist'))
  registerDisplayMediaHandler()
  registerPermissionHandlers()
  let updateChannelReady: Promise<void> = Promise.resolve()
  registerUpdateIpc(() => updateChannelReady)
  // Launch-perf sink: the renderer's boot gate sends T4–T6 once; merged with the
  // main-process T0–T3 into a single desktop.log line (ipc/perf.ts).
  registerPerfIpc(logger)
  // The window is created later in bootstrap; the closure reads it lazily so a
  // notification click always focuses the live main window.
  registerNotificationIpc(() => mainWindow)
  const startupStartedAt = Date.now()
  const theme = initializeAppearance(logger)
  process.env.FENGYU_THEME = theme

  const reportStage = (stage: BootStage) => {
    logger.info(`[desktop] startup ${stage} +${Date.now() - startupStartedAt} ms`)
  }

  const isPackaged = app.isPackaged
  // Start Vite's module/Sass warmup while the backend boots. Attach a rejection
  // handler immediately: backend readiness can take longer than a Vite failure.
  const frontendReady = isPackaged
    ? Promise.resolve(null)
    : ensureDevFrontend().then(() => null, (error: unknown) => ({ error }))

  // ── Dev: connect to an externally-started backend ───────────────────────────
  const externalBackend = devBackendUrl()
  if (externalBackend) {
    logger.info(`[desktop] dev mode: connecting to external backend at ${externalBackend} (no spawn, no supervisor)`)
    // Wait for it to be ready (same poll as the spawned path). /api/health bypasses auth,
    // so an empty token works whether or not you started the backend with --token=.
    const token = process.env.FENGYU_TOKEN ?? ''
    process.env.FENGYU_API_BASE = externalBackend
    process.env.FENGYU_TOKEN = token
    process.env.FENGYU_SETUP_MODE = ''
    // In-app boot surface for this path too (mirrors the spawn branch): without
    // these handlers the renderer's boot:get-state / endpoint:get pulls reject
    // with "No handler registered", the startup screen gets no stage pushes,
    // and the failure screen's retry/logs buttons would be dead channels.
    let devBootAttempt = 1
    const reprobeExternalBackend = async (onProgress: (stage: BootStage) => void): Promise<void> => {
      await pollHealth({ baseUrl: externalBackend, shouldCancel: () => isQuitting, onProgress })
    }
    const devBootIpc = registerBootIpc({
      logger,
      getWindow: () => mainWindow,
      logsDir: resolveLogDir,
      // The IDE owns the process — retry means "wait for it again", not respawn.
      onRetry: async () => {
        devBootAttempt += 1
        devBootIpc.pushBootState({ phase: 'booting', stage: 'spawning', attempt: devBootAttempt })
        try {
          await reprobeExternalBackend(pushDevStage)
        } catch (err) {
          const failure = classifyBootFailure(err)
          devBootIpc.pushBootState({
            phase: 'failed',
            reason: failure.reason,
            exitCode: failure.exitCode,
            detail: err instanceof Error ? err.message : String(err),
            attempt: devBootAttempt,
          })
          throw err
        }
      },
      onAckTimeout: (state) => {
        dialog.showErrorBox(
          'Backend not reachable',
          `Could not reach the external backend at ${externalBackend}.\n${state.detail ?? ''}\n\n` +
            'Start it in your IDE (or `mvn -pl FengYu spring-boot:run`), then relaunch the desktop shell.',
        )
        app.quit()
      },
    })
    const pushDevStage = (stage: BootStage) => {
      reportStage(stage)
      devBootIpc.pushBootState({ phase: 'booting', stage })
    }
    // Browser automation bridge: start it here too (not only in the spawn branch) so the
    // IDE-started backend can drive a real BrowserWindow. The IDE JVM must in turn be
    // launched with `-Dfengyu.desktop=true` and these two env vars. A *fixed* port + token
    // (read from env below) is required: the JVM is launched by IntelliJ, which cannot
    // learn a random OS port after the fact. When unset, the bridge still starts on a
    // random port — logged for ad-hoc use — but browser_* calls from the IDE backend will
    // stay in degraded mode (it does not know the address).
    const bridgePort = Number.parseInt(process.env.FENGYU_BROWSER_BRIDGE_PORT ?? '', 10)
    const bridgeToken = process.env.FENGYU_BROWSER_BRIDGE_TOKEN
    try {
      browserBridge = await startBrowserBridge(new BrowserSession(), {
        port: Number.isFinite(bridgePort) && bridgePort > 0 ? bridgePort : undefined,
        token: bridgeToken && bridgeToken.length > 0 ? bridgeToken : undefined,
      })
      process.env.FENGYU_BROWSER_BRIDGE_PORT = String(browserBridge.port)
      process.env.FENGYU_BROWSER_BRIDGE_TOKEN = browserBridge.token
      // The token authorizes browser automation from the backend; keep its value out of
      // desktop.log — log presence/length only. In this dev path you supplied it yourself
      // via FENGYU_BROWSER_BRIDGE_TOKEN (or it was generated for ad-hoc use).
      logger.info(
        `[desktop] browser bridge ready on 127.0.0.1:${browserBridge.port} (token present, ${browserBridge.token.length} chars, redacted). ` +
          'For the IDE backend to use it, set VM option `-Dfengyu.desktop=true` and env ' +
          `FENGYU_BROWSER_BRIDGE_PORT=${browserBridge.port} FENGYU_BROWSER_BRIDGE_TOKEN=<your configured token>.`,
      )
    } catch (err) {
      // Bridge is an adjunct to the IDE backend, not a prerequisite — keep booting so the
      // user can still use the shell for non-browser work and see the warning in the log.
      logger.warn(`[desktop] browser bridge not started: ${err instanceof Error ? err.message : String(err)}`)
    }
    pushDevStage('spawning')

    // The main window opens FIRST — its in-app startup screen (static boot shell →
    // BootGate) owns the wait that the retired splash window used to cover. Dev
    // must have Vite listening before the window loads its URL.
    try {
      const failure = await frontendReady
      if (failure) throw failure.error
    } catch (err) {
      dialog.showErrorBox(
        'Frontend not reachable',
        `Could not start the Vite frontend dev server.\n${err instanceof Error ? err.message : String(err)}\n\n` +
          'Run `cd frontend && yarn install && yarn run dev` manually, then relaunch the desktop shell.',
      )
      app.quit()
      return
    }

    markMainWindowLoad() // T3: main-window load begins (renderer T4–T6 follow)
    const win = createMainWindow({
      apiBase: externalBackend,
      token,
      theme,
      onHideToTray: () => logger.info('[desktop] window hidden to tray'),
      shouldHideToTray: () => trayAvailable,
      isDev: true,
      isQuitting: () => isQuitting,
      onMainReady: () => {
        logger.info(`[desktop] startup main-ready +${Date.now() - startupStartedAt} ms`)
      },
    })
    mainWindow = win
    trayAvailable =
      createTray(
        win,
        () => {
          /* external backend is owned by the IDE; nothing to kill on quit */
        },
        (m) => logger.info(m),
      ) !== null
    // The endpoint is known up-front here (unlike the spawn path's port
    // resolution) — register the handoff and land it immediately so the
    // renderer's pull finds it.
    registerEndpointIpc({ getWindow: () => mainWindow }).pushEndpoint({ apiBase: externalBackend, token })

    // No token on the health probe: /api/health is token-bypassed (see util/health.ts).
    // The SPA's boot gate runs its own poll in parallel and takes over on success.
    try {
      await pollHealth({ baseUrl: externalBackend, shouldCancel: () => isQuitting, onProgress: pushDevStage })
    } catch (err) {
      // Hand recovery to the in-app failure screen (retry re-polls; the native
      // dialog + quit stays as the renderer-never-ACKs fallback in ipc/boot.ts).
      const failure = classifyBootFailure(err)
      devBootIpc.pushBootState({
        phase: 'failed',
        reason: failure.reason,
        exitCode: failure.exitCode,
        detail: err instanceof Error ? err.message : String(err),
        attempt: devBootAttempt,
      })
      return
    }
    return
  }

  // ── Packaged / jar-dev: spawn the backend ───────────────────────────────────
  const layout = resolveLayout(isPackaged, process.resourcesPath, process.env)

  // AOT startup-cache decision (packaged builds only — dev spawns a bare FENGYU_JAR
  // backend where background training would be noise). Bundled CI cache first, then a
  // self-trained cache; neither valid → plain launch + one background training pass
  // after boot so the NEXT launch is fast. All I/O failures degrade to "no cache".
  if (isPackaged) {
    const javaBin = resolveJava(layout)
    // Trust the bundled JRE's identity ONLY when resolveJava actually resolved to it:
    // a corrupted install (jre/bin/java quarantined while release + cache survive)
    // would otherwise put a JDK-25 cache flag onto a PATH JDK 21 — an unrecognized
    // -XX option aborts that JVM outright. When the bundled binary is gone, degrade
    // to the lite path (probe PATH java, self-train) exactly like a JRE-less install.
    const bundledJreActive = layout.jre !== undefined && javaBin === layout.jre
    const aotPrepared = prepareAotLaunch({
      jarPath: layout.jar,
      // layout.jre is <resources>/jre/bin/java — the cache lives two levels up, beside
      // the JVM it was trained with (see train-aot-cache.sh).
      jreDir: bundledJreActive ? dirname(dirname(layout.jre!)) : undefined,
      javaBin,
      runtimeRootDir: runtimeRoot(),
      appVersion: app.getVersion(),
    })
    if (aotPrepared.plan.mode === 'use') {
      aotJvmArgs = {
        flags: aotPrepared.plan.flags,
        classpath: aotPrepared.plan.classpath,
        cwd: aotPrepared.plan.cwd,
      }
      logger.info(`[desktop] AOT startup cache active (source: ${aotPrepared.plan.source})`)
    } else {
      aotTrainingContext = aotPrepared.training
      logger.info(`[desktop] AOT startup cache absent${aotPrepared.training ? '; background training scheduled after boot' : ' (JVM unsupported)'}`)
    }
  }

  const token = genToken()
  process.env.FENGYU_TOKEN = token
  process.env.FENGYU_API_BASE = '' // handed to the renderer via endpoint:ready once the port resolves
  // Browser automation bridge: must start before the JVM spawn so the backend inherits
  // the bridge port/token via process.env and fengyu.desktop=true enables the host tool.
  // Same tolerance as the dev-connect path above: the bridge is an adjunct (the backend's
  // browser_* tools degrade gracefully without it), so a startup failure — e.g. the port
  // already in use — must not take the whole app down.
  try {
    browserBridge = await startBrowserBridge(new BrowserSession())
    process.env.FENGYU_BROWSER_BRIDGE_PORT = String(browserBridge.port)
    process.env.FENGYU_BROWSER_BRIDGE_TOKEN = browserBridge.token
  } catch (err) {
    browserBridge = null
    logger.warn(`[desktop] browser bridge not started: ${err instanceof Error ? err.message : String(err)}`)
  }
  // ── In-app boot-failure recovery (P2) ─────────────────────────────────────────
  // The main window exists for the WHOLE boot (see below), so boot failures are
  // surfaced in the app (boot:state push + retry IPC) instead of a native dialog +
  // quit. The native dialog survives as the fallback for a renderer that never ACKs
  // (see ipc/boot.ts) — exactly the old behavior for that path.
  //
  // Registered BEFORE the window + spawn: stage pushes start the moment the window
  // exists, and a push to a not-yet-loaded page is dropped — the renderer re-pulls
  // boot:get-state on mount, so registering early loses nothing.
  const bootIpc = registerBootIpc({
    logger,
    getWindow: () => mainWindow,
    logsDir: resolveLogDir,
    onRetry: async () => {
      bootIpc.pushBootState({ phase: 'booting', stage: 'spawning', attempt: bootAttempt + 1 })
      try {
        await retryBackendBoot()
      } catch (err) {
        const failure = classifyBootFailure(err)
        bootIpc.pushBootState({
          phase: 'failed',
          reason: failure.reason,
          exitCode: failure.exitCode,
          detail: err instanceof Error ? err.message : String(err),
          attempt: bootAttempt + 1,
        })
        throw err
      }
    },
    onAckTimeout: (state) => {
      dialog.showErrorBox(
        'Backend stopped',
        `The FengYu backend did not become ready.\n${state.detail ?? ''}\n\n` +
          'Please relaunch Infinia. If the problem persists, check the logs at ' +
          `${resolveLogDir()}.`,
      )
      app.quit()
    },
  })
  const pushStage = (stage: BootStage) => {
    reportStage(stage)
    bootIpc.pushBootState({ phase: 'booting', stage })
  }

  // The main window opens FIRST — before the spawn — so its in-app startup screen
  // (static boot shell → BootGate's StartupScreen) owns the whole boot surface the
  // retired splash window used to cover, JVM cold start included. The document
  // loads before the backend port is known; the renderer receives the endpoint via
  // endpoint:ready and its boot-gate health poll starts answering once the backend
  // is up. Dev must have Vite listening before the window loads its URL.
  if (!isPackaged) {
    try {
      const failure = await frontendReady
      if (failure) throw failure.error
    } catch (err) {
      dialog.showErrorBox(
        'Frontend not reachable',
        `Could not start the Vite frontend dev server.\n${err instanceof Error ? err.message : String(err)}\n\n` +
          'Run `cd frontend && yarn install && yarn run dev` manually, then relaunch the desktop shell.',
      )
      app.quit()
      return
    }
  }

  markMainWindowLoad() // T3: main-window load begins (renderer T4–T6 follow)
  pushStage('spawning')
  const win = createMainWindow({
    // Empty until the spawn resolves the port (CSP: loopback wildcard, see
    // create-window.ts); the renderer gets the real endpoint via endpoint:ready.
    apiBase: '',
    token,
    theme,
    onHideToTray: () => logger.info('[desktop] window hidden to tray'),
    shouldHideToTray: () => trayAvailable,
    isDev: !isPackaged,
    isQuitting: () => isQuitting,
    onMainReady: () => {
      logger.info(`[desktop] startup main-ready +${Date.now() - startupStartedAt} ms`)
    },
  })
  mainWindow = win
  trayAvailable = createTray(win, killBackend, (m) => logger.info(m)) !== null
  const endpointIpc = registerEndpointIpc({ getWindow: () => mainWindow })

  // Spawn and read the bound port. The renderer is already up behind its boot
  // gate, so its health poll takes over the moment the endpoint lands below.
  let child: BackendChild
  let port: number
  try {
    const spawned = await spawnBackend({
      layout,
      token,
      requestedPort: 24056,
      aot: aotJvmArgs,
      onLine: logger.backendLine,
      onErrLine: logger.backendErrLine,
      shouldCancel: () => isQuitting,
      onProgress: pushStage,
      // Arm the quit-path teardown handle the moment the JVM exists: a spawn that fails
      // later (port handshake timeout) kills the child internally, and this is the only
      // way will-quit's forceKill backstop can still reach the tree if the shell quits
      // inside the kill()'s 5s SIGKILL escalation window.
      onChildSpawned: (spawnedChild) => {
        backendChild = spawnedChild
      },
    })
    child = spawned.child
    port = spawned.port
  } catch (err) {
    // A spawn failure is a broken environment (java missing, jar unreadable) —
    // the in-app failure screen's retry cannot fix it, so keep the native
    // dialog + quit. The startup screen stays behind the modal until app.quit().
    const msg = err instanceof Error ? err.message : String(err)
    if (/spawn.*java|ENOENT/i.test(msg)) {
      dialog.showErrorBox(
        'Java not found',
        'FengYu requires Java 25+ on your PATH. Please install a JRE (https://adoptium.net) ' +
          'or use the Infinia build that bundles a JRE.',
      )
    } else {
      dialog.showErrorBox('Failed to start backend', msg)
    }
    app.quit()
    return
  }

  const apiBase = `http://127.0.0.1:${port}`
  process.env.FENGYU_API_BASE = apiBase
  // Unknown until the setup probe below completes; the SPA live-probes /api/setup/status
  // once its boot gate sees a healthy backend (router/index.ts handles the null hint).
  process.env.FENGYU_SETUP_MODE = ''
  backendChild = child
  // The already-running renderer learns the endpoint now — its boot gate starts
  // polling the real backend (page loads after this point re-read the env snapshot).
  endpointIpc.pushEndpoint({ apiBase, token })
  pushStage('loading-ui')

  /** Wire post-boot supervision: env hint, SETUP→APP supervisor or APP crash guard. */
  const engageBackendRuntime = (setupMode: boolean, child: BackendChild, engagedPort: number): void => {
    process.env.FENGYU_SETUP_MODE = String(setupMode)
    // Boot reached its stable end state (APP, or SETUP awaiting the wizard): safe to
    // schedule the idle-time AOT self-training pass if this install has no cache yet.
    maybeScheduleAotTraining()

    const action = startupAction(setupMode, engagedPort)
    if (action === StartupAction.ShowWindowAndSupervise) {
      logger.info('[desktop] backend in SETUP mode; opening setup wizard')
      stopSupervisor?.()
      stopSupervisor = superviseSetupRestart({
        getChild: () => backendChild,
        setChild: (c) => {
          backendChild = c
        },
        expectedPort: engagedPort,
        isShuttingDown: () => isQuitting,
        log: logger.info,
        onFatal: (m) => {
          logger.error(`FATAL: ${m}`)
          dialog.showErrorBox(
            'Backend stopped',
            `${m}\n\nThe app cannot continue. Please relaunch Infinia and check the logs if the problem persists.`,
          )
          app.quit()
        },
        restart: () =>
          // onChildSpawned keeps the will-quit forceKill backstop armed across the
          // SETUP→APP respawn: during the restart window `backendChild` still names the
          // exited SETUP child, so a quit here would strand the fresh JVM tree.
          startBackend({ layout, token, requestedPort: engagedPort, aot: aotJvmArgs, onBackendLine: logger.backendLine, onBackendErrLine: logger.backendErrLine, shouldCancel: () => isQuitting, log: logger.warn, onChildSpawned: (spawnedChild) => { backendChild = spawnedChild } })
            .then((r) => ({ child: r.child, port: r.port, setupMode: r.setupMode })),
      })
    }

    // APP-mode crash guard: if the backend exits while the shell is still running,
    // surface a dialog instead of silently leaving the user with connection errors.
    // Alpha does NOT auto-restart (avoid restart loops); the user relaunches manually.
    // Scoped to pure APP mode (ShowWindow) to avoid conflicting with the SETUP supervisor,
    // which carries the same fatal handling across the SETUP→APP transition.
    if (action === StartupAction.ShowWindow) {
      const proc = child.process
      proc.once('exit', (code) => {
        if (isAppCrash(code, isQuitting)) {
          logger.error(`[desktop] backend exited unexpectedly (code ${code})`)
          dialog.showErrorBox(
            'Backend stopped',
            'The FengYu backend exited unexpectedly. The app cannot continue. ' +
              'Please relaunch Infinia. If the problem persists, check the logs at ' +
              `${resolveLogDir()}.`,
          )
          app.quit()
        }
      })
    }
  }

  let bootAttempt = 1
  /**
   * One background AOT self-training pass per session (see backend/aot-cache.ts): only
   * when the launch plan found no usable cache, delayed past the boot burst so its
   * extra JVM boot + ~170 MB dump write land after the UI is interactive.
   */
  const maybeScheduleAotTraining = (): void => {
    if (aotTrainingScheduled || !aotTrainingContext || isQuitting) return
    aotTrainingScheduled = true
    const timer = setTimeout(() => {
      if (isQuitting || !aotTrainingContext) return
      aotTrainer = trainAotCacheInBackground({
        context: aotTrainingContext,
        pluginsDir: join(runtimeRoot(), 'plugins'),
        log: logger.info,
      })
    }, 40_000)
    timer.unref()
  }
  /** User-initiated retry from the in-app failure screen: respawn → health → setup → supervise. */
  const retryBackendBoot = async (): Promise<void> => {
    // End any leftover backend tree synchronously first: a half-dead JVM holding the
    // port would push the respawn onto another port and stale the renderer endpoint.
    backendChild?.forceKill()
    backendChild = null
    stopSupervisor?.()
    stopSupervisor = null
    bootAttempt += 1
    // NOTE: from here on, onChildSpawned below re-arms `backendChild` the moment the
    // respawned JVM exists, and a FAILED retry deliberately leaves it armed — the quit
    // path's will-quit forceKill backstop must still reach the internally-killed tree
    // if the user quits inside kill()'s 5s SIGKILL escalation window (the same contract
    // the first-boot failure path documents below).

    // A retry backend that dies mid-wait must fail fast, exactly like the first
    // boot's exitDuringBoot race — not park behind startBackend's 120s deadline.
    // (No initializers on the closure-assigned lets: `= null` would be CFA-narrowed
    // to null at the later use sites — the same trap as the app.dock guard above.)
    let retryAbandoned = false
    let rejectExitDuringRetry: ((err: Error) => void) | undefined
    let detachExitWatcher: (() => void) | undefined
    const exitDuringRetry = new Promise<never>((_, reject) => {
      rejectExitDuringRetry = reject
    })

    let started: Awaited<ReturnType<typeof startBackend>>
    try {
      started = await Promise.race([
        startBackend({
          layout,
          token,
          requestedPort: port,
          aot: aotJvmArgs,
          onBackendLine: logger.backendLine,
          onBackendErrLine: logger.backendErrLine,
          shouldCancel: () => isQuitting || retryAbandoned,
          onProgress: pushStage,
          log: logger.warn,
          // Keep the quit-path teardown handle armed across the retry (see the NOTE above).
          onChildSpawned: (spawnedChild) => {
            backendChild = spawnedChild
          },
          onSpawn: (spawned) => {
            const proc = spawned.process
            const onExit = (code: number | null) => {
              if (isQuitting) return
              // Lets startBackend's internal health poll abort promptly too.
              retryAbandoned = true
              const err = Object.assign(new Error(`backend exited during startup (code ${code})`), {
                exitCode: code,
              })
              rejectExitDuringRetry?.(tagBootFailure(err, 'backend-exited'))
            }
            proc.once('exit', onExit)
            detachExitWatcher = () => proc.removeListener('exit', onExit)
          },
        }),
        exitDuringRetry,
      ])
    } catch (err) {
      // tagBootFailure keeps an existing tag, so the precise backend-exited /
      // port-changed reasons survive; anything else becomes retry-spawn-failed.
      throw tagBootFailure(err instanceof Error ? err : new Error(String(err)), 'retry-spawn-failed')
    } finally {
      detachExitWatcher?.()
    }
    if (started.port !== port) {
      started.child.kill()
      throw tagBootFailure(
        new Error(`retried backend moved from port ${port} to ${started.port}; the webview endpoint cannot change`),
        'port-changed',
      )
    }
    backendChild = started.child
    engageBackendRuntime(started.setupMode, started.child, started.port)
    bootIpc.pushBootState({ phase: 'ready', attempt: bootAttempt })
  }

  // Backend readiness wait — runs while the renderer's boot gate polls in parallel. A backend exit
  // during this wait fails fast (a crashed JVM would otherwise park the skeleton behind
  // the full 2 min health deadline). Removed once boot succeeds; APP-mode crash guarding
  // is attached separately below.
  const exitDuringBoot = new Promise<never>((_, reject) => {
    onBootExit = (code) => {
      if (isQuitting) return
      const err = Object.assign(new Error(`backend exited during startup (code ${code})`), {
        exitCode: code,
      })
      reject(tagBootFailure(err, 'backend-exited'))
    }
    child.process.once('exit', onBootExit)
  })

  let setupMode = false
  try {
    await Promise.race([
      (async () => {
        // No token on the health probe: /api/health is token-bypassed (see util/health.ts).
        // Each stage is tagged so the failure screen can name the real cause.
        await pollHealth({ port, shouldCancel: () => isQuitting, onProgress: pushStage })
          .catch((err: unknown) => {
            throw tagBootFailure(err instanceof Error ? err : new Error(String(err)), 'health-deadline')
          })
        setupMode = await probeSetupMode(port, token, undefined, () => isQuitting, logger.warn)
          .catch((err: unknown) => {
            throw tagBootFailure(err instanceof Error ? err : new Error(String(err)), 'setup-probe-failed')
          })
      })(),
      exitDuringBoot,
    ])
  } catch (err) {
    // Hand recovery to the in-app failure screen; the native dialog + quit stays as
    // the fallback when the renderer never ACKs (ipc/boot.ts ack timer).
    // Keep backendChild set: the quit path's will-quit backstop then guarantees the
    // backend tree dies synchronously with the shell even if SIGTERM is ignored
    // (a deadline-exceeded JVM is still alive and gets the SIGTERM here).
    backendChild?.kill()
    const failure = classifyBootFailure(err)
    bootIpc.pushBootState({
      phase: 'failed',
      reason: failure.reason,
      exitCode: failure.exitCode,
      detail: err instanceof Error ? err.message : String(err),
      attempt: bootAttempt,
    })
    return
  } finally {
    if (onBootExit) {
      child.process.removeListener('exit', onBootExit)
      onBootExit = undefined
    }
  }
  // SETUP supervisor / APP crash guard (shared with the retry path above).
  engageBackendRuntime(setupMode, child, port)

  // Load the channel alongside the renderer. Update IPC waits for this promise so
  // early renderer checks still use the persisted feed without delaying first paint.
  if (isPackaged && !setupMode) {
    updateChannelReady = bootstrapUpdateApiBaseFromBackend(apiBase, token).catch(err => {
      logger.warn(`[updater] cannot load persisted update channel: ${String(err)}`)
    })
  }

  // Non-blocking native update check — only when packaged (dev builds have no update channel).
  // Both native and renderer checks wait for the persisted channel. The .catch keeps a
  // check-time failure from surfacing as an unhandledRejection (logged and tolerated,
  // but an explicit warn names the updater in desktop.log).
  if (isPackaged) {
    void updateChannelReady
      .then(() => { if (!isQuitting) return checkForUpdates() })
      .catch((err) => {
        logger.warn(`[updater] startup update check failed: ${err instanceof Error ? err.message : String(err)}`)
      })
  }
}

app.whenReady().then(() => {
  markMainLaunchWhenReady() // T2: Electron ready; bootstrap begins below
  // The lock was acquired at module top (before the cwd bootstrap): a secondary instance
  // already called app.quit() inside acquireSingleInstanceLock — never bootstrap it.
  if (!isPrimaryInstance) return
  void bootstrap().catch((err) => {
    // Bootstrap failures that have their own recovery path (backend unreachable, frontend
    // down) already show a specific dialog inside bootstrap(); this catches everything else.
    // app.exit bypasses before-quit/will-quit, so the backend must be torn down explicitly.
    logger.error(`[desktop] bootstrap failed: ${err instanceof Error ? err.stack ?? err.message : String(err)}`)
    killBackend()
    backendChild?.forceKill() // app.exit below never lets kill()'s 5s escalation fire
    try {
      dialog.showErrorBox(
        'Startup failed',
        `Infinia failed to start and must close.\n${err instanceof Error ? err.message : String(err)}\n\n` +
          'Please relaunch Infinia. If the problem persists, check the logs at ' +
          `${resolveLogDir()}.`,
      )
    } catch {
      // Best-effort dialog: never let a dialog failure mask the exit below.
    }
    app.exit(1)
  })
})

// Clean up the spawned backend + dev Vite on quit. before-quit covers Cmd+Q / tray Quit /
// app.quit() and runs the graceful sequence from desktop/graceful-quit.ts: SIGTERM the backend
// tree, wait (capped ~2.5s) for it to exit so Spring Boot can flush, then force-kill and re-quit.
// Update install-restarts (portable apply / quitAndInstall) skip the wait via markUpdateInstallRestart.
// will-quit fires on ALL exit paths (including forceful ones where before-quit's async wait never
// completes) and is the backstop that guarantees the backend tree and the detached Vite process
// group die with the shell — forceKill() and stop() are idempotent, so calling them from both is safe.
app.on(
  'before-quit',
  createGracefulQuitHandler({
    getChild: () => backendChild,
    onTeardown: teardownShell,
    // Dual sink: desktop.log (always) + update.log (so an update-restart trace shows the quit
    // chain reached before-quit — the last update.log line then marks exactly where it died).
    log: (m) => {
      logger.info(m)
      logUpdate(`[quit] ${m.replace('[desktop] ', '')}`)
    },
  }),
)
app.on('will-quit', () => {
  logUpdate('[quit] will-quit reached — final backend force-kill, exiting now')
  // Final backstop on every exit path: before-quit's graceful wait may never complete (or was
  // skipped), and tree-kill's async SIGKILL enumeration may not get to run after this handler
  // returns — the direct-child signal inside forceKill() is the synchronous guarantee that the
  // backend JVM itself dies before this process exits. No-op once the child has exited.
  backendChild?.forceKill()
  devFrontend?.stop()
})

// Keep the app (and tray) alive on macOS even after the last window closes. Registering the
// listener at all suppresses Electron's default quit-on-all-closed, so the no-op is gated on
// macOS only — every other platform intentionally keeps the default quit when the last window
// closes (which then tears the backend down through before-quit above).
if (process.platform === 'darwin') {
  app.on('window-all-closed', () => {
    // Suppress the default quit only while a tray exists to fall back to. Without one
    // (unreadable tray assets), staying alive would strand a windowless, trayless shell
    // with a live backend — quit instead so the normal teardown runs.
    if (!trayAvailable) app.quit()
  })
}
