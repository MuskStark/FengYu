import { BrowserWindow, ipcMain, nativeTheme } from 'electron'
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { runtimeRoot } from './runtime-paths'

export type DesktopTheme = 'dark' | 'light'

export function backgroundColorForTheme(theme: DesktopTheme): string {
  return theme === 'light' ? '#ffffff' : '#0d0d0d'
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
    if (!isDesktopTheme(value)) return
    nativeTheme.themeSource = value
    const window = BrowserWindow.fromWebContents(event.sender)
    if (window && !window.isDestroyed()) {
      window.setBackgroundColor(backgroundColorForTheme(value))
    }
    try {
      writeCachedTheme(configDirectory, value)
    } catch (err) {
      logger?.info(`[desktop] could not persist appearance: ${err instanceof Error ? err.message : String(err)}`)
    }
  })

  return initialTheme
}
