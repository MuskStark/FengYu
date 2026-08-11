import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// autoUpdater is a plain object in the mock so we can assert the autoDownload /
// autoInstallOnAppQuit properties the production code MUST set before checkForUpdates().
vi.mock('electron-updater', () => ({
  autoUpdater: {
    checkForUpdates: vi.fn(),
    setFeedURL: vi.fn(),
    downloadUpdate: vi.fn(),
    quitAndInstall: vi.fn(),
    autoDownload: true,
    autoInstallOnAppQuit: true,
    disableDifferentialDownload: false,
  },
}))
vi.mock('electron', () => ({
  app: { getAppPath: vi.fn(() => '/fake/app') },
  dialog: { showMessageBox: vi.fn() },
  shell: { openExternal: vi.fn() },
}))
vi.mock('node:fs', () => ({
  existsSync: vi.fn((p: string) => p.includes('jre')),
  readFileSync: vi.fn(() => JSON.stringify({ fengyu: { signedRelease: false } })),
}))

// A reusable "an update is available" response from autoUpdater.checkForUpdates().
const UPDATE_AVAILABLE = { updateInfo: { version: '9.9.9' } }

describe('checkForUpdates skips JRE variant', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    delete process.env.FENGYU_UPDATE_API_BASE
    ;(process as any).resourcesPath = '/fake/resources'
    const fs = await import('node:fs')
    ;(fs.existsSync as any).mockImplementation((p: string) => p.includes('jre'))
    // Avoid spurious stderr from an unmocked dialog when a probe does reach the offer step.
    const electron = await import('electron')
    ;(electron.dialog.showMessageBox as any).mockResolvedValue({ response: 1 })
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
    ;(autoUpdater.checkForUpdates as any).mockResolvedValue(UPDATE_AVAILABLE)
    const { checkForUpdates } = await import('../src/updater/auto-updater')
    await checkForUpdates()
    expect((autoUpdater.checkForUpdates as any).mock.calls.length).toBeGreaterThanOrEqual(1)
  })

  it('checks the dedicated JRE feed when FY-Proxy is configured', async () => {
    process.env.FENGYU_UPDATE_API_BASE = 'http://proxy.local:8088'
    const { autoUpdater } = await import('electron-updater')
    ;(autoUpdater.checkForUpdates as any).mockResolvedValue(UPDATE_AVAILABLE)
    const { checkForUpdates } = await import('../src/updater/auto-updater')
    await checkForUpdates()
    expect(autoUpdater.setFeedURL).toHaveBeenCalledWith(expect.objectContaining({
      url: 'http://proxy.local:8088/fengyu-updates/jre',
    }))
    expect(autoUpdater.checkForUpdates).toHaveBeenCalled()
  })
})

describe('checkForUpdates unsigned build (default metadata)', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    delete process.env.FENGYU_UPDATE_API_BASE
    ;(process as any).resourcesPath = '/fake/resources'
    const electron = await import('electron')
    const fs = await import('node:fs')
    ;(fs.existsSync as any).mockReturnValue(false)
    // Packaged metadata: signedRelease absent / false → unsigned.
    ;(fs.readFileSync as any).mockReturnValue(JSON.stringify({ fengyu: { signedRelease: false } }))
    // Reset the autoDownload/autoInstallOnAppQuit to the library defaults so the production code's
    // assignment is what we assert against, not a leftover from a previous test.
    const { autoUpdater } = await import('electron-updater')
    autoUpdater.autoDownload = true
    autoUpdater.autoInstallOnAppQuit = true
    ;(electron.dialog.showMessageBox as any).mockResolvedValue({ response: 1 })
  })

  /**
   * P0-9 (regression): an unsigned build must disable electron-updater's implicit download and
   * quit-and-install BEFORE checkForUpdates() runs. The library defaults (autoDownload=true,
   * autoInstallOnAppQuit=true) would otherwise fetch the update and install it on next quit — an
   * unverified binary — even though our code never calls downloadUpdate().
   */
  it('disables autoDownload and autoInstallOnAppQuit before checking (unsigned)', async () => {
    const { autoUpdater } = await import('electron-updater')
    ;(autoUpdater.checkForUpdates as any).mockResolvedValue(UPDATE_AVAILABLE)
    const { checkForUpdates } = await import('../src/updater/auto-updater')
    await checkForUpdates()
    expect(autoUpdater.autoDownload).toBe(false)
    expect(autoUpdater.autoInstallOnAppQuit).toBe(false)
  })

  it('does not download or install an unsigned update', async () => {
    const { autoUpdater } = await import('electron-updater')
    const electron = await import('electron')
    ;(autoUpdater.checkForUpdates as any).mockResolvedValue(UPDATE_AVAILABLE)
    ;(electron.dialog.showMessageBox as any).mockResolvedValue({ response: 0 }) // "Open download page"

    const { checkForUpdates } = await import('../src/updater/auto-updater')
    await checkForUpdates()

    expect((autoUpdater.downloadUpdate as any).mock.calls.length).toBe(0)
    expect((autoUpdater.quitAndInstall as any).mock.calls.length).toBe(0)
    expect((electron.shell.openExternal as any).mock.calls.length).toBe(1)
    expect((electron.shell.openExternal as any).mock.calls[0][0]).toContain('releases')
  })

  it('does nothing further when the user dismisses the unsigned-update notice', async () => {
    const { autoUpdater } = await import('electron-updater')
    const electron = await import('electron')
    ;(autoUpdater.checkForUpdates as any).mockResolvedValue(UPDATE_AVAILABLE)
    ;(electron.dialog.showMessageBox as any).mockResolvedValue({ response: 1 }) // "Later"

    const { checkForUpdates } = await import('../src/updater/auto-updater')
    await checkForUpdates()

    expect((autoUpdater.downloadUpdate as any).mock.calls.length).toBe(0)
    expect((electron.shell.openExternal as any).mock.calls.length).toBe(0)
  })

  /**
   * P0-9 build-contract: the signed flag comes ONLY from packaged build-time metadata. A packaged
   * build must ignore process.env — a launcher cannot flip a build to "signed" by exporting
   * FENGYU_SIGNED_RELEASE. Here the baked metadata is unsigned, so even with the env var set the
   * build stays unsigned (autoDownload stays false).
   */
  it('ignores FENGYU_SIGNED_RELEASE env var when baked metadata says unsigned', async () => {
    process.env.FENGYU_SIGNED_RELEASE = 'true' // must be IGNORED for a packaged build
    try {
      const { autoUpdater } = await import('electron-updater')
      ;(autoUpdater.checkForUpdates as any).mockResolvedValue(UPDATE_AVAILABLE)
      const { checkForUpdates } = await import('../src/updater/auto-updater')
      await checkForUpdates()
      expect(autoUpdater.autoDownload).toBe(false, 'env var must not override unsigned baked metadata')
      expect(autoUpdater.autoInstallOnAppQuit).toBe(false)
    } finally {
      delete process.env.FENGYU_SIGNED_RELEASE
    }
  })
})

