import { afterEach, describe, expect, it, vi } from 'vitest'

const packageType = { value: 'deb' }
const originalPlatform = process.platform

vi.mock('node:fs', () => ({
  existsSync: vi.fn(() => true),
  readFileSync: vi.fn(() => packageType.value),
}))

vi.mock('electron-updater', () => ({
  autoUpdater: {
    setFeedURL: vi.fn(),
    disableDifferentialDownload: false,
  },
}))

afterEach(() => {
  delete process.env.FENGYU_UPDATE_API_BASE
  packageType.value = 'deb'
  Object.defineProperty(process, 'platform', { value: originalPlatform, configurable: true })
  vi.clearAllMocks()
})

describe('configureUpdateFeed', () => {
  it('keeps the packaged GitHub provider when no proxy is configured', async () => {
    const { autoUpdater } = await import('electron-updater')
    const { configureUpdateFeed } = await import('../src/updater/update-feed')
    expect(configureUpdateFeed(autoUpdater as any, false)).toBeNull()
    expect(autoUpdater.setFeedURL).not.toHaveBeenCalled()
  })

  it('selects the deb generic feed and disables differential download', async () => {
    Object.defineProperty(process, 'platform', { value: 'linux', configurable: true })
    ;(process as { resourcesPath?: string }).resourcesPath = '/fake/resources'
    process.env.FENGYU_UPDATE_API_BASE = 'http://10.0.0.5:8088/'
    const { autoUpdater } = await import('electron-updater')
    const { configureUpdateFeed } = await import('../src/updater/update-feed')

    expect(configureUpdateFeed(autoUpdater as any, false)).toBe(
      'http://10.0.0.5:8088/fengyu-updates/deb',
    )
    expect(autoUpdater.setFeedURL).toHaveBeenLastCalledWith({
      provider: 'generic',
      url: 'http://10.0.0.5:8088/fengyu-updates/deb',
      useMultipleRangeRequest: false,
    })
    expect(autoUpdater.disableDifferentialDownload).toBe(true)
  })

  it('rejects JRE and non-deb packages on the intranet channel', async () => {
    Object.defineProperty(process, 'platform', { value: 'linux', configurable: true })
    ;(process as { resourcesPath?: string }).resourcesPath = '/fake/resources'
    process.env.FENGYU_UPDATE_API_BASE = 'http://10.0.0.5:8088'
    const { configureUpdateFeed } = await import('../src/updater/update-feed')
    const updater = { setFeedURL: vi.fn(), disableDifferentialDownload: false }

    expect(() => configureUpdateFeed(updater, true)).toThrow(/Windows portable ZIP.*Debian package/)
    packageType.value = 'appimage'
    expect(() => configureUpdateFeed(updater, false)).toThrow(/Windows portable ZIP.*Debian package/)
    expect(updater.setFeedURL).not.toHaveBeenCalled()
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

  it('uses the proxy file-management page for manual downloads', async () => {
    process.env.FENGYU_UPDATE_API_BASE = 'http://proxy.local:8088'
    const { updateDownloadPageUrl } = await import('../src/updater/update-feed')
    expect(updateDownloadPageUrl()).toBe('http://proxy.local:8088/files')
  })
})

describe('bootstrapUpdateApiBaseFromBackend', () => {
  const originalFetch = globalThis.fetch

  afterEach(() => {
    delete process.env.FENGYU_UPDATE_API_BASE
    globalThis.fetch = originalFetch
    vi.restoreAllMocks()
  })

  it('seeds the env var from the backend settings payload (trailing slash trimmed)', async () => {
    globalThis.fetch = vi.fn(async () =>
      new Response(JSON.stringify({ updateApiBase: 'http://10.0.0.5:8088/' }), { status: 200 }),
    ) as unknown as typeof fetch
    const { bootstrapUpdateApiBaseFromBackend } = await import('../src/updater/update-feed')
    await bootstrapUpdateApiBaseFromBackend('http://127.0.0.1:24056', 'tok')
    expect(process.env.FENGYU_UPDATE_API_BASE).toBe('http://10.0.0.5:8088')
    expect(globalThis.fetch).toHaveBeenCalledWith('http://127.0.0.1:24056/api/settings', {
      headers: { 'X-FengYu-Token': 'tok' },
    })
  })

  it('clears the env var when the persisted value is empty (GitHub default)', async () => {
    process.env.FENGYU_UPDATE_API_BASE = 'http://preexisting:9999'
    globalThis.fetch = vi.fn(async () =>
      new Response(JSON.stringify({ updateApiBase: '' }), { status: 200 }),
    ) as unknown as typeof fetch
    const { bootstrapUpdateApiBaseFromBackend } = await import('../src/updater/update-feed')
    await bootstrapUpdateApiBaseFromBackend('http://127.0.0.1:24056', 'tok')
    expect(process.env.FENGYU_UPDATE_API_BASE).toBe('')
  })

  it('leaves the env var untouched when the backend responds non-200', async () => {
    process.env.FENGYU_UPDATE_API_BASE = 'http://preexisting:9999'
    globalThis.fetch = vi.fn(async () => new Response('nope', { status: 503 })) as unknown as typeof fetch
    const { bootstrapUpdateApiBaseFromBackend } = await import('../src/updater/update-feed')
    await bootstrapUpdateApiBaseFromBackend('http://127.0.0.1:24056', 'tok')
    expect(process.env.FENGYU_UPDATE_API_BASE).toBe('http://preexisting:9999')
  })

  it('propagates fetch errors (the main.ts caller swallows them)', async () => {
    // The function itself does NOT swallow errors — it surfaces them so the caller (main.ts's
    // startup bootstrap) can decide. main.ts wraps the call in `.catch(() => {})`, which is the
    // documented "fall back to env default" behavior. Here we assert the raw contract: a network
    // failure rejects.
    globalThis.fetch = vi.fn(async () => { throw new Error('connection refused') }) as unknown as typeof fetch
    const { bootstrapUpdateApiBaseFromBackend } = await import('../src/updater/update-feed')
    await expect(bootstrapUpdateApiBaseFromBackend('http://127.0.0.1:24056', 'tok'))
      .rejects.toThrow('connection refused')
  })
})
