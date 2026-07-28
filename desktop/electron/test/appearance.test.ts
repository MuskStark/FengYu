import { afterEach, describe, expect, it } from 'vitest'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { appearanceFile, readCachedTheme, writeCachedTheme } from '../src/desktop/appearance'

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
})
