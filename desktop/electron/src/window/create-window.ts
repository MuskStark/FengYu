import { BrowserWindow, session } from 'electron'
import { join } from 'node:path'

export interface CreateWindowOptions {
  apiBase: string
  token: string
  /** Called when the user clicks the close button (we hide-to-tray instead of closing). */
  onHideToTray: () => void
  isDev: boolean
  /**
   * Returns true once the app is genuinely quitting (tray "Quit" / Cmd+Q / Alt+F4).
   *
   * The close handler hides-to-tray and calls preventDefault() ONLY when this is
   * false. When true, it lets the close proceed so `app.quit()` can complete —
   * otherwise the unconditional preventDefault() in here would silently cancel
   * the quit that before-quit/app.quit() initiated. This resolves the Task 4
   * close-vs-quit gotcha that Task 3's review deferred.
   */
  isQuitting: () => boolean
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

  // In dev the SPA is served by Vite over http(s) and lazy-loads modules/fonts/styles on demand.
  // Without an explicit CSP, Electron applies its default `default-src 'self'`, which blocks the
  // MDI font (@mdi/font) and Vite's HMR/inlined assets, spamming the console with font-violation
  // errors and breaking icon rendering. Set a dev CSP that allows the Vite origin + data: URIs +
  // ws (HMR). Packaged builds serve the SPA from the app bundle (same origin) and keep the strict
  // default — this override is dev-only.
  if (opts.isDev) {
    const csp = [
      "default-src 'self'",
      "script-src 'self' 'unsafe-inline' 'unsafe-eval'", // Vite dev needs eval for HMR/transform
      "style-src 'self' 'unsafe-inline' http://127.0.0.1:5173 http://127.0.0.1:24056 http://localhost:24056",
      "font-src 'self' data: http://127.0.0.1:5173 http://127.0.0.1:24056 http://localhost:24056",
      "img-src 'self' data: blob: http://127.0.0.1:5173 http://127.0.0.1:24056 http://localhost:24056",
      // Plugin micro-frontend UIs are served by the backend and iframed by the host. pluginAssetUrl
      // swaps the hostname (127.0.0.1 ↔ localhost) for origin isolation, so frame-src must allow BOTH
      // loopback hostnames on the backend port. child-src covers older browsers; both needed.
      "frame-src 'self' http://127.0.0.1:24056 http://localhost:24056",
      "child-src 'self' http://127.0.0.1:24056 http://localhost:24056",
      "connect-src 'self' http://127.0.0.1:5173 http://127.0.0.1:24056 http://localhost:24056 ws://127.0.0.1:5173 http://127.0.0.1:* ws://127.0.0.1:*",
      "worker-src 'self' blob:",
      "object-src 'none'",
      "base-uri 'self'",
    ].join('; ')
    session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
      callback({
        responseHeaders: {
          ...details.responseHeaders,
          'Content-Security-Policy': [csp],
        },
      })
    })
  }

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

  // Hide-to-tray instead of closing — UNLESS the app is genuinely quitting.
  // When isQuitting() is true (tray Quit / before-quit), let the close proceed
  // so app.quit() completes; otherwise preventDefault()+hide keeps the backend
  // alive in the tray.
  win.on('close', (e) => {
    if (opts.isQuitting()) return
    e.preventDefault()
    opts.onHideToTray()
    win.hide()
  })

  if (opts.isDev) {
    void win.loadURL('http://127.0.0.1:5173')
    // Auto-open DevTools in dev so runtime/console errors are visible without a keyboard shortcut.
    win.webContents.openDevTools({ mode: 'detach' })
  } else {
    void win.loadFile(join(__dirname, '../../frontend-dist/index.html'))
  }
  return win
}
