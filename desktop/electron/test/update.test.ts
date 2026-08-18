import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * Unit tests for the renderer-driven update IPC handler (`src/ipc/update.ts`).
 *
 * Mirrors the mock style of `auto-updater.test.ts`: autoUpdater is a plain object so we can
 * assert the autoDownload/autoInstallOnAppQuit assignments, and ipcMain.handle is captured into
 * a map so each handler can be invoked directly with a synthetic event.
 */

// autoUpdater event listeners captured here so tests can fire them.
const listeners: Record<string, ((...args: unknown[]) => void) | undefined> = {}
const autoUpdater = {
  checkForUpdates: vi.fn(),
  downloadUpdate: vi.fn(),
  quitAndInstall: vi.fn(),
  autoDownload: true,
  autoInstallOnAppQuit: true,
  disableDifferentialDownload: false,
  setFeedURL: vi.fn(),
  currentVersion: '4.0.0' as string | { version: string },
  on: vi.fn((event: string, cb: (...args: unknown[]) => void) => {
    listeners[event] = cb
    return autoUpdater
  }),
}

const handlers = new Map<string, (...args: unknown[]) => unknown>()
const sentMessages: { channel: string; payload: unknown }[] = []
const allWindows: {
  isDestroyed: () => boolean
  destroy: ReturnType<typeof vi.fn>
  webContents: { send: (c: string, p: unknown) => void }
}[] = []
const portableMode = { value: false }
const portableCheck = vi.fn()

vi.mock('electron-updater', () => ({ autoUpdater }))
vi.mock('node:fs', () => ({
  existsSync: vi.fn((p: string) => p.endsWith('package-type')),
  readFileSync: vi.fn(() => 'deb'),
}))
vi.mock('electron', () => ({
  app: { quit: vi.fn() },
  dialog: { showMessageBoxSync: vi.fn(() => 0) },
  ipcMain: {
    handle: vi.fn((channel: string, fn: (...args: unknown[]) => unknown) => handlers.set(channel, fn)),
  },
  BrowserWindow: {
    fromWebContents: vi.fn(() => allWindows[0]),
    getAllWindows: vi.fn(() => allWindows),
  },
  shell: { openExternal: vi.fn() },
}))
// These tests exercise the electron-updater (nsis) path; force the portable branch off so the
// ipc handler routes to autoUpdater. The portable pipeline has its own dedicated test file.
vi.mock('../src/updater/portable-updater', () => ({
  isWindowsPortable: () => portableMode.value,
  checkPortableUpdate: portableCheck,
  downloadAndExtractPortable: vi.fn(),
  applyPortableUpdate: vi.fn(),
}))
// The ipc handler logs each portable-install step to update.log; keep unit tests off the real FS.
vi.mock('../src/updater/update-log', () => ({
  logUpdate: vi.fn(),
}))

const UPDATE_AVAILABLE = { updateInfo: { version: '9.9.9', releaseNotes: '' } }

/**
 * Invoke a captured handler the way ipcMain.handle presents it to the renderer: a
 * synchronous throw inside the handler becomes a rejected invoke() promise.
 */
const invoke = (channel: string, ...args: unknown[]) =>
  new Promise<unknown>((resolve, reject) => {
    try {
      resolve(handlers.get(channel)!(...args))
    } catch (err) {
      reject(err)
    }
  })

beforeEach(async () => {
  // Reset the module under test so the process-wide `progressWired` guard re-registers listeners
  // against the freshly-cleared `listeners` map each test.
  vi.resetModules()
  vi.clearAllMocks()
  for (const k of Object.keys(listeners)) delete listeners[k]
  handlers.clear()
  sentMessages.length = 0
  allWindows.length = 0
  allWindows.push({
    isDestroyed: () => false,
    destroy: vi.fn(),
    webContents: { send: (c, p) => sentMessages.push({ channel: c, payload: p }) },
  })
  autoUpdater.autoDownload = true
  autoUpdater.autoInstallOnAppQuit = true
  portableMode.value = false
  delete process.env.FENGYU_UPDATE_API_BASE
})

