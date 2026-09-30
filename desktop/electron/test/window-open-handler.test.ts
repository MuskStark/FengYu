import { describe, it, expect, vi, beforeEach } from 'vitest'

// Hoisted capture for handlers registered on the mocked webContents.
// vi.mock is hoisted above imports, so the capture object must be hoisted too.
const captured = vi.hoisted(() => ({
  openHandler: null as ((details: { url: string }) => { action: 'deny' }) | null,
  willNavigate: null as ((e: { preventDefault: () => void }, url: string) => void) | null,
  readyToShow: null as (() => void) | null,
  browserWindowOptions: null as Record<string, unknown> | null,
  headersReceived: null as ((details: {
    url?: string
    responseHeaders?: Record<string, string[]>
    resourceType?: string
  }, callback: (result: { responseHeaders?: Record<string, string[]> }) => void) => void) | null,
  // The packaged SPA entry document createMainWindow reads the inline-script hashes from.
  frontendIndexHtml: { value: '' },
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

vi.mock('node:fs', () => ({
  // createMainWindow reads the built SPA entry to extract the import-map CSP hash.
  readFileSync: vi.fn(() => captured.frontendIndexHtml.value),
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

/** A realistic built index.html: meta CSP with the single import-map sha256 hash (P2-21). */
const BUILT_INDEX_HTML =
  '<head><meta http-equiv="Content-Security-Policy" content="default-src \'self\'; ' +
  "script-src 'self' 'wasm-unsafe-eval' 'sha256-QWTZLXhDD5hKxFhesU2m1DRw9EOg/NEZcH7hCF5kSbk='; " +
  'style-src \'self\' \'unsafe-inline\'"></head>'

const IMPORT_MAP_HASH = "'sha256-QWTZLXhDD5hKxFhesU2m1DRw9EOg/NEZcH7hCF5kSbk='"

describe('createMainWindow navigation guards', () => {
  beforeEach(() => {
    captured.openHandler = null
    captured.willNavigate = null
    captured.readyToShow = null
    captured.browserWindowOptions = null
    captured.headersReceived = null
    captured.frontendIndexHtml.value = BUILT_INDEX_HTML
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
    captured.headersReceived!({ url: 'app://shell/index.html', responseHeaders: {}, resourceType: 'mainFrame' }, callback)
    const csp = callback.mock.calls[0][0].responseHeaders['Content-Security-Policy'][0]
    expect(csp).toContain("default-src 'self'")
    expect(csp).toContain('http://127.0.0.1:24123')
    expect(csp).toContain('http://localhost:24123')
    expect(csp).not.toContain("'unsafe-eval'")
  })

  it('P2-21: production script-src drops unsafe-inline and admits only the import-map hash', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: 'http://127.0.0.1:24056',
      token: '',
      onHideToTray: () => {},
      isDev: false,
      isQuitting: () => false,
    })

    const callback = vi.fn()
    captured.headersReceived!({ url: 'app://shell/index.html', responseHeaders: {}, resourceType: 'mainFrame' }, callback)
    const csp = callback.mock.calls[0][0].responseHeaders['Content-Security-Policy'][0]
    const scriptSrc = csp.split('; ').find((d: string) => d.startsWith('script-src'))
    // No unsafe-inline: the header policy no longer weakens the meta CSP. The single
    // inline script (the shared-Vue import map) stays admitted via its exact content hash,
    // so removing unsafe-inline cannot white-screen the packaged app.
    expect(scriptSrc).toBe(`script-src 'self' ${IMPORT_MAP_HASH}`)
    expect(scriptSrc).not.toContain("'unsafe-inline'")
  })

  it('P2-21: fails open to unsafe-inline when the entry document carries no CSP hash', async () => {
    captured.frontendIndexHtml.value = '<head><meta charset="utf-8"></head>'
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: 'http://127.0.0.1:24056',
      token: '',
      onHideToTray: () => {},
      isDev: false,
      isQuitting: () => false,
    })

    const callback = vi.fn()
    captured.headersReceived!({ url: 'app://shell/index.html', responseHeaders: {}, resourceType: 'mainFrame' }, callback)
    const csp = callback.mock.calls[0][0].responseHeaders['Content-Security-Policy'][0]
    // An anomalous build keeps the pre-hardening behavior instead of risking a white screen.
    expect(csp).toContain("script-src 'self' 'unsafe-inline'")
  })

  it('P2-21: keeps unsafe-inline/unsafe-eval in dev (Vite HMR requires them)', async () => {
    const { contentSecurityPolicy } = await import('../src/window/create-window')
    const csp = contentSecurityPolicy({ apiBase: '', isDev: true })
    expect(csp).toContain("script-src 'self' 'unsafe-inline' 'unsafe-eval'")
  })

  it('scopes the header CSP to the main window entry document, not every mainFrame', async () => {
    const { createMainWindow } = await import('../src/window/create-window')
    createMainWindow({
      apiBase: 'http://127.0.0.1:24056',
      token: '',
      onHideToTray: () => {},
      isDev: false,
      isQuitting: () => false,
    })

    // A mainFrame document that is NOT the shell entry (e.g. a future window sharing the
    // default session) must not inherit the shell policy — its own CSP stays untouched.
    const responseHeaders = { 'Content-Security-Policy': ["default-src 'none'"] }
    const callback = vi.fn()
    captured.headersReceived!({ url: 'http://localhost:9999/other.html', responseHeaders, resourceType: 'mainFrame' }, callback)
    expect(callback).toHaveBeenCalledWith({ responseHeaders })
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
    captured.headersReceived!({ url: 'http://127.0.0.1:24056/plugin-runtime/x/ui/', responseHeaders, resourceType: 'subFrame' }, callback)
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

describe('extractCspScriptHashes (P2-21)', () => {
  it('extracts every sha256 hash source from the meta CSP', async () => {
    const { extractCspScriptHashes } = await import('../src/window/create-window')
    expect(extractCspScriptHashes(BUILT_INDEX_HTML)).toEqual([IMPORT_MAP_HASH])
    const multiple = BUILT_INDEX_HTML.replace(
      IMPORT_MAP_HASH,
      `${IMPORT_MAP_HASH} 'sha256-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfG='`,
    )
    expect(extractCspScriptHashes(multiple)).toHaveLength(2)
  })

  it('returns [] for documents without a meta CSP or without hashes', async () => {
    const { extractCspScriptHashes } = await import('../src/window/create-window')
    expect(extractCspScriptHashes('<html><body></body></html>')).toEqual([])
    expect(extractCspScriptHashes('<meta http-equiv="Content-Security-Policy" content="default-src \'self\'">')).toEqual([])
  })
})
