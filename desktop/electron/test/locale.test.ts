import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

const electron = vi.hoisted(() => ({
  ipcHandler: null as ((event: { sender: object }, value: unknown) => void) | null,
  systemLocale: { value: 'en-US' },
}))

vi.mock('electron', () => ({
  app: { getLocale: () => electron.systemLocale.value },
  ipcMain: {
    on: vi.fn((_channel: string, handler: typeof electron.ipcHandler) => {
      electron.ipcHandler = handler
    }),
  },
}))

import {
  initializeLocale,
  isDesktopLocale,
  localeFile,
  localeFromSystemLanguage,
  readCachedLocale,
  writeCachedLocale,
} from '../src/desktop/locale'

const temporaryDirectories: string[] = []

function temporaryDirectory(): string {
  const directory = mkdtempSync(join(tmpdir(), 'fengyu-locale-'))
  temporaryDirectories.push(directory)
  return directory
}

afterEach(() => {
  for (const directory of temporaryDirectories.splice(0)) {
    rmSync(directory, { recursive: true, force: true })
  }
})

describe('desktop locale cache', () => {
  beforeEach(() => {
    electron.ipcHandler = null
    electron.systemLocale.value = 'en-US'
  })

  it('maps the system language onto the two shipped locales', () => {
    expect(localeFromSystemLanguage('zh-CN')).toBe('zh')
    expect(localeFromSystemLanguage('zh_TW')).toBe('zh')
    expect(localeFromSystemLanguage('en-US')).toBe('en')
    expect(localeFromSystemLanguage(undefined)).toBe('en')
    expect(isDesktopLocale('zh')).toBe(true)
    expect(isDesktopLocale('ja')).toBe(false)
  })

  it('defaults to the OS language when the cache is absent or invalid', () => {
    const directory = temporaryDirectory()
    electron.systemLocale.value = 'zh-CN'
    expect(readCachedLocale(directory)).toBe('zh')
    writeFileSync(localeFile(directory), '{"locale":"unknown"}')
    expect(readCachedLocale(directory)).toBe('zh')
    electron.systemLocale.value = 'en-US'
    expect(readCachedLocale(directory)).toBe('en')
  })

  it('round-trips a validated locale', () => {
    const directory = temporaryDirectory()
    writeCachedLocale(directory, 'zh')
    expect(readCachedLocale(directory)).toBe('zh')
    expect(JSON.parse(readFileSync(localeFile(directory), 'utf8'))).toEqual({ locale: 'zh' })
  })

  it('returns the initial locale, persists and forwards accepted pushes', () => {
    const directory = temporaryDirectory()
    writeCachedLocale(directory, 'zh')
    const onChange = vi.fn()

    const initial = initializeLocale(onChange, undefined, directory)
    expect(initial).toBe('zh')

    electron.ipcHandler!({ sender: {} }, 'en')
    expect(onChange).toHaveBeenCalledWith('en')
    expect(JSON.parse(readFileSync(localeFile(directory), 'utf8'))).toEqual({ locale: 'en' })

    // An unvalidated value never reaches native surfaces nor the cache.
    onChange.mockClear()
    electron.ipcHandler!({ sender: {} }, 'fr')
    electron.ipcHandler!({ sender: {} }, null)
    expect(onChange).not.toHaveBeenCalled()
    expect(JSON.parse(readFileSync(localeFile(directory), 'utf8'))).toEqual({ locale: 'en' })
  })
})