describe('update:check', () => {
  it('disables implicit download/install and reports an available update', async () => {
    autoUpdater.checkForUpdates.mockResolvedValue(UPDATE_AVAILABLE)
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const result = (await handlers.get('update:check')!({ sender: {} })) as {
      updateAvailable: boolean
      version: string | null
    }

    expect(autoUpdater.autoDownload).toBe(false)
    expect(autoUpdater.autoInstallOnAppQuit).toBe(false)
    expect(autoUpdater.checkForUpdates).toHaveBeenCalledTimes(1)
    expect(autoUpdater.downloadUpdate).not.toHaveBeenCalled()
    expect(result.updateAvailable).toBe(true)
    expect(result.version).toBe('9.9.9')
  })

  it('propagates a failed check so the renderer can show an error', async () => {
    autoUpdater.checkForUpdates.mockRejectedValue(new Error('network down'))
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    await expect(handlers.get('update:check')!({ sender: {} })).rejects.toThrow('network down')
  })

  it('reports no update when the latest version equals currentVersion', async () => {
    // electron-updater exposes currentVersion as a SemVer object in production.
    autoUpdater.currentVersion = { version: '9.9.9' }
    autoUpdater.checkForUpdates.mockResolvedValue(UPDATE_AVAILABLE)
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const result = (await handlers.get('update:check')!({ sender: {} })) as { updateAvailable: boolean }
    expect(result.updateAvailable).toBe(false)
    autoUpdater.currentVersion = '4.0.0'
  })

  it('propagates a failed portable check so the renderer can show an error', async () => {
    portableMode.value = true
    portableCheck.mockRejectedValue(new Error('proxy offline'))
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    await expect(handlers.get('update:check')!({ sender: {} })).rejects.toThrow('proxy offline')
  })
})

describe('update:download-install (Windows/Linux)', () => {
  beforeEach(() => {
    Object.defineProperty(process, 'platform', { value: 'linux', configurable: true })
    ;(process as { resourcesPath?: string }).resourcesPath = '/fake/resources'
    process.env.FENGYU_UPDATE_API_BASE = 'http://proxy.local:8088'
  })

  it('downloads and installs a deb update after native consent', async () => {
    autoUpdater.downloadUpdate.mockResolvedValue(['/tmp/pkg'])
    autoUpdater.checkForUpdates.mockResolvedValue(UPDATE_AVAILABLE)
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const result = (await handlers.get('update:download-install')!({ sender: {} })) as { action: string }

    // The renderer click alone is not authorization: a main-process dialog must confirm first.
    const { dialog } = await import('electron')
    expect(dialog.showMessageBoxSync).toHaveBeenCalledTimes(1)
    expect(dialog.showMessageBoxSync).toHaveBeenCalledWith(
      expect.objectContaining({ buttons: ['Install', 'Cancel'], message: expect.stringContaining('9.9.9') }),
    )
    // The consent dialog names the feed host so the user sees where the code would come from.
    expect(dialog.showMessageBoxSync).toHaveBeenCalledWith(
      expect.objectContaining({ message: expect.stringContaining('from proxy.local:8088') }),
    )
    expect(autoUpdater.autoDownload).toBe(false)
    expect(autoUpdater.autoInstallOnAppQuit).toBe(false)
    expect(autoUpdater.downloadUpdate).toHaveBeenCalledTimes(1)
    expect(autoUpdater.quitAndInstall).toHaveBeenCalledTimes(1)
    expect(result.action).toBe('restarting')
  })

  it('rejects a second install while one is already in flight (no second dialog/download)', async () => {
    autoUpdater.downloadUpdate.mockResolvedValue(['/tmp/pkg'])
    let releaseCheck!: (value: unknown) => void
    autoUpdater.checkForUpdates.mockImplementation(
      () => new Promise((resolve) => { releaseCheck = resolve }),
    )
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const first = handlers.get('update:download-install')!({ sender: {} })
    await expect(handlers.get('update:download-install')!({ sender: {} })).rejects.toThrow(
      /already in progress/,
    )

    releaseCheck(UPDATE_AVAILABLE)
    const result = (await first) as { action: string }
    expect(result.action).toBe('restarting')
    const { dialog } = await import('electron')
    expect(dialog.showMessageBoxSync).toHaveBeenCalledTimes(1)
    expect(autoUpdater.downloadUpdate).toHaveBeenCalledTimes(1)
    expect(autoUpdater.quitAndInstall).toHaveBeenCalledTimes(1)

    // The guard resets after completion — a later install runs normally.
    autoUpdater.checkForUpdates.mockResolvedValue(UPDATE_AVAILABLE)
    const second = (await handlers.get('update:download-install')!({ sender: {} })) as { action: string }
    expect(second.action).toBe('restarting')
    expect(dialog.showMessageBoxSync).toHaveBeenCalledTimes(2)
  })

  it('aborts the install when the native consent dialog is cancelled', async () => {
    autoUpdater.downloadUpdate.mockResolvedValue(['/tmp/pkg'])
    autoUpdater.checkForUpdates.mockResolvedValue(UPDATE_AVAILABLE)
    const { dialog } = await import('electron')
    ;(dialog.showMessageBoxSync as ReturnType<typeof vi.fn>).mockReturnValueOnce(1)
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const result = (await handlers.get('update:download-install')!({ sender: {} })) as { action: string }

    expect(result.action).toBe('manual')
    expect(autoUpdater.downloadUpdate).not.toHaveBeenCalled()
    expect(autoUpdater.quitAndInstall).not.toHaveBeenCalled()
  })

  it('still prompts for consent when the pre-consent version check fails', async () => {
    autoUpdater.downloadUpdate.mockResolvedValue(['/tmp/pkg'])
    autoUpdater.checkForUpdates.mockRejectedValue(new Error('feed hiccup'))
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const result = (await handlers.get('update:download-install')!({ sender: {} })) as { action: string }
    expect(result.action).toBe('restarting')
    expect(autoUpdater.downloadUpdate).toHaveBeenCalledTimes(1)
  })

  it('uses manual download for the ambiguous shared GitHub feed', async () => {
    delete process.env.FENGYU_UPDATE_API_BASE
    const { shell } = await import('electron')
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const result = (await handlers.get('update:download-install')!({ sender: {} })) as { action: string }
    expect(result.action).toBe('manual')
    expect(shell.openExternal).toHaveBeenCalledWith('https://github.com/MuskStark/FengYu/releases')
    expect(autoUpdater.downloadUpdate).not.toHaveBeenCalled()
  })
})

