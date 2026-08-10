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
  currentVersion: '4.0.0',
  on: vi.fn((event: string, cb: (...args: unknown[]) => void) => {
    listeners[event] = cb
    return autoUpdater
  }),
}

const handlers = new Map<string, (...args: unknown[]) => unknown>()
const sentMessages: { channel: string; payload: unknown }[] = []
const allWindows: { isDestroyed: () => boolean; webContents: { send: (c: string, p: unknown) => void } }[] = []

vi.mock('electron-updater', () => ({ autoUpdater }))
vi.mock('electron', () => ({
  app: { quit: vi.fn() },
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
  isWindowsPortable: () => false,
  checkPortableUpdate: vi.fn(),
  downloadAndExtractPortable: vi.fn(),
  applyPortableUpdate: vi.fn(),
}))

const UPDATE_AVAILABLE = { updateInfo: { version: '9.9.9', releaseNotes: '' } }

beforeEach(async () => {
  // Reset the module under test so the process-wide `progressWired` guard re-registers listeners
  // against the freshly-cleared `listeners` map each test.
  vi.resetModules()
  vi.clearAllMocks()
  for (const k of Object.keys(listeners)) delete listeners[k]
  handlers.clear()
  sentMessages.length = 0
  allWindows.length = 0
  allWindows.push({ isDestroyed: () => false, webContents: { send: (c, p) => sentMessages.push({ channel: c, payload: p }) } })
  autoUpdater.autoDownload = true
  autoUpdater.autoInstallOnAppQuit = true
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

  it('returns updateAvailable=false when checkForUpdates throws', async () => {
    autoUpdater.checkForUpdates.mockRejectedValue(new Error('network down'))
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const result = (await handlers.get('update:check')!({ sender: {} })) as { updateAvailable: boolean }
    expect(result.updateAvailable).toBe(false)
  })

  it('reports no update when the latest version equals currentVersion', async () => {
    autoUpdater.currentVersion = '9.9.9'
    autoUpdater.checkForUpdates.mockResolvedValue(UPDATE_AVAILABLE)
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const result = (await handlers.get('update:check')!({ sender: {} })) as { updateAvailable: boolean }
    expect(result.updateAvailable).toBe(false)
    autoUpdater.currentVersion = '4.0.0'
  })
})

describe('update:download-install (Windows/Linux)', () => {
  beforeEach(() => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
  })

  it('downloads and installs on user consent (win/linux)', async () => {
    autoUpdater.downloadUpdate.mockResolvedValue(['/tmp/pkg'])
    const { registerUpdateIpc } = await import('../src/ipc/update')
    registerUpdateIpc()

    const result = (await handlers.get('update:download-install')!({ sender: {} })) as { action: string }

    expect(autoUpdater.autoDownload).toBe(false)
    expect(autoUpdater.autoInstallOnAppQuit).toBe(false)
    expect(autoUpdater.downloadUpdate).toHaveBeenCalledTimes(1)
    expect(autoUpdater.quitAndInstall).toHaveBeenCalledTimes(1)
    expect(result.action).toBe('restarting')
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
