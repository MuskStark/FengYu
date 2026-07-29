import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

const electron = vi.hoisted(() => ({
  ipcHandler: null as ((event: { sender: object }, value: unknown) => void) | null,
  nativeTheme: { themeSource: 'system' },
  setBackgroundColor: vi.fn(),
  isDestroyed: vi.fn(() => false),
}))

vi.mock('electron', () => ({
  BrowserWindow: {
    fromWebContents: vi.fn(() => ({
      isDestroyed: electron.isDestroyed,
      setBackgroundColor: electron.setBackgroundColor,
    })),
  },
  ipcMain: {
    on: vi.fn((_channel: string, handler: typeof electron.ipcHandler) => {
      electron.ipcHandler = handler
    }),
  },
  nativeTheme: electron.nativeTheme,
}))

import {
  appearanceFile,
  backgroundColorForTheme,
  initializeAppearance,
  readCachedTheme,
  writeCachedTheme,
} from '../src/desktop/appearance'

const temporaryDirectories: string[] = []

function temporaryDirectory(): string {
  const directory = mkdtempSync(join(tmpdir(), 'fengyu-appearance-'))
  temporaryDirectories.push(directory)
  return directory
}

afterEach(() => {
  for (const directory of temporaryDirectories.splice(0)) {
    rmSync(directory, { recursive: true, force: true })
  }
})

describe('desktop appearance cache', () => {
  beforeEach(() => {
    electron.ipcHandler = null
    electron.nativeTheme.themeSource = 'system'
    electron.setBackgroundColor.mockClear()
    electron.isDestroyed.mockReset()
    electron.isDestroyed.mockReturnValue(false)
  })

  it('defaults to dark when the cache is absent or invalid', () => {
    const directory = temporaryDirectory()
    expect(readCachedTheme(directory)).toBe('dark')
    writeFileSync(appearanceFile(directory), '{"theme":"unknown"}')
    expect(readCachedTheme(directory)).toBe('dark')
  })

  it('round-trips a validated theme', () => {
    const directory = temporaryDirectory()
    writeCachedTheme(directory, 'light')
    expect(readCachedTheme(directory)).toBe('light')
    expect(JSON.parse(readFileSync(appearanceFile(directory), 'utf8'))).toEqual({ theme: 'light' })
  })

  it('maps themes to the native window backing colors', () => {
    expect(backgroundColorForTheme('dark')).toBe('#0d0d0d')
    expect(backgroundColorForTheme('light')).toBe('#ffffff')
  })

  it('updates the macOS native appearance and existing window immediately', () => {
    const directory = temporaryDirectory()
    initializeAppearance(undefined, directory)

    electron.ipcHandler!({ sender: {} }, 'light')

    expect(electron.nativeTheme.themeSource).toBe('light')
    expect(electron.setBackgroundColor).toHaveBeenCalledWith('#ffffff')
    expect(JSON.parse(readFileSync(appearanceFile(directory), 'utf8'))).toEqual({ theme: 'light' })
  })
})
