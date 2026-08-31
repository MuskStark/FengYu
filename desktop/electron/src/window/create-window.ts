import { BrowserWindow, shell } from 'electron'
import { join } from 'node:path'
import { APP_INDEX } from './app-protocol'
import { backgroundColorForTheme, type DesktopTheme } from '../desktop/appearance'

export interface CreateWindowOptions {
  apiBase: string
  token: string
  theme?: DesktopTheme
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
  /**
   * Called once when the main window finishes its first paint (ready-to-show).
   * Used by main.ts to tear down the splash window at the exact moment the main
   * window becomes visible — closing it earlier would leave a window-less gap.
   */
  onMainReady?: () => void
}

function backendOrigins(apiBase: string): string[] {
  if (!apiBase) return []
  try {
    const url = new URL(apiBase)
    const origins = new Set([url.origin])
    if (url.hostname === '127.0.0.1' || url.hostname === 'localhost') {
      const alternate = new URL(url.origin)
      alternate.hostname = url.hostname === '127.0.0.1' ? 'localhost' : '127.0.0.1'
      origins.add(alternate.origin)
    }
    return [...origins]
  } catch {
    return []
  }
}

export function contentSecurityPolicy(opts: Pick<CreateWindowOptions, 'apiBase' | 'isDev'>): string {
  const backends = backendOrigins(opts.apiBase)
  const devHttp = opts.isDev ? ['http://127.0.0.1:5173'] : []
  const devWs = opts.isDev ? ['ws://127.0.0.1:5173'] : []
  const script = opts.isDev
    ? "script-src 'self' 'unsafe-inline' 'unsafe-eval'"
    : "script-src 'self' 'unsafe-inline'"
  const sources = (values: string[]) => values.join(' ')
  return [
    "default-src 'self'",
    script,
    `style-src 'self' 'unsafe-inline' ${sources([...devHttp, ...backends])}`.trim(),
    `font-src 'self' data: ${sources([...devHttp, ...backends])}`.trim(),
    `img-src 'self' data: blob: ${sources([...devHttp, ...backends])}`.trim(),
    `frame-src 'self' ${sources(backends)}`.trim(),
    `child-src 'self' ${sources(backends)}`.trim(),
    `connect-src 'self' ${sources([...devHttp, ...devWs, ...backends])}`.trim(),
    "worker-src 'self' blob:",
    "object-src 'none'",
    "base-uri 'self'",
  ].join('; ')
}

function isAllowedNavigation(currentValue: string, targetValue: string): boolean {
  try {
    const current = new URL(currentValue)
    const target = new URL(targetValue)
    if (current.protocol === 'file:') {
      return target.protocol === 'file:' && target.pathname === current.pathname
    }
    return target.origin === current.origin
  } catch {
    return false
  }
}

/**
 * Create the main BrowserWindow. 1280×820, min 960×640, matches the previous Rust window.
 * The native frame stays enabled so every platform keeps its own system window controls.
 * macOS hides only the title-bar background, allowing the native traffic lights to sit over
 * the renderer like the ChatGPT desktop app.
 * contextIsolation + sandbox on; nodeIntegration off — standard secure posture.
 *
 * The preload script (`dist/window/preload.js`) reads `apiBase`/`token` from `process.env`
 * (set in main.ts before window creation) and exposes them on `window.fengyu` via
 * contextBridge. `apiBase`/`token` in the options are not used directly here but are
 * kept on the signature for clarity (and for future main-process consumers).
 */
export function createMainWindow(opts: CreateWindowOptions): BrowserWindow {
  void opts.token

  const win = new BrowserWindow({
    title: 'FengYu',
    width: 1280,
    height: 820,
    minWidth: 960,
    minHeight: 640,
    ...(process.platform === 'darwin'
      ? {
          // A true frameless content window keeps the HTML title-bar controls
          // interactive. The hidden title-bar style still initializes Electron's
          // native button proxy, which is required for custom traffic-light positioning.
          frame: false,
          titleBarStyle: 'hidden' as const,
        }
      : {}),
    // Do not expose Chromium's default white surface while the Vue bundle is
    // still loading. The window is revealed below after its first paint.
    // #0d0d0d matches the dark theme's `background` (md3-themes.ts) so the
    // native window backing never leaks a lighter strip along the right edge
    // where the renderer fails to cover the last sub-pixel.
    show: false,
    backgroundColor: backgroundColorForTheme(opts.theme ?? 'dark'),
    resizable: true,
    webPreferences: {
      preload: join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })

  // Keep the native macOS traffic lights while the renderer owns the rest of the title bar.
  // The shell explicitly cuts its drag regions around interactive controls, so these native
  // buttons no longer require an invisible system title-bar hit target.
  if (process.platform === 'darwin') {
    win.setWindowButtonVisibility(true)
    // Restoring the buttons resets their native frame, so apply the custom position last.
    // A 12px traffic light at y=18 shares the 48px window bar's y=24 centerline.
    win.setWindowButtonPosition({ x: 14, y: 18 })
  }

  const csp = contentSecurityPolicy(opts)
  win.webContents.session.webRequest.onHeadersReceived((details, callback) => {
    // Only replace the shell document's policy. Plugin iframe documents carry their own,
    // stricter CSP from PluginRuntimeController and must not inherit the host's connect/frame rules.
    if (details.resourceType !== 'mainFrame') {
      callback({ responseHeaders: details.responseHeaders })
      return
    }
    callback({
      responseHeaders: {
        ...details.responseHeaders,
        'Content-Security-Policy': [csp],
      },
    })
  })

  // Navigation guard: deny all window.open; delegate http(s) to the system browser.
  // Without this, <a target="_blank"> opens a new Electron window with the same preload,
  // and a compromised page could window.open('file://...') or navigate to an arbitrary origin.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//.test(url)) {
      void shell.openExternal(url)
    }
    return { action: 'deny' }
  })
  // Block in-page navigation to a different origin (defense against iframe/top-level redirects).
  win.webContents.on('will-navigate', (e, url) => {
    if (!isAllowedNavigation(win.webContents.getURL(), url)) e.preventDefault()
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

  win.once('ready-to-show', () => {
    if (!opts.isQuitting()) win.show()
    opts.onMainReady?.()
  })

  let loadPromise: Promise<void>
  if (opts.isDev) {
    loadPromise = Promise.resolve(win.loadURL('http://127.0.0.1:5173'))
    // Auto-open DevTools in dev so runtime/console errors are visible without a keyboard shortcut.
    // Playwright sets NODE_ENV=test; suppressing the detached DevTools window
    // there keeps ElectronApplication window selection deterministic.
    if (process.env.FENGYU_DEVTOOLS === '1' && process.env.NODE_ENV !== 'test') {
      win.webContents.openDevTools({ mode: 'detach' })
    }
  } else {
    // app:// (see app-protocol.ts): a real, non-opaque origin so the build's CSP meta
    // tag is honored — file:// loads made both header and meta CSP inert (M-6).
    loadPromise = Promise.resolve(win.loadURL(APP_INDEX))
  }
  void loadPromise.catch((err) => {
    // Keep a failed renderer diagnosable: log the load error and reveal the
    // dark-backed window instead of leaving an invisible process in the tray.
    console.error('[desktop] failed to load renderer', err)
    if (!win.isDestroyed()) win.show()
    // ready-to-show never fires when the load rejects, so tear down the splash
    // here too — otherwise it stays parked over a broken main window with no
    // way for the user to close it (frameless, focusable:false, skipTaskbar).
    opts.onMainReady?.()
  })
  return win
}
