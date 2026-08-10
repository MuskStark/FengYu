import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * Unit tests for the Windows portable self-updater (`src/updater/portable-updater.ts`).
 *
 * The network download, tar extraction, and bat spawn are real side-effects — we mock fetch and
 * fs/child_process and focus on the load-bearing logic: portable detection, version comparison,
 * release-asset parsing, and the absence-of-app-update.yml signal.
 */

const APP_UPDATE_YML_EXISTS = { value: false } // toggled per test via fs.existsSync mock

vi.mock('electron', () => ({
  app: {
    isPackaged: true,
    getVersion: vi.fn(() => '4.0.0-beta.1'),
    getPath: vi.fn((name: string) => (name === 'exe' ? 'C:\\Infinia\\Infinia.exe' : 'C:\\Temp')),
  },
}))
vi.mock('node:fs', () => ({
  existsSync: vi.fn((p: string) => {
    // app-update.yml presence is the portable-vs-nsis signal.
    if (typeof p === 'string' && p.endsWith('app-update.yml')) return APP_UPDATE_YML_EXISTS.value
    return false
  }),
  readdirSync: vi.fn(() => []),
  mkdirSync: vi.fn(),
  mkdtempSync: vi.fn(() => 'C:\\Temp\\fengyu-update'),
  createWriteStream: vi.fn(() => ({ write: vi.fn(), end: vi.fn((cb: () => void) => cb()) })),
  writeFileSync: vi.fn(),
  rmSync: vi.fn(),
}))

beforeEach(() => {
  vi.clearAllMocks()
  APP_UPDATE_YML_EXISTS.value = false
  // process.resourcesPath is undefined outside a packaged Electron — pin it so existsSync joins work.
  ;(process as { resourcesPath?: string }).resourcesPath = 'C:\\Infinia\\resources'
})

describe('compareVersions', () => {
  it('orders pre-releases alpha < beta < rc < release', async () => {
    const { compareVersions } = await import('../src/updater/portable-updater')
    expect(compareVersions('4.0.0-alpha.1', '4.0.0-alpha.1')).toBe(0)
    expect(compareVersions('4.0.0-alpha.2', '4.0.0-alpha.1')).toBeGreaterThan(0)
    expect(compareVersions('4.0.0-beta.1', '4.0.0-alpha.9')).toBeGreaterThan(0)
    expect(compareVersions('4.0.0-rc.1', '4.0.0-beta.5')).toBeGreaterThan(0)
    expect(compareVersions('4.0.0', '4.0.0-rc.9')).toBeGreaterThan(0)
    expect(compareVersions('4.0.0-rc.1', '4.0.0')).toBeLessThan(0)
  })

  it('compares numeric segments, not lexicographically', async () => {
    const { compareVersions } = await import('../src/updater/portable-updater')
    expect(compareVersions('4.1.10', '4.1.9')).toBeGreaterThan(0)
    expect(compareVersions('4.10.0', '4.9.0')).toBeGreaterThan(0)
  })
})

describe('isWindowsPortable', () => {
  it('returns true on win32 when app-update.yml is absent', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    APP_UPDATE_YML_EXISTS.value = false
    const { isWindowsPortable } = await import('../src/updater/portable-updater')
    expect(isWindowsPortable()).toBe(true)
  })

  it('returns false when app-update.yml exists (nsis install)', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    APP_UPDATE_YML_EXISTS.value = true
    const { isWindowsPortable } = await import('../src/updater/portable-updater')
    expect(isWindowsPortable()).toBe(false)
  })

  it('returns false on non-win32 platforms', async () => {
    Object.defineProperty(process, 'platform', { value: 'darwin', configurable: true })
    const { isWindowsPortable } = await import('../src/updater/portable-updater')
    expect(isWindowsPortable()).toBe(false)
  })
})

