import { app, dialog } from 'electron'
import { join } from 'node:path'
import { resolveLayout } from './backend/runtime-layout'
import { genToken } from './util/token'
import { startBackend } from './backend/orchestrator'
import { isAppCrash, startupAction, StartupAction, superviseSetupRestart, type BackendChild } from './backend/supervisor'
import { pollHealth } from './util/health'
import { registerDialogIpc } from './ipc/dialog'
import { createMainWindow } from './window/create-window'
import { createSplashWindow, sendProgress, destroySplash } from './window/create-splash'
import { initLogger } from './desktop/logger'
import { acquireSingleInstanceLock } from './desktop/single-instance'
import { createTray } from './desktop/tray'
import { checkForUpdates } from './updater/auto-updater'
import { startDevFrontend, type DevFrontendHandle } from './desktop/dev-frontend'
import { initializeAppearance } from './desktop/appearance'

const logger = initLogger()
let backendChild: BackendChild | null = null
let devFrontend: DevFrontendHandle | null = null
let stopSupervisor: (() => void) | null = null
let isQuitting = false

// Prevents an extra console window on Windows in release builds. Must run after the
// `electron` import (CommonJS require() is source-order, unlike ESM import hoisting) but
// before app.whenReady — placing it here at module top-level satisfies both.
if (process.platform === 'win32') app.setAppUserModelId('fan.summer.fengyu')

function killBackend() {
  isQuitting = true
  stopSupervisor?.()
  stopSupervisor = null
  backendChild?.kill()
  devFrontend?.stop()
}

/**
 * In dev, auto-start the Vite frontend (the old Tauri shell did this via `beforeDevCommand`).
 * Resolves once Vite is listening on :5173; throws if it fails to come up. Idempotent: if Vite is
 * already running, returns without spawning. The spawned process is stopped on app quit.
 */
async function ensureDevFrontend(): Promise<void> {
  // __dirname in dev is <repo>/desktop/electron/dist → repo root is three levels up.
  const repoRoot = join(__dirname, '..', '..', '..')
  devFrontend = await startDevFrontend({ repoRoot, log: (m) => logger.info(m), isQuitting: () => isQuitting })
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
          'Run `cd frontend && npm install && npm run dev` manually, then relaunch the desktop shell.',
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
            '<user home>/.fengyu/logs/.',
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
          'Run `cd frontend && npm install && npm run dev` manually, then relaunch the desktop shell.',
      )
      app.quit()
      return
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

  createTray(win, killBackend)

  // Non-blocking update check — only when packaged (dev builds have no update channel).
  if (isPackaged) void checkForUpdates()
}

app.whenReady().then(() => {
  const locked = acquireSingleInstanceLock((existing) => {
    if (existing) {
      existing.show()
      existing.focus()
    }
  })
  if (!locked) return
  void bootstrap()
})

// Clean up the spawned backend + dev Vite on quit. before-quit covers Cmd+Q / tray Quit / app.quit();
// will-quit fires on ALL exit paths (including some forceful ones where before-quit's async work
// might not complete) and is the backstop that guarantees the detached Vite process group dies with
// the shell — stop() is idempotent (guards on child.killed), so calling it from both is safe.
app.on('before-quit', killBackend)
app.on('will-quit', () => devFrontend?.stop())

// Keep the app (and tray) alive on macOS even after the last window closes.
// The 'window-all-closed' listener receives no event arg in these Electron
// typings (signature is `() => void`); on macOS the default already does not
// quit, so an explicit no-op is sufficient to keep the tray alive. On other
// platforms we intentionally let the default quit-on-all-closed stand.
app.on('window-all-closed', () => {
  // no-op: prevent default quit so the tray remains (macOS default behavior)
})
