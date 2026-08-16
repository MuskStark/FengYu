import { BrowserWindow, app } from 'electron'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { pickLocale, type SplashStage } from './splash-i18n'
import type { DesktopTheme } from '../desktop/appearance'

interface Logger {
  info: (message: string) => void
}

interface CreateSplashOptions {
  logger?: Logger
  theme?: DesktopTheme
}

/**
 * Resolve splash.html on disk.
 *
 * Dev: <cwd>/resources/splash.html (cwd is desktop/electron when running via
 *      `yarn run dev` / `electron .`).
 * Packaged: this file compiles to <asar>/dist/window/create-splash.js, so
 *           __dirname is <asar>/dist/window/. Two `..` climb to <asar>/, then
 *           resources/splash.html — which is where electron-builder.yml's
 *           `files:` entry places it inside app.asar.
 */
function resolveSplashHtml(): string {
  const devPath = join(process.cwd(), 'resources', 'splash.html')
  const prodPath = join(__dirname, '..', '..', 'resources', 'splash.html')
  return existsSync(devPath) ? devPath : prodPath
}

/**
 * Create and show the splash window. Returns the window, or null if creation
 * failed (the main process must treat null as "no splash" and continue booting).
 *
 * Frameless + transparent on macOS/Windows; opaque dark rectangle on Linux
 * (some compositors render transparent windows incorrectly).
 */
export function createSplashWindow(opts: CreateSplashOptions = {}): BrowserWindow | null {
  try {
    const isLinux = process.platform === 'linux'
    const theme = opts.theme ?? 'dark'
    const splash = new BrowserWindow({
      width: 480,
      height: 320,
      frame: false,
      transparent: !isLinux,
      hasShadow: false,
      resizable: false,
      maximizable: false,
      minimizable: false,
      fullscreenable: false,
      skipTaskbar: true,
      show: false,
      center: true,
      focusable: false,
      backgroundColor: isLinux ? (theme === 'light' ? '#f7f7f7' : '#0d0d0d') : undefined,
      webPreferences: {
        preload: join(__dirname, 'splash-preload.js'),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
      },
    })

    const locale = pickLocale(app.getLocale())
    const splashFile = resolveSplashHtml()
    void splash.loadFile(splashFile, { query: { lang: locale, theme } })

    splash.once('ready-to-show', () => {
      if (!splash.isDestroyed()) splash.show()
    })

    splash.webContents.on('did-fail-load', (_e, errorCode, errorDescription) => {
      // Splash is decorative: log and continue, never block the main boot.
      opts.logger?.info(`[desktop] splash load failed: ${errorCode} ${errorDescription}`)
    })

    return splash
  } catch (err) {
    opts.logger?.info(`[desktop] splash creation failed: ${err instanceof Error ? err.message : String(err)}`)
    return null
  }
}

/**
 * Push a progress update to the splash renderer. Null-safe and destroyed-safe.
 */
export function sendProgress(splash: BrowserWindow | null, stage: SplashStage): void {
  if (!splash || splash.isDestroyed()) return
  splash.webContents.send('splash:progress', { stage, ts: Date.now() })
}

/**
 * Destroy the splash window if it exists and is still alive. Null-safe.
 */
export function destroySplash(splash: BrowserWindow | null): void {
  if (!splash || splash.isDestroyed()) return
  splash.destroy()
}