describe('update:download-install (macOS unsigned fallback)', () => {
  beforeEach(() => {
    Object.defineProperty(process, 'platform', { value: 'darwin', configurable: true })
  })

  it('opens the releases page instead of quitAndInstall on macOS', async () => {
    const { shell } = await import('electron')
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const result = (await handlers.get('update:download-install')!({ sender: {} })) as {
      action: string
      releaseUrl: string
    }

    expect(autoUpdater.downloadUpdate).not.toHaveBeenCalled()
    expect(autoUpdater.quitAndInstall).not.toHaveBeenCalled()
    expect(shell.openExternal).toHaveBeenCalledTimes(1)
    expect(result.action).toBe('manual')
    expect(result.releaseUrl).toContain('releases')
  })
})

describe('progress / state broadcasts', () => {
  it('forwards download-progress to every renderer window', async () => {
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    listeners['download-progress']!({ percent: 42, transferred: 100, total: 240, bytesPerSecond: 10 })
    expect(sentMessages.some((m) => m.channel === 'update:progress')).toBe(true)
  })

  it('forwards update-downloaded as a state event', async () => {
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    listeners['update-downloaded']!()
    const stateMsg = sentMessages.find((m) => m.channel === 'update:state')
    expect(stateMsg).toBeDefined()
    expect((stateMsg!.payload as { state: string }).state).toBe('downloaded')
  })

  it('skips destroyed windows when broadcasting', async () => {
    allWindows[0].isDestroyed = () => true
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    listeners['download-progress']!({ percent: 1 })
    expect(sentMessages.length).toBe(0)
  })
})

describe('update:download-install (Windows portable)', () => {
  const PORTABLE_INFO = {
    version: '9.9.9',
    zipUrl: 'http://proxy.local:8088/fengyu-releases/download/portable.zip',
    sha256: 'b'.repeat(64),
    releaseUrl: 'http://proxy.local:8088/files',
    releaseName: 'Infinia 9.9.9',
  }

  beforeEach(() => {
    portableMode.value = true
    portableCheck.mockResolvedValue(PORTABLE_INFO)
    // Match PORTABLE_INFO's proxy URLs — the portable pipeline is FY-Proxy driven.
    process.env.FENGYU_UPDATE_API_BASE = 'http://proxy.local:8088'
  })

  it('downloads and applies the portable update after native consent', async () => {
    const { downloadAndExtractPortable, applyPortableUpdate } = await import('../src/updater/portable-updater')
    const { dialog, app } = await import('electron')
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const result = (await handlers.get('update:download-install')!({ sender: {} })) as { action: string }

    expect(result.action).toBe('restarting')
    // The portable consent dialog also names the source host.
    expect(dialog.showMessageBoxSync).toHaveBeenCalledWith(
      expect.objectContaining({ message: expect.stringContaining('from proxy.local:8088') }),
    )
    expect(downloadAndExtractPortable).toHaveBeenCalledTimes(1)
    expect(applyPortableUpdate).toHaveBeenCalledTimes(1)
    // Windows are destroyed (not closed) before quitting: a renderer beforeunload must never
    // get the chance to veto the quit and leave the replace bat waiting on this PID forever.
    expect(allWindows[0].destroy).toHaveBeenCalledTimes(1)
    expect(app.quit).toHaveBeenCalled()
  })

  it('aborts the robocopy/relaunch pipeline when consent is cancelled', async () => {
    const { downloadAndExtractPortable, applyPortableUpdate } = await import('../src/updater/portable-updater')
    const { dialog, app } = await import('electron')
    ;(dialog.showMessageBoxSync as ReturnType<typeof vi.fn>).mockReturnValueOnce(1)
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const result = (await handlers.get('update:download-install')!({ sender: {} })) as {
      action: string
      releaseUrl: string
    }

    expect(result.action).toBe('manual')
    expect(result.releaseUrl).toBe(PORTABLE_INFO.releaseUrl)
    expect(downloadAndExtractPortable).not.toHaveBeenCalled()
    expect(applyPortableUpdate).not.toHaveBeenCalled()
    expect(app.quit).not.toHaveBeenCalled()
  })
})

