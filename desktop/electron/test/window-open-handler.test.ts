import { describe, it, expect, vi, beforeEach } from 'vitest'

// Hoisted capture for handlers registered on the mocked webContents.
// vi.mock is hoisted above imports, so the capture object must be hoisted too.
const captured = vi.hoisted(() => ({
  openHandler: null as ((details: { url: string }) => { action: 'deny' }) | null,
  willNavigate: null as ((e: { preventDefault: () => void }, url: string) => void) | null,
  shellOpenExternal: vi.fn<(url: string) => Promise<void>>(),
  getURL: vi.fn<() => string>(() => 'http://127.0.0.1:5173/'),
  setWindowOpenHandler: vi.fn((fn: (details: { url: string }) => { action: 'deny' }) => {
    captured.openHandler = fn
  }),
  wcOn: vi.fn((evt: string, fn: (e: { preventDefault: () => void }, url: string) => void) => {
    if (evt === 'will-navigate') captured.willNavigate = fn
  }),
}))

vi.mock('electron', () => ({
  BrowserWindow: vi.fn().mockImplementation(() => ({
    on: vi.fn(),
    loadURL: vi.fn(),
    loadFile: vi.fn(),
    webContents: {
      openDevTools: vi.fn(),
      setWindowOpenHandler: captured.setWindowOpenHandler,
      on: captured.wcOn,
      getURL: captured.getURL,
    },
  })),
  session: { defaultSession: { webRequest: { onHeadersReceived: vi.fn() } } },
  shell: { openExternal: captured.shellOpenExternal },
}))

describe('createMainWindow navigation guards', () => {
  beforeEach(() => {
    captured.openHandler = null
    captured.willNavigate = null
    captured.shellOpenExternal.mockClear()
    captured.getURL.mockReturnValue('http://127.0.0.1:5173/')
  })

  it('registers setWindowOpenHandler and a will-navigate listener', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: '',
      token: '',
      onHideToTray: () => {},
      isDev: true,
      isQuitting: () => false,
    })
    expect(captured.setWindowOpenHandler).toHaveBeenCalledTimes(1)
    expect(captured.wcOn).toHaveBeenCalledWith('will-navigate', expect.any(Function))
    expect(captured.willNavigate).not.toBeNull()
  })

  it('denies window.open for http(s) AND delegates to shell.openExternal', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: '',
      token: '',
      onHideToTray: () => {},
      isDev: true,
      isQuitting: () => false,
    })
    const result = captured.openHandler!({ url: 'https://example.com/path' })
    expect(result).toEqual({ action: 'deny' })
    expect(captured.shellOpenExternal).toHaveBeenCalledWith('https://example.com/path')
  })

  it('denies window.open for non-http(s) WITHOUT calling shell.openExternal', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: '',
      token: '',
      onHideToTray: () => {},
      isDev: true,
      isQuitting: () => false,
    })
    const result = captured.openHandler!({ url: 'file:///etc/passwd' })
    expect(result).toEqual({ action: 'deny' })
    expect(captured.shellOpenExternal).not.toHaveBeenCalled()
  })

  it('will-navigate blocks cross-origin in-page navigation', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: '',
      token: '',
      onHideToTray: () => {},
      isDev: true,
      isQuitting: () => false,
    })
    const prevented = vi.fn()
    // getURL returns the SPA origin; an https URL is a different origin -> preventDefault.
    captured.willNavigate!({ preventDefault: prevented }, 'https://evil.example/')
    expect(prevented).toHaveBeenCalledTimes(1)
  })

  it('will-navigate allows same-origin in-page navigation', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: '',
      token: '',
      onHideToTray: () => {},
      isDev: true,
      isQuitting: () => false,
    })
    const prevented = vi.fn()
    // Same URL as getURL() -> allow.
    captured.willNavigate!({ preventDefault: prevented }, 'http://127.0.0.1:5173/')
    expect(prevented).not.toHaveBeenCalled()
  })
})
