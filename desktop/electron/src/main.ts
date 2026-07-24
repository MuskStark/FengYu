import { app, dialog } from 'electron'
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
let isQuitting = false

// Prevents an extra console window on Windows in release builds. Must run after the
// `electron` import (CommonJS require() is source-order, unlike ESM import hoisting) but
// before app.whenReady — placing it here at module top-level satisfies both.
if (process.platform === 'win32') app.setAppUserModelId('fan.summer.fengyu')

function killBackend() {
  isQuitting = true
  backendChild?.kill()
}

async function bootstrap(): Promise<void> {
  registerDialogIpc()

  const isPackaged = app.isPackaged
  const layout = resolveLayout(isPackaged, process.resourcesPath, process.env)

  const token = genToken()
  process.env.FENGYU_TOKEN = token
  process.env.FENGYU_API_BASE = '' // set after we know the port

  let started
  try {
    started = await startBackend({
      layout,
      token,
      requestedPort: 24056,
      onBackendLine: logger.backendLine,
      shouldCancel: () => isQuitting,
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
      setChild: (c) => {
        backendChild = c
      },
      expectedPort: started.port,
      isShuttingDown: () => isQuitting,
      onFatal: (m) => logger.error(`FATAL: ${m}`),
      restart: () =>
        startBackend({ layout, token, requestedPort: started.port, onBackendLine: logger.backendLine, shouldCancel: () => isQuitting })
          .then((r) => ({ child: r.child, port: r.port, setupMode: r.setupMode })),
    })
  }

  const win = createMainWindow({
    apiBase,
    token,
    onHideToTray: () => logger.info('[desktop] window hidden to tray'),
    isDev: !isPackaged,
    isQuitting: () => isQuitting,
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

app.on('before-quit', killBackend)

// Keep the app (and tray) alive on macOS even after the last window closes.
// The 'window-all-closed' listener receives no event arg in these Electron
// typings (signature is `() => void`); on macOS the default already does not
// quit, so an explicit no-op is sufficient to keep the tray alive. On other
// platforms we intentionally let the default quit-on-all-closed stand.
app.on('window-all-closed', () => {
  // no-op: prevent default quit so the tray remains (macOS default behavior)
})
