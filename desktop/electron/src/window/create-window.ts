import { BrowserWindow } from 'electron'
import { join } from 'node:path'

export interface CreateWindowOptions {
  apiBase: string
  token: string
  /** Called when the user clicks the close button (we hide-to-tray instead of closing). */
  onHideToTray: () => void
  isDev: boolean
}

/**
 * Create the main BrowserWindow. 1280×820, min 960×640, matches the previous Rust window.
 * contextIsolation + sandbox on; nodeIntegration off — standard secure posture.
 *
 * The preload script (`dist/window/preload.js`) reads `apiBase`/`token` from `process.env`
 * (set in main.ts before window creation) and exposes them on `window.fengyu` via
 * contextBridge. `apiBase`/`token` in the options are not used directly here but are
 * kept on the signature for clarity (and for future main-process consumers).
 */
export function createMainWindow(opts: CreateWindowOptions): BrowserWindow {
  void opts.apiBase
  void opts.token

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