describe('update:set-api-base', () => {
  it('writes the renderer-supplied URL into process.env.FENGYU_UPDATE_API_BASE', async () => {
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    await handlers.get('update:set-api-base')!({}, 'http://10.0.0.5:8088')
    expect(process.env.FENGYU_UPDATE_API_BASE).toBe('http://10.0.0.5:8088')
  })

  it('also accepts https URLs (plain http stays allowed for intranet feeds)', async () => {
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    await handlers.get('update:set-api-base')!({}, 'https://proxy.corp.example')
    expect(process.env.FENGYU_UPDATE_API_BASE).toBe('https://proxy.corp.example')
  })

  it('rejects non-http(s) schemes without touching the current override', async () => {
    process.env.FENGYU_UPDATE_API_BASE = 'http://preexisting:9999'
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    await expect(invoke('update:set-api-base', {}, 'file:///etc/passwd')).rejects.toThrow(/HTTP or HTTPS/)
    await expect(invoke('update:set-api-base', {}, 'javascript:alert(1)')).rejects.toThrow(/HTTP or HTTPS/)
    expect(process.env.FENGYU_UPDATE_API_BASE).toBe('http://preexisting:9999')
  })

  it('rejects a non-URL string without touching the current override', async () => {
    process.env.FENGYU_UPDATE_API_BASE = 'http://preexisting:9999'
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    await expect(invoke('update:set-api-base', {}, 'not a url')).rejects.toThrow(/absolute HTTP/)
    expect(process.env.FENGYU_UPDATE_API_BASE).toBe('http://preexisting:9999')
  })

  it('rejects a non-string argument instead of silently clearing the channel', async () => {
    process.env.FENGYU_UPDATE_API_BASE = 'http://preexisting:9999'
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    await expect(invoke('update:set-api-base', {}, 123)).rejects.toThrow(/must be a string/)
    expect(process.env.FENGYU_UPDATE_API_BASE).toBe('http://preexisting:9999')
  })

  it('rejects URLs with embedded credentials at the IPC boundary without touching env', async () => {
    // Same rule updateApiBase() applies at check time — enforced HERE so the renderer cannot
    // store a URL that every later check would reject with a confusing error.
    process.env.FENGYU_UPDATE_API_BASE = 'http://preexisting:9999'
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    await expect(invoke('update:set-api-base', {}, 'http://user:pass@proxy:8088')).rejects.toThrow(
      /credentials/,
    )
    expect(process.env.FENGYU_UPDATE_API_BASE).toBe('http://preexisting:9999')
  })

  it('rejects URLs with query parameters or fragments at the IPC boundary without touching env', async () => {
    process.env.FENGYU_UPDATE_API_BASE = 'http://preexisting:9999'
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    await expect(invoke('update:set-api-base', {}, 'http://proxy:8088?token=x')).rejects.toThrow(
      /query parameters/,
    )
    await expect(invoke('update:set-api-base', {}, 'http://proxy:8088#latest')).rejects.toThrow(
      /fragment/,
    )
    expect(process.env.FENGYU_UPDATE_API_BASE).toBe('http://preexisting:9999')
  })

  it('logs the accepted api-base in credential-free origin form', async () => {
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => {})
    try {
      const { registerUpdateIpc } = await import('../src/ipc/update')
      registerUpdateIpc()

      await handlers.get('update:set-api-base')!({}, 'http://10.0.0.5:8088/fengyu-updates')
      // Exactly the origin — no path detail that could ever echo sensitive URL parts.
      expect(logSpy).toHaveBeenCalledWith('[updater] update api-base set to http://10.0.0.5:8088')
      expect(process.env.FENGYU_UPDATE_API_BASE).toBe('http://10.0.0.5:8088/fengyu-updates')
    } finally {
      logSpy.mockRestore()
    }
  })

  it('clears the override on an explicit empty string', async () => {
    process.env.FENGYU_UPDATE_API_BASE = 'http://preexisting:9999'
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    await handlers.get('update:set-api-base')!({}, '')
    expect(process.env.FENGYU_UPDATE_API_BASE).toBe('')
  })
})
