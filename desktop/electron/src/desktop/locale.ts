import { app, ipcMain } from 'electron'
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { isMainWindowSender } from '../ipc/sender-guard'
import { runtimeRoot } from './runtime-paths'

/**
 * Native-surface locale state (the tray menu today). The SPA owns the UI language —
 * persisted in the renderer's localStorage, invisible to the main process — so the
 * renderer pushes every change over the `locale:set` channel and this module mirrors
 * it into a small cache that bridges the gap before the renderer loads. Same shape as
 * the appearance (theme) cache: file-backed, atomically written, validated on read.
 */
export type DesktopLocale = 'en' | 'zh'

export function isDesktopLocale(value: unknown): value is DesktopLocale {
  return value === 'en' || value === 'zh'
}

/** First-launch locale from the OS language, mirroring the SPA's navigator.language sniff. */
export function localeFromSystemLanguage(language: string | undefined): DesktopLocale {
  return language?.toLowerCase().startsWith('zh') ? 'zh' : 'en'
}

export function localeFile(configDirectory: string): string {
  return join(configDirectory, 'locale.json')
}

/** Read the last SPA-confirmed locale; the OS language covers a first launch. */
export function readCachedLocale(
  configDirectory: string,
  fallback: DesktopLocale = localeFromSystemLanguage(app.getLocale()),
): DesktopLocale {
  try {
    const parsed = JSON.parse(readFileSync(localeFile(configDirectory), 'utf8')) as { locale?: unknown }
    return isDesktopLocale(parsed.locale) ? parsed.locale : fallback
  } catch {
    return fallback
  }
}

/** Persist atomically so an interrupted write cannot corrupt the startup preference. */
export function writeCachedLocale(configDirectory: string, locale: DesktopLocale): void {
  const target = localeFile(configDirectory)
  const temporary = `${target}.tmp`
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(temporary, JSON.stringify({ locale }) + '\n', 'utf8')
  renameSync(temporary, target)
}

interface Logger {
  info: (message: string) => void
}

/**
 * Read the cached locale and accept later pushes from the SPA. The returned locale is
 * what native surfaces should start with; `onChange` fires on every accepted push
 * (same value twice is the caller's concern — tray.ts de-duplicates).
 */
export function initializeLocale(
  onChange: (locale: DesktopLocale) => void,
  logger?: Logger,
  configDirectory = join(runtimeRoot(), 'config'),
): DesktopLocale {
  const initial = readCachedLocale(configDirectory)
  ipcMain.on('locale:set', (event, value: unknown) => {
    if (!isMainWindowSender(event?.sender)) return
    if (!isDesktopLocale(value)) return
    try {
      writeCachedLocale(configDirectory, value)
    } catch (err) {
      logger?.info(`[desktop] could not persist locale: ${err instanceof Error ? err.message : String(err)}`)
    }
    onChange(value)
  })
  return initial
}
