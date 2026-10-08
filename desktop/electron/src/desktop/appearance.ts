import { BrowserWindow, ipcMain, nativeTheme } from 'electron'
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { isMainWindowSender } from '../ipc/sender-guard'
import { runtimeRoot } from './runtime-paths'

export type DesktopTheme = 'dark' | 'light'

export function backgroundColorForTheme(theme: DesktopTheme): string {
  return theme === 'light' ? '#ffffff' : '#0d0d0d'
}

export interface TitleBarOverlayColors {
  color: string
  symbolColor: string
}

/**
 * Window Controls Overlay (WCO) tint for the Windows/Linux shell. The overlay
 * capsule sits over the renderer's 48px window bar strip, whose background is
 * the header token — mirror those literals here (frontend/src/styles/zai.css
 * `--color-header` / `--color-foreground`) so the OS-drawn min/max/close
 * buttons blend into the strip instead of flashing the system title-bar white.
 *
 * These literals are the desktop half of a hand-mirrored pair: changing
 * `--color-header` / `--color-foreground` in frontend/src/styles/zai.css
 * without updating them here leaves the capsule tinted the old color (and vice
 * versa: the window-bar strip flashes a different band than the capsule).
 * Both sides are pinned together by test/window-open-handler.test.ts and
 * test/appearance.test.ts — update all four places in one change.
 */
export function titleBarOverlayForTheme(theme: DesktopTheme): TitleBarOverlayColors {
  return theme === 'light'
    ? { color: '#ffffff', symbolColor: '#18181b' }
    : { color: '#141416', symbolColor: '#fafafa' }
}

interface Logger {
  info: (message: string) => void
}

function isDesktopTheme(value: unknown): value is DesktopTheme {
  return value === 'dark' || value === 'light'
}

export function appearanceFile(configDirectory: string): string {
  return join(configDirectory, 'appearance.json')
}

/** Read the last backend-confirmed theme without delaying desktop startup. */
export function readCachedTheme(configDirectory: string): DesktopTheme {
  try {
    const parsed = JSON.parse(readFileSync(appearanceFile(configDirectory), 'utf8')) as { theme?: unknown }
    return isDesktopTheme(parsed.theme) ? parsed.theme : 'dark'
  } catch {
    return 'dark'
  }
}

/** Persist atomically so an interrupted write cannot leave a corrupt startup preference. */
export function writeCachedTheme(configDirectory: string, theme: DesktopTheme): void {
  const target = appearanceFile(configDirectory)
  const temporary = `${target}.tmp`
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(temporary, JSON.stringify({ theme }) + '\n', 'utf8')
  renameSync(temporary, target)
}

/**
 * Apply the cached theme to native Electron surfaces and accept later updates from the SPA.
 * The backend remains authoritative; this cache only bridges the period before it is available.
 */
export function initializeAppearance(
  logger?: Logger,
  configDirectory = join(runtimeRoot(), 'config'),
): DesktopTheme {
  const initialTheme = readCachedTheme(configDirectory)
  nativeTheme.themeSource = initialTheme

  ipcMain.on('appearance:set-theme', (event, value: unknown) => {
    if (!isMainWindowSender(event?.sender)) return
    if (!isDesktopTheme(value)) return
    nativeTheme.themeSource = value
    const window = BrowserWindow.fromWebContents(event.sender)
    if (window && !window.isDestroyed()) {
      window.setBackgroundColor(backgroundColorForTheme(value))
      // Retint the WCO capsule with the theme switch. Any non-main window that
      // ever shares this session would not have the overlay enabled, and calling
      // setTitleBarOverlay there throws — hence the guard plus the try/catch.
      if (process.platform !== 'darwin' && typeof window.setTitleBarOverlay === 'function') {
        try {
          window.setTitleBarOverlay(titleBarOverlayForTheme(value))
        } catch {
          // Overlay not enabled on this window; nothing to retint.
        }
      }
    }
    try {
      writeCachedTheme(configDirectory, value)
    } catch (err) {
      logger?.info(`[desktop] could not persist appearance: ${err instanceof Error ? err.message : String(err)}`)
    }
  })

  return initialTheme
}
