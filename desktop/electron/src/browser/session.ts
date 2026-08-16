import { BrowserWindow, shell } from 'electron'
import { join } from 'node:path'
import { mkdirSync } from 'node:fs'
import { runtimeRoot } from '../desktop/runtime-paths'

/** CDP protocol version pinned for Input.* and Accessibility.* domains. */
const CDP_VERSION = '1.3'

/**
 * Lazy, single, visible browser window used for AI-driven automation. Uses a Chromium
 * persistent partition so cookies/localStorage/login state survive restarts. Mirrors the
 * former plugin-browser BrowserSession's lazy-start + idempotent-close lifecycle.
 *
 * Owns two session-scoped resources that span multiple browser_* operations:
 *  - a persistent CDP (webContents.debugger) attachment, so real-input dispatch
 *    (Input.dispatchMouseEvent / Input.insertText) and a11y capture reuse one channel
 *    instead of attach/detach per call;
 *  - an element-ref sequence: browser_find stamps a `data-fengyu-ref="<id>"` attribute
 *    on a matched element so subsequent operations target that exact node. A framework
 *    replacement intentionally makes the ref stale; refs also reset on navigation/close.
 */
export class BrowserSession {
  private win: BrowserWindow | null = null
  private refSeq = 0
  private cdpAttached = false

  constructor(
    private readonly partition = 'persist:fengyu-browser',
    private readonly windowTitle = 'FengYu Browser',
  ) {}

  /** The current window, or null if closed/not yet created. */
  window(): BrowserWindow | null {
    return this.win && !this.win.isDestroyed() ? this.win : null
  }

  /** Lazily create the window on first navigation; reuse thereafter. */
  ensureWindow(): BrowserWindow {
    const existing = this.window()
    if (existing) return existing
    this.win = new BrowserWindow({
      title: this.windowTitle,
      width: 1280,
      height: 900,
      show: true,
      webPreferences: {
        partition: this.partition,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
      },
    })
    // A page navigation invalidates every stamped data-fengyu-ref anchor (the DOM is
    // replaced), so drop the registry so callers do not resolve stale refs into nothing.
    const reset = (): void => { this.refSeq = 0 }
    this.win.webContents.on('did-start-loading', reset)
    // Navigation guard, same posture as the main window (create-window.ts): this window
    // renders untrusted web content, so deny all window.open (otherwise a page could spawn
    // unguarded child windows) and delegate http(s) to the system browser. The will-navigate
    // guard is deliberately narrower than the main window's same-origin rule: cross-origin
    // link clicks ARE the browsing this window exists for — it only blocks escapes from the
    // web (file:, chrome:, ...) which loadURL-driven browser_navigate never needs.
    this.win.webContents.setWindowOpenHandler(({ url }) => {
      if (/^https?:\/\//.test(url)) {
        void shell.openExternal(url)
      }
      return { action: 'deny' }
    })
    this.win.webContents.on('will-navigate', (e, url) => {
      if (!/^https?:\/\//i.test(url)) e.preventDefault()
    })
    return this.win
  }

  /**
   * Returns the webContents' CDP channel, attaching once for the window's lifetime.
   * Used by real-input dispatch (mouse/key/insertText) and a11y capture. Detached on
   * {@link close}. Safe to call repeatedly.
   */
  async cdp(): Promise<Electron.Debugger> {
    const w = this.window()
    if (!w) throw new Error('no browser session')
    const dbg = w.webContents.debugger
    if (!this.cdpAttached && !dbg.isAttached()) {
      dbg.attach(CDP_VERSION)
      this.cdpAttached = true
    }
    return dbg
  }

  /** Allocate the next ref id (e.g. `el_3`). Sequence resets on navigation/close. */
  nextRefId(): string {
    return `el_${++this.refSeq}`
  }

  /** Reset ref state (invoked on navigation/close). */
  resetRefs(): void {
    this.refSeq = 0
  }

  /** Lightweight tab metadata; never creates a window. */
  describe(): { open: boolean; url: string; title: string } {
    const w = this.window()
    return {
      open: w != null,
      url: w?.webContents.getURL() ?? '',
      title: w?.webContents.getTitle() ?? '',
    }
  }

  /** Destroy the window and clear all session state. Idempotent. */
  close(): void {
    if (this.win && !this.win.isDestroyed()) {
      try { if (this.cdpAttached) this.win.webContents.debugger.detach() } catch { /* already gone */ }
      this.win.destroy()
    }
    this.win = null
    this.cdpAttached = false
    this.refSeq = 0
  }

  /** Directory for screenshot PNGs, created on demand. */
  screenshotsDir(): string {
    const dir = join(runtimeRoot(), 'browser-screenshots')
    mkdirSync(dir, { recursive: true })
    return dir
  }
}
