import { describe, it, expect, vi, beforeEach } from 'vitest'

// Hoisted capture for handlers registered on the mocked webContents.
// vi.mock is hoisted above imports, so the capture object must be hoisted too.
const captured = vi.hoisted(() => ({
  openHandler: null as ((details: { url: string }) => { action: 'deny' }) | null,
  willNavigate: null as ((e: { preventDefault: () => void }, url: string) => void) | null,
  readyToShow: null as (() => void) | null,
  browserWindowOptions: null as Record<string, unknown> | null,
  headersReceived: null as ((details: {
    responseHeaders?: Record<string, string[]>
    resourceType?: string
  }, callback: (result: { responseHeaders?: Record<string, string[]> }) => void) => void) | null,
  shellOpenExternal: vi.fn<(url: string) => Promise<void>>(),
  show: vi.fn(),
  setWindowButtonVisibility: vi.fn(),
  setWindowButtonPosition: vi.fn(),
  getURL: vi.fn<() => string>(() => 'http://127.0.0.1:5173/'),
  setWindowOpenHandler: vi.fn((fn: (details: { url: string }) => { action: 'deny' }) => {
    captured.openHandler = fn
  }),
  wcOn: vi.fn((evt: string, fn: (e: { preventDefault: () => void }, url: string) => void) => {
    if (evt === 'will-navigate') captured.willNavigate = fn
  }),
  once: vi.fn((evt: string, fn: () => void) => {
    if (evt === 'ready-to-show') captured.readyToShow = fn
  }),
}))

vi.mock('electron', () => ({
  // Vitest 4 spies no longer construct when the implementation is an arrow function,
  // and the SUT calls `new BrowserWindow(...)` — keep the implementation constructible.
  BrowserWindow: vi.fn().mockImplementation(function (options: Record<string, unknown>) {
    captured.browserWindowOptions = options
    return {
      on: vi.fn(),
      once: captured.once,
      show: captured.show,
      setWindowButtonVisibility: captured.setWindowButtonVisibility,
      setWindowButtonPosition: captured.setWindowButtonPosition,
      isDestroyed: vi.fn(() => false),
      loadURL: vi.fn(),
      loadFile: vi.fn(),
      webContents: {
        id: 7,
        openDevTools: vi.fn(),
        setWindowOpenHandler: captured.setWindowOpenHandler,
        on: captured.wcOn,
        getURL: captured.getURL,
        session: {
          webRequest: {
            onHeadersReceived: vi.fn((fn) => {
              captured.headersReceived = fn
            }),
          },
        },
      },
    }
  }),
  shell: { openExternal: captured.shellOpenExternal },
}))

describe('createMainWindow navigation guards', () => {
  beforeEach(() => {
    captured.openHandler = null
    captured.willNavigate = null
    captured.readyToShow = null
    captured.browserWindowOptions = null
    captured.headersReceived = null
    captured.shellOpenExternal.mockClear()
    captured.show.mockClear()
    captured.setWindowButtonVisibility.mockClear()
    captured.setWindowButtonPosition.mockClear()
    captured.once.mockClear()
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

  it('keeps the window hidden on a dark surface until the first renderer paint', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: '',
      token: '',
      onHideToTray: () => {},
      isDev: true,
      isQuitting: () => false,
    })

    expect(captured.browserWindowOptions).toMatchObject({
      show: false,
      backgroundColor: '#0d0d0d',
    })
    if (process.platform === 'darwin') {
      expect(captured.browserWindowOptions).toMatchObject({ frame: false, titleBarStyle: 'hidden' })
      expect(captured.setWindowButtonVisibility).toHaveBeenCalledWith(true)
      expect(captured.setWindowButtonPosition).toHaveBeenCalledWith({ x: 14, y: 18 })
      expect(captured.setWindowButtonVisibility.mock.invocationCallOrder[0])
        .toBeLessThan(captured.setWindowButtonPosition.mock.invocationCallOrder[0])
    } else {
      expect(captured.browserWindowOptions).not.toHaveProperty('frame')
      expect(captured.setWindowButtonVisibility).not.toHaveBeenCalled()
      expect(captured.setWindowButtonPosition).not.toHaveBeenCalled()
    }
    expect(captured.show).not.toHaveBeenCalled()
    expect(captured.readyToShow).not.toBeNull()
    captured.readyToShow!()
    expect(captured.show).toHaveBeenCalledTimes(1)
  })

  it('uses the cached light surface before the renderer paints', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: '',
      token: '',
      theme: 'light',
      onHideToTray: () => {},
      isDev: false,
      isQuitting: () => false,
    })

    expect(captured.browserWindowOptions).toMatchObject({
      show: false,
      backgroundColor: '#ffffff',
    })
  })

  it('injects a strict production CSP that permits only the selected loopback backend', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: 'http://127.0.0.1:24123',
      token: '',
      onHideToTray: () => {},
      isDev: false,
      isQuitting: () => false,
    })

    const callback = vi.fn()
    captured.headersReceived!({ responseHeaders: {}, resourceType: 'mainFrame' }, callback)
    const csp = callback.mock.calls[0][0].responseHeaders['Content-Security-Policy'][0]
    expect(csp).toContain("default-src 'self'")
    expect(csp).toContain('http://127.0.0.1:24123')
    expect(csp).toContain('http://localhost:24123')
    expect(csp).not.toContain("'unsafe-eval'")
  })

  it('preserves the backend CSP on plugin iframe documents', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: 'http://127.0.0.1:24056',
      token: '',
      onHideToTray: () => {},
      isDev: false,
      isQuitting: () => false,
    })

    const responseHeaders = { 'Content-Security-Policy': ["default-src 'self'; connect-src 'none'"] }
    const callback = vi.fn()
    captured.headersReceived!({ responseHeaders, resourceType: 'subFrame' }, callback)
    expect(callback).toHaveBeenCalledWith({ responseHeaders })
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
    // Same origin with a different path/query -> allow.
    captured.willNavigate!({ preventDefault: prevented }, 'http://127.0.0.1:5173/about?from=test')
    expect(prevented).not.toHaveBeenCalled()
  })

  it('will-navigate keeps packaged file navigation on the loaded entry file', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: 'http://127.0.0.1:24056',
      token: '',
      onHideToTray: () => {},
      isDev: false,
      isQuitting: () => false,
    })
    captured.getURL.mockReturnValue('file:///Applications/Infinia/frontend-dist/index.html#/about')

    const allowed = vi.fn()
    captured.willNavigate!({ preventDefault: allowed }, 'file:///Applications/Infinia/frontend-dist/index.html#/settings')
    expect(allowed).not.toHaveBeenCalled()

    const blocked = vi.fn()
    captured.willNavigate!({ preventDefault: blocked }, 'file:///etc/passwd')
    expect(blocked).toHaveBeenCalledTimes(1)
  })
})
