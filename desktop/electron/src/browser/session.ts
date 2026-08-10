import { BrowserWindow } from 'electron'
import { join } from 'node:path'
import { mkdirSync } from 'node:fs'
import { runtimeRoot } from '../desktop/runtime-paths'

/**
 * Lazy, single, visible browser window used for AI-driven automation. Uses a Chromium
 * persistent partition so cookies/localStorage/login state survive restarts. Mirrors the
 * former plugin-browser BrowserSession's lazy-start + idempotent-close lifecycle.
 */
export class BrowserSession {
  private win: BrowserWindow | null = null

  /** The current window, or null if closed/not yet created. */
  window(): BrowserWindow | null {
    return this.win && !this.win.isDestroyed() ? this.win : null
  }

  /** Lazily create the window on first navigation; reuse thereafter. */
  ensureWindow(): BrowserWindow {
    const existing = this.window()
    if (existing) return existing
    this.win = new BrowserWindow({
      title: 'FengYu Browser',
      width: 1280,
      height: 900,
      show: true,
      webPreferences: {
        partition: 'persist:fengyu-browser',
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
      },
    })
    return this.win
  }

  /** Destroy the window and clear the reference. Idempotent. */
  close(): void {
    if (this.win && !this.win.isDestroyed()) this.win.destroy()
    this.win = null
  }

  /** Directory for screenshot PNGs, created on demand. */
  screenshotsDir(): string {
    const dir = join(runtimeRoot(), 'browser-screenshots')
    mkdirSync(dir, { recursive: true })
    return dir
  }
}
