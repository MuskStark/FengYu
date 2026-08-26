import { app, dialog, session } from 'electron'
import { join } from 'node:path'
import { resolveLayout } from './backend/runtime-layout'
import { genToken } from './util/token'
import { startBackend } from './backend/orchestrator'
import { isAppCrash, startupAction, StartupAction, superviseSetupRestart, type BackendChild } from './backend/supervisor'
import { pollHealth } from './util/health'
import { registerDialogIpc } from './ipc/dialog'
import { registerDisplayMediaHandler } from './ipc/displayMedia'
import { registerPermissionHandlers } from './window/permission-handlers'
import { registerUpdateIpc } from './ipc/update'
import { registerNotificationIpc } from './ipc/notification'
import { createMainWindow } from './window/create-window'
import { createSplashWindow, sendProgress, destroySplash } from './window/create-splash'
import { initLogger } from './desktop/logger'
import { acquireSingleInstanceLock } from './desktop/single-instance'
import { createTray } from './desktop/tray'
import { createGracefulQuitHandler } from './desktop/graceful-quit'
import { checkForUpdates } from './updater/auto-updater'
import { bootstrapUpdateApiBaseFromBackend } from './updater/update-feed'
import { logUpdate } from './updater/update-log'
import { startDevFrontend, type DevFrontendHandle } from './desktop/dev-frontend'
import { initializeAppearance } from './desktop/appearance'
import { applyUosLaunchPolicy } from './desktop/uos'
import { BrowserSession } from './browser/session'
import { startBrowserBridge, type BrowserBridge } from './browser/bridge'

// UOS no-sandbox policy: must run BEFORE initLogger below — it chdirs to the user's home (a
// menu-launched UOS app starts with cwd `/`, unwritable for non-root, and <cwd>/.fengyu would
// crash the logger) and appends `no-sandbox` (must precede app.whenReady). No-op unless this
// is the packaged UOS artifact (fengyu.uos baked by electron-builder.uos.yml).
const uosLaunch = applyUosLaunchPolicy()

const logger = initLogger()
if (uosLaunch) {
  logger.info('[desktop] UOS build: no-sandbox mode enabled, working directory re-anchored to the user home')
}
let backendChild: BackendChild | null = null
let devFrontend: DevFrontendHandle | null = null
let browserBridge: BrowserBridge | null = null
let stopSupervisor: (() => void) | null = null
let isQuitting = false
// The main window, once created (null during splash-only startup). Held explicitly so the
// second-instance handler can target it directly instead of guessing from window URLs.
let mainWindow: Electron.BrowserWindow | null = null

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

