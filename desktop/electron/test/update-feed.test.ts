import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('electron-updater', () => ({
  autoUpdater: {
    setFeedURL: vi.fn(),
    disableDifferentialDownload: false,
  },
}))

afterEach(() => {
  delete process.env.FENGYU_UPDATE_API_BASE
  vi.clearAllMocks()
})

describe('configureUpdateFeed', () => {
  it('keeps the packaged GitHub provider when no proxy is configured', async () => {
    const { autoUpdater } = await import('electron-updater')
    const { configureUpdateFeed } = await import('../src/updater/update-feed')
    expect(configureUpdateFeed(autoUpdater as any, false)).toBeNull()
    expect(autoUpdater.setFeedURL).not.toHaveBeenCalled()
  })

  it('selects separate lite and JRE generic feeds and disables differential download', async () => {
    process.env.FENGYU_UPDATE_API_BASE = 'http://10.0.0.5:8088/'
    const { autoUpdater } = await import('electron-updater')
    const { configureUpdateFeed } = await import('../src/updater/update-feed')

    expect(configureUpdateFeed(autoUpdater as any, false)).toBe(
      'http://10.0.0.5:8088/fengyu-updates/lite',
    )
    expect(autoUpdater.setFeedURL).toHaveBeenLastCalledWith({
      provider: 'generic',
      url: 'http://10.0.0.5:8088/fengyu-updates/lite',
      useMultipleRangeRequest: false,
    })

    expect(configureUpdateFeed(autoUpdater as any, true)).toBe(
      'http://10.0.0.5:8088/fengyu-updates/jre',
    )
    expect(autoUpdater.disableDifferentialDownload).toBe(true)
  })

  it('rejects non-HTTP and credential-bearing proxy URLs', async () => {
    const { configureUpdateFeed } = await import('../src/updater/update-feed')
    process.env.FENGYU_UPDATE_API_BASE = 'file:///tmp/feed'
    expect(() => configureUpdateFeed({ setFeedURL: vi.fn(), disableDifferentialDownload: false }, false))
      .toThrow(/HTTP or HTTPS/)

    process.env.FENGYU_UPDATE_API_BASE = 'http://user:secret@proxy.local'
    expect(() => configureUpdateFeed({ setFeedURL: vi.fn(), disableDifferentialDownload: false }, false))
      .toThrow(/must not contain credentials/)
  })

  it('uses the proxy admin page for manual downloads', async () => {
    process.env.FENGYU_UPDATE_API_BASE = 'http://proxy.local:8088'
    const { updateDownloadPageUrl } = await import('../src/updater/update-feed')
    expect(updateDownloadPageUrl()).toBe('http://proxy.local:8088/admin')
  })
})