describe('checkForUpdates signed build (baked metadata fengyu.signedRelease=true)', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    process.env.FENGYU_UPDATE_API_BASE = 'http://proxy.local:8088'
    ;(process as any).resourcesPath = '/fake/resources'
    const electron = await import('electron')
    const fs = await import('node:fs')
    ;(fs.existsSync as any).mockReturnValue(false)
    // Packaged metadata: signedRelease true → signed (a future signed+notarized build sets this).
    ;(fs.readFileSync as any).mockReturnValue(JSON.stringify({ fengyu: { signedRelease: true } }))
    const { autoUpdater } = await import('electron-updater')
    autoUpdater.autoDownload = false
    autoUpdater.autoInstallOnAppQuit = false
    ;(electron.dialog.showMessageBox as any).mockResolvedValue({ response: 0 })
  })

  /**
   * P0-9: a signed build re-enables auto-download/install, then offers to install on accept.
   * Signed state comes ONLY from build-time metadata, not a mutable env var.
   */
  it('re-enables autoDownload/autoInstallOnAppQuit and installs on accept (signed)', async () => {
    const { autoUpdater } = await import('electron-updater')
    ;(autoUpdater.checkForUpdates as any).mockResolvedValue(UPDATE_AVAILABLE)

    const { checkForUpdates } = await import('../src/updater/auto-updater')
    await checkForUpdates()

    expect(autoUpdater.autoDownload).toBe(true)
    expect(autoUpdater.autoInstallOnAppQuit).toBe(true)
    expect((autoUpdater.downloadUpdate as any).mock.calls.length).toBe(1)
    expect((autoUpdater.quitAndInstall as any).mock.calls.length).toBe(1)
  })

  it('does not auto-install from the shared public feed even for a signed build', async () => {
    delete process.env.FENGYU_UPDATE_API_BASE
    const { autoUpdater } = await import('electron-updater')
    const electron = await import('electron')
    ;(autoUpdater.checkForUpdates as any).mockResolvedValue(UPDATE_AVAILABLE)
    await (await import('../src/updater/auto-updater')).checkForUpdates()
    expect(autoUpdater.autoDownload).toBe(false)
    expect(autoUpdater.autoInstallOnAppQuit).toBe(false)
    expect(autoUpdater.downloadUpdate).not.toHaveBeenCalled()
    expect(electron.shell.openExternal).toHaveBeenCalledWith('https://github.com/MuskStark/FengYu/releases')
  })
})

/**
 * Unit tests for the readSignedReleaseFlag contract: it reads ONLY the baked `fengyu.signedRelease`
 * field, fails safe (false) on missing/malformed metadata, and ignores the environment entirely.
 */
describe('readSignedReleaseFlag build-time metadata contract', () => {
  it('returns true only when fengyu.signedRelease === true', async () => {
    const { readSignedReleaseFlag } = await import('../src/updater/auto-updater')
    expect(readSignedReleaseFlag({ fengyu: { signedRelease: true } })).toBe(true)
    expect(readSignedReleaseFlag({ fengyu: { signedRelease: false } })).toBe(false)
    expect(readSignedReleaseFlag({ fengyu: {} })).toBe(false)
    expect(readSignedReleaseFlag({})).toBe(false)
    expect(readSignedReleaseFlag({ fengyu: { signedRelease: 'true' } })).toBe(false)
    expect(readSignedReleaseFlag({ fengyu: { signedRelease: 1 } })).toBe(false)
  })
})