async function bootstrap(): Promise<void> {
  registerDialogIpc()
  registerDisplayMediaHandler()
  registerPermissionHandlers()
  registerUpdateIpc()
  // The window is created later in bootstrap; the closure reads it lazily so a
  // notification click always focuses the live main window.
  registerNotificationIpc(() => mainWindow)
  const startupStartedAt = Date.now()
  const theme = initializeAppearance(logger)
  process.env.FENGYU_THEME = theme

  const reportProgress = (splash: Electron.BrowserWindow | null, stage: Parameters<typeof sendProgress>[1]) => {
    logger.info(`[desktop] startup ${stage} +${Date.now() - startupStartedAt} ms`)
    sendProgress(splash, stage)
  }

  // Show the splash immediately — before any backend work — so the user sees
  // feedback during the JVM cold start + Spring context init (the longest gap).
  const splash = createSplashWindow({ logger, theme })

  const isPackaged = app.isPackaged

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
    reportProgress(splash, 'spawning')
    try {
      await pollHealth({ baseUrl: externalBackend, token, shouldCancel: () => isQuitting, onProgress: (s) => reportProgress(splash, s) })
    } catch (err) {
      destroySplash(splash)
      dialog.showErrorBox(
        'Backend not reachable',
        `Could not reach the external backend at ${externalBackend}.\n${err instanceof Error ? err.message : String(err)}\n\n` +
          'Start it in your IDE (or `mvn -pl FengYu spring-boot:run`), then relaunch the desktop shell.',
      )
      app.quit()
      return
    }

    try {
      await ensureDevFrontend()
    } catch (err) {
      destroySplash(splash)
      dialog.showErrorBox(
        'Frontend not reachable',
        `Could not start the Vite frontend dev server.\n${err instanceof Error ? err.message : String(err)}\n\n` +
          'Run `cd frontend && yarn install && yarn run dev` manually, then relaunch the desktop shell.',
      )
      app.quit()
      return
    }

    reportProgress(splash, 'loading-ui')
    const win = createMainWindow({
      apiBase: externalBackend,
      token,
      theme,
      onHideToTray: () => logger.info('[desktop] window hidden to tray'),
      isDev: true,
      isQuitting: () => isQuitting,
      onMainReady: () => {
        logger.info(`[desktop] startup main-ready +${Date.now() - startupStartedAt} ms`)
        destroySplash(splash)
      },
    })
    mainWindow = win
    createTray(win, () => {
      /* external backend is owned by the IDE; nothing to kill on quit */
    })
    return
  }

  // ── Packaged / jar-dev: spawn the backend ───────────────────────────────────
  const layout = resolveLayout(isPackaged, process.resourcesPath, process.env)

  const token = genToken()
  process.env.FENGYU_TOKEN = token
  process.env.FENGYU_API_BASE = '' // set after we know the port
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
  reportProgress(splash, 'spawning')

  let started
  try {
    started = await startBackend({
      layout,
      token,
      requestedPort: 24056,
      onBackendLine: logger.backendLine,
      shouldCancel: () => isQuitting,
      onProgress: (s) => reportProgress(splash, s),
    })
  } catch (err) {
    destroySplash(splash)
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
  process.env.FENGYU_SETUP_MODE = String(started.setupMode)
  backendChild = started.child

  const action = startupAction(started.setupMode, started.port)

  if (action === StartupAction.ShowWindowAndSupervise) {
    logger.info('[desktop] backend in SETUP mode; opening setup wizard')
    stopSupervisor = superviseSetupRestart({
      getChild: () => backendChild,
      setChild: (c) => {
        backendChild = c
      },
      expectedPort: started.port,
      isShuttingDown: () => isQuitting,
      onFatal: (m) => {
        logger.error(`FATAL: ${m}`)
        dialog.showErrorBox(
          'Backend stopped',
          `${m}\n\nThe app cannot continue. Please relaunch Infinia and check the logs if the problem persists.`,
        )
        app.quit()
      },
      restart: () =>
        startBackend({ layout, token, requestedPort: started.port, onBackendLine: logger.backendLine, shouldCancel: () => isQuitting })
          .then((r) => ({ child: r.child, port: r.port, setupMode: r.setupMode })),
    })
  }

  // APP-mode crash guard: if the backend exits while the shell is still running,
  // surface a dialog instead of silently leaving the user with connection errors.
  // Alpha does NOT auto-restart (avoid restart loops); the user relaunches manually.
  // Scoped to pure APP mode (ShowWindow) to avoid conflicting with the SETUP supervisor,
  // which carries the same fatal handling across the SETUP→APP transition.
  if (action === StartupAction.ShowWindow && backendChild) {
    const proc = backendChild.process
    proc.once('exit', (code) => {
      if (isAppCrash(code, isQuitting)) {
        logger.error(`[desktop] backend exited unexpectedly (code ${code})`)
        dialog.showErrorBox(
          'Backend stopped',
          'The FengYu backend exited unexpectedly. The app cannot continue. ' +
            'Please relaunch Infinia. If the problem persists, check the logs at ' +
            '<program working directory>/.fengyu/logs/.',
        )
        app.quit()
      }
    })
  }

  if (!isPackaged) {
    try {
      await ensureDevFrontend()
    } catch (err) {
      destroySplash(splash)
      dialog.showErrorBox(
        'Frontend not reachable',
        `Could not start the Vite frontend dev server.\n${err instanceof Error ? err.message : String(err)}\n\n` +
          'Run `cd frontend && yarn install && yarn run dev` manually, then relaunch the desktop shell.',
      )
      app.quit()
      return
    }
  }

  // Resolve the persisted update channel before creating the renderer. StatusBar performs its
  // automatic update check as soon as the window mounts; if this local bootstrap were left in the
  // background, a Windows portable build could race it, probe unreachable GitHub, and never retry
  // FY-Proxy. This is a loopback-only settings read and failures preserve the launch-time env.
  if (isPackaged && !started.setupMode) {
    try {
      await bootstrapUpdateApiBaseFromBackend(apiBase, token)
    } catch (err) {
      logger.warn(`[updater] cannot load persisted update channel: ${String(err)}`)
    }
  }

  reportProgress(splash, 'loading-ui')
  const win = createMainWindow({
    apiBase,
    token,
    theme,
    onHideToTray: () => logger.info('[desktop] window hidden to tray'),
    isDev: !isPackaged,
    isQuitting: () => isQuitting,
    onMainReady: () => {
      logger.info(`[desktop] startup main-ready +${Date.now() - startupStartedAt} ms`)
      destroySplash(splash)
    },
  })
  mainWindow = win
  createTray(win, killBackend)

  // Non-blocking native update check — only when packaged (dev builds have no update channel).
  // APP mode has already loaded the persisted FY-Proxy address before the renderer was created,
  // so both this probe and StatusBar's automatic probe see the same channel.
  if (isPackaged) {
    void checkForUpdates()
  }
}

app.whenReady().then(() => {
  const locked = acquireSingleInstanceLock(
    (existing) => {
      if (existing) {
        existing.show()
        existing.focus()
      }
    },
    () => mainWindow,
  )
  if (!locked) return
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
          '<program working directory>/.fengyu/logs/.',
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
    // no-op: prevent default quit so the tray remains (macOS default behavior)
  })
}
