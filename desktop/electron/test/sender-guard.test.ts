import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * Unit tests for the IPC sender guard (src/ipc/sender-guard.ts) and its wiring into a
 * real handler (ipc/external.ts). Privileged ipcMain channels must only serve the main
 * window's webContents; the guard allows everything while no resolver is installed (the
 * unit-test default) and enforces the main-window identity once main.ts installs one.
 */

const { ipcHandle, openExternal } = vi.hoisted(() => ({
  ipcHandle: vi.fn(),
  openExternal: vi.fn(async () => {}),
}))

vi.mock('electron', () => ({
  ipcMain: { handle: ipcHandle },
  shell: { openExternal },
}))

function handler(channel: string): (...args: unknown[]) => unknown {
  const call = ipcHandle.mock.calls.find(([name]) => name === channel)
  if (!call) throw new Error(`no handler registered for ${channel}`)
  return call[1] as (...args: unknown[]) => unknown
}

function fakeMainWindow(destroyed = false) {
  return {
    isDestroyed: vi.fn(() => destroyed),
    webContents: { id: 7 },
  } as unknown as Electron.BrowserWindow
}

describe('IPC sender guard', () => {
  afterEach(async () => {
    const { setMainWindowResolver } = await import('../src/ipc/sender-guard')
    setMainWindowResolver(null)
  })

  it('allows every sender while no resolver is installed (unit-test registrations)', async () => {
    const { isMainWindowSender, setMainWindowResolver } = await import('../src/ipc/sender-guard')
    setMainWindowResolver(null)
    expect(isMainWindowSender(undefined)).toBe(true)
    expect(isMainWindowSender({})).toBe(true)
  })

  it('accepts only the main window webContents once the resolver is installed', async () => {
    const { isMainWindowSender, setMainWindowResolver } = await import('../src/ipc/sender-guard')
    const win = fakeMainWindow()
    setMainWindowResolver(() => win)

    expect(isMainWindowSender(win.webContents)).toBe(true)
    expect(isMainWindowSender({ id: 99 })).toBe(false)
    expect(isMainWindowSender(undefined)).toBe(false)
  })

  it('rejects senders while the main window is destroyed', async () => {
    const { isMainWindowSender, setMainWindowResolver } = await import('../src/ipc/sender-guard')
    const win = fakeMainWindow()
    setMainWindowResolver(() => win)

    win.isDestroyed.mockReturnValue(true)
    expect(isMainWindowSender(win.webContents)).toBe(false)
  })

  it('rejects senders when the main window does not exist yet', async () => {
    const { isMainWindowSender, setMainWindowResolver } = await import('../src/ipc/sender-guard')
    setMainWindowResolver(() => null)
    expect(isMainWindowSender({})).toBe(false)
  })

  it('external:open refuses a non-main sender but serves the main window', async () => {
    const { setMainWindowResolver } = await import('../src/ipc/sender-guard')
    const { registerExternalIpc } = await import('../src/ipc/external')
    ipcHandle.mockClear()
    registerExternalIpc()

    const win = fakeMainWindow()
    setMainWindowResolver(() => win)

    await expect(handler('external:open')({ sender: { id: 99 } }, 'https://example.com'))
      .rejects.toThrow(/sender is not the main window/)
    expect(openExternal).not.toHaveBeenCalled()

    await handler('external:open')({ sender: win.webContents }, 'https://example.com')
    // httpUrl() normalizes through new URL(...).toString() — the trailing slash is expected.
    expect(openExternal).toHaveBeenCalledWith('https://example.com/')
  })
})