describe('checkPortableUpdate', () => {
  it('returns the portable zip asset when the latest release is newer', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    const fakeRelease = {
      tag_name: 'v4.0.0-beta.2',
      name: 'Infinia 4.0.0-beta.2',
      html_url: 'https://github.com/MuskStark/FengYu/releases/tag/v4.0.0-beta.2',
      assets: [
        { name: 'Infinia-4.0.0-beta.2-win-x64-setup.exe', browser_download_url: 'https://x/setup.exe' },
        { name: 'Infinia-4.0.0-beta.2-win-x64-portable.zip', browser_download_url: 'https://x/portable.zip' },
      ],
    }
    const fakeFetch = vi.fn(async () => ({
      ok: true,
      json: async () => [fakeRelease],
    })) as unknown as typeof fetch

    const { checkPortableUpdate } = await import('../src/updater/portable-updater')
    const result = await checkPortableUpdate('MuskStark/FengYu', fakeFetch)

    expect(result).not.toBeNull()
    expect(result!.version).toBe('4.0.0-beta.2')
    expect(result!.zipUrl).toBe('https://x/portable.zip')
    expect(result!.releaseUrl).toContain('v4.0.0-beta.2')
  })

  it('returns null when the latest release is not newer than current', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    const { app } = await import('electron')
    ;(app.getVersion as ReturnType<typeof vi.fn>).mockReturnValue('4.0.0')
    const fakeRelease = {
      tag_name: 'v4.0.0',
      name: 'Infinia 4.0.0',
      html_url: 'https://github.com/MuskStark/FengYu/releases/tag/v4.0.0',
      assets: [{ name: 'Infinia-4.0.0-win-x64-portable.zip', browser_download_url: 'https://x/p.zip' }],
    }
    const fakeFetch = vi.fn(async () => ({ ok: true, json: async () => [fakeRelease] })) as unknown as typeof fetch
    const { checkPortableUpdate } = await import('../src/updater/portable-updater')
    expect(await checkPortableUpdate('MuskStark/FengYu', fakeFetch)).toBeNull()
    // restore for subsequent tests
    ;(app.getVersion as ReturnType<typeof vi.fn>).mockReturnValue('4.0.0-beta.1')
  })

  it('returns null when no portable zip asset is present', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    const fakeRelease = {
      tag_name: 'v5.0.0',
      name: 'Infinia 5.0.0',
      html_url: 'https://github.com/MuskStark/FengYu/releases/tag/v5.0.0',
      assets: [{ name: 'Infinia-5.0.0-win-x64-setup.exe', browser_download_url: 'https://x/setup.exe' }],
    }
    const fakeFetch = vi.fn(async () => ({ ok: true, json: async () => [fakeRelease] })) as unknown as typeof fetch
    const { checkPortableUpdate } = await import('../src/updater/portable-updater')
    expect(await checkPortableUpdate('MuskStark/FengYu', fakeFetch)).toBeNull()
  })

  it('throws when the GitHub API is not ok', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    const fakeFetch = vi.fn(async () => ({ ok: false, status: 403 })) as unknown as typeof fetch
    const { checkPortableUpdate } = await import('../src/updater/portable-updater')
    await expect(checkPortableUpdate('MuskStark/FengYu', fakeFetch)).rejects.toThrow(/HTTP 403/)
  })

  it('accepts a single release object from FY-Proxy (intranet mirror) instead of a GitHub array', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    // FY-Proxy returns one release object (not wrapped in an array), with download URLs pointing
    // at the intranet server. checkPortableUpdate must accept both shapes.
    const fakeRelease = {
      tag_name: 'v4.0.0',
      name: 'Infinia 4.0.0',
      html_url: 'http://10.0.0.5:8088/admin',
      assets: [
        { name: 'Infinia-4.0.0-win-x64-portable.zip', browser_download_url: 'http://10.0.0.5:8088/fengyu-releases/download/Infinia-4.0.0-win-x64-portable.zip' },
      ],
    }
    const fakeFetch = vi.fn(async () => ({ ok: true, json: async () => fakeRelease })) as unknown as typeof fetch
    const { checkPortableUpdate } = await import('../src/updater/portable-updater')
    const result = await checkPortableUpdate('MuskStark/FengYu', fakeFetch)

    expect(result).not.toBeNull()
    expect(result!.version).toBe('4.0.0')
    expect(result!.zipUrl).toContain('10.0.0.5:8088')
  })

  it('uses FENGYU_UPDATE_API_BASE when set (intranet mirror)', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    process.env.FENGYU_UPDATE_API_BASE = 'http://10.0.0.5:8088/'
    const fakeFetch = vi.fn(async () => ({
      ok: true,
      json: async () => [], // no releases
    })) as unknown as typeof fetch
    try {
      const { checkPortableUpdate } = await import('../src/updater/portable-updater')
      await checkPortableUpdate('MuskStark/FengYu', fakeFetch)
      // Verify the URL used the intranet base (with trailing slash trimmed), not GitHub
      expect((fakeFetch as ReturnType<typeof vi.fn>).mock.calls[0][0]).toBe(
        'http://10.0.0.5:8088/fengyu-releases/api/releases/latest',
      )
    } finally {
      delete process.env.FENGYU_UPDATE_API_BASE
    }
  })
})
