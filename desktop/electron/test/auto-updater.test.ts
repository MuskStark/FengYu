import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('electron-updater', () => ({ autoUpdater: { checkForUpdates: vi.fn() } }))
vi.mock('electron', () => ({ dialog: { showMessageBox: vi.fn() } }))
vi.mock('node:fs', () => ({
  existsSync: vi.fn((p: string) => p.includes('jre')),
}))

describe('checkForUpdates skips JRE variant', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // process.resourcesPath is Electron-only; stub it so node:path.join works in vitest.
    ;(process as any).resourcesPath = '/fake/resources'
  })

  it('does not check for updates when jre/ exists in resourcesPath', async () => {
    const { autoUpdater } = await import('electron-updater')
    const { checkForUpdates } = await import('../src/updater/auto-updater')
    await checkForUpdates()
    expect((autoUpdater.checkForUpdates as any).mock.calls.length).toBe(0)
  })

  it('checks for updates when jre/ does NOT exist (lite variant proceeds)', async () => {
    const fs = await import('node:fs')
    ;(fs.existsSync as any).mockReturnValue(false)
    const { autoUpdater } = await import('electron-updater')
    const { checkForUpdates } = await import('../src/updater/auto-updater')
    await checkForUpdates()
    expect((autoUpdater.checkForUpdates as any).mock.calls.length).toBeGreaterThanOrEqual(1)
  })
})
