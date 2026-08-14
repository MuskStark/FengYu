import { afterEach, describe, expect, it } from 'vitest'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const require = createRequire(import.meta.url)
const afterPack = require('../scripts/after-pack.cjs') as (context: {
  electronPlatformName: string
  appOutDir: string
}) => Promise<void>

const created: string[] = []

afterEach(() => {
  delete process.env.FENGYU_WINDOWS_PORTABLE_ZIP
  for (const dir of created.splice(0)) rmSync(dir, { recursive: true, force: true })
})

function appOutDir(): string {
  const dir = mkdtempSync(join(tmpdir(), 'fengyu-after-pack-'))
  created.push(dir)
  mkdirSync(join(dir, 'resources'))
  return dir
}

describe('Windows portable package marker', () => {
  it('writes the marker for the dedicated ZIP build pass', async () => {
    const dir = appOutDir()
    process.env.FENGYU_WINDOWS_PORTABLE_ZIP = '1'

    await afterPack({ electronPlatformName: 'win32', appOutDir: dir })

    const marker = join(dir, 'resources', 'fengyu-portable-zip')
    expect(readFileSync(marker, 'utf8')).toBe('portable-zip\n')
  })

  it('removes a stale marker from an NSIS build pass', async () => {
    const dir = appOutDir()
    const marker = join(dir, 'resources', 'fengyu-portable-zip')
    writeFileSync(marker, 'stale')

    await afterPack({ electronPlatformName: 'win32', appOutDir: dir })

    expect(existsSync(marker)).toBe(false)
  })
})
