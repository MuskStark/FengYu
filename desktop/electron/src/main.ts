import { app, dialog } from 'electron'
import { resolveLayout } from './backend/runtime-layout'
import { genToken } from './util/token'
import { startBackend } from './backend/orchestrator'
import { startupAction, StartupAction, superviseSetupRestart } from './backend/supervisor'
import type { BackendChild } from './backend/supervisor'
import { registerDialogIpc } from './ipc/dialog'
import { createMainWindow } from './window/create-window'

// Prevents an extra console window on Windows in release builds.
if (process.platform === 'win32') app.setAppUserModelId('fan.summer.fengyu')

let backendChild: BackendChild | null = null
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
