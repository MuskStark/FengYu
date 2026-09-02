import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub electron with a mockable webContents/debugger. The session module imports
// { BrowserWindow } from 'electron', so we mock it before importing SUT.
//
// `sendCommand` is captured into `cdpCalls` so click/type tests can assert the exact
// CDP Input.* sequence (pointer focus, keyboard clear, and insertText), which is the
// whole point of the real-input rewrite: JS-synthesised el.click() and el.value=
// assignment would leave these arrays empty.
const execJs = vi.fn()
const loadURL = vi.fn()
const capturePage = vi.fn()
const attach = vi.fn()
const detach = vi.fn()
const isAttached = vi.fn(() => false)
const createFromBuffer = vi.fn()
const shellOpenExternal = vi.fn()
const canGoBack = vi.fn()
const canGoForward = vi.fn()
const goBack = vi.fn()
const goForward = vi.fn()
const reload = vi.fn()
const cdpCalls: Array<{ method: string; params: Record<string, unknown> }> = []
const sendCommand = vi.fn(async (method: string, params: Record<string, unknown> = {}) => {
  cdpCalls.push({ method, params })
  // Accessibility.getFullAXTree is the normal non-Input caller; return an empty tree.
  return { nodes: [] }
})
// Guards registered on the automation window's webContents (session.ts ensureWindow).
let openHandler: ((details: { url: string }) => { action: string }) | null = null
const wcEvents: Record<string, (e: { preventDefault: () => void }, url?: string) => void> = {}

vi.mock('electron', () => ({
  nativeImage: { createFromBuffer },
  shell: { openExternal: shellOpenExternal },
  // Vitest 4 spies no longer construct when the implementation is an arrow function,
  // and the SUT calls `new BrowserWindow(...)` — keep the implementation constructible.
  BrowserWindow: vi.fn().mockImplementation(function () {
    return {
      isDestroyed: () => false,
      destroy: vi.fn(),
      webContents: {
        loadURL,
        executeJavaScript: execJs,
        capturePage,
        isLoading: () => false,
        once: vi.fn(),
        on: vi.fn((evt: string, fn: (e: { preventDefault: () => void }, url?: string) => void) => {
          wcEvents[evt] = fn
        }),
        setWindowOpenHandler: vi.fn((fn: (details: { url: string }) => { action: string }) => {
          openHandler = fn
        }),
        debugger: {
          attach,
          detach,
          isAttached,
          sendCommand,
        },
        getURL: () => 'https://example.com',
        getTitle: () => 'Example',
        navigationHistory: { canGoBack, canGoForward, goBack, goForward },
        reload,
      },
    }
  }),
}))

// Import AFTER mocks are registered.
const { BrowserSession } = await import('../src/browser/session')
const { handleBrowserOp } = await import('../src/browser/handlers')

describe('handleBrowserOp', () => {
  beforeEach(() => {
    execJs.mockReset(); loadURL.mockReset(); capturePage.mockReset()
    attach.mockReset(); detach.mockReset(); isAttached.mockReset(); isAttached.mockReturnValue(false)
    createFromBuffer.mockReset(); sendCommand.mockClear()
    canGoBack.mockReset(); canGoForward.mockReset(); goBack.mockReset(); goForward.mockReset(); reload.mockReset()
    cdpCalls.length = 0
  })

  it('navigate creates the window and loads the url', async () => {
    loadURL.mockResolvedValue(undefined)
    execJs.mockResolvedValue('Example')
    const s = new BrowserSession()
    const r = await handleBrowserOp(s, 'browser_navigate', { url: 'https://example.com' })
    expect(loadURL).toHaveBeenCalledWith('https://example.com')
    expect(r.success).toBe(true)
    // Title must come from the live DOM (executeJavaScript 'document.title'), not getTitle().
    expect(execJs).toHaveBeenCalledWith('document.title')
    expect(r.title).toBe('Example')
  })

  it('navigate honors waitUntil:networkidle with a settle delay', async () => {
    loadURL.mockResolvedValue(undefined)
    execJs.mockResolvedValue('Idle Page')
    const s = new BrowserSession()
    const start = Date.now()
    const r = await handleBrowserOp(s, 'browser_navigate', {
      url: 'https://example.com',
      waitUntil: 'networkidle',
    })
    // The networkidle path must wait the documented 500ms degrade delay.
    expect(Date.now() - start).toBeGreaterThanOrEqual(480)
    expect(r.success).toBe(true)
    expect(execJs).toHaveBeenCalledWith('document.title')
    expect(r.title).toBe('Idle Page')
  })

  it('history goes back through navigationHistory and returns page state', async () => {
    canGoBack.mockReturnValue(true)
    execJs.mockResolvedValue('Previous')
    const s = new BrowserSession()
    s.ensureWindow()

    const r = await handleBrowserOp(s, 'browser_history', { action: 'back' })

    expect(r.success).toBe(true)
    expect(goBack).toHaveBeenCalledOnce()
    expect(r.action).toBe('back')
    expect(r.title).toBe('Previous')
  })

  it('history fails without dispatch when there is no forward entry', async () => {
    canGoForward.mockReturnValue(false)
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_history', { action: 'forward' })
    expect(r.success).toBe(false)
    expect(r.summary).toContain('no forward history')
    expect(goForward).not.toHaveBeenCalled()
  })

  it('click returns no session when window absent', async () => {
    const s = new BrowserSession()
    const r = await handleBrowserOp(s, 'browser_click', { selector: '#x' })
    expect(r.success).toBe(false)
    expect(r.summary).toContain('no browser session')
  })

  it('get_text returns the executed innerText', async () => {
    execJs.mockResolvedValue('hello')
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_get_text', {})
    expect(r.success).toBe(true)
    expect(r.text).toBe('hello')
    expect(r.length).toBe(5)
  })

  it('query returns count and samples', async () => {
    execJs.mockResolvedValue({ count: 2, samples: ['a', 'b'] })
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_query', { selector: 'div' })
    expect(r.count).toBe(2)
    expect(r.samples).toEqual(['a', 'b'])
  })

  it('close destroys the window', async () => {
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_close', {})
    expect(r.success).toBe(true)
    expect(r.closed).toBe(true)
    expect(s.window()).toBeNull()
  })

  it('screenshot saves a PNG and returns an imagePath', async () => {
    // Fake NativeImage: a 1x1 PNG with a valid signature.
    capturePage.mockResolvedValue({
      toPNG: () => Buffer.from([0x89, 0x50, 0x4e, 0x47]),
      getSize: () => ({ width: 1, height: 1 }),
    })
    execJs.mockResolvedValue({
      url: 'https://example.com', title: 'Example', count: 1,
      snapshot: 'URL: https://example.com\n[snap_1] button "Go"',
    })
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_screenshot', {})
    expect(r.success).toBe(true)
    expect(String(r.imagePath)).toMatch(/\.png$/)
    expect(r.mimeType).toBe('image/png')
    expect(r.imageBase64).toBe(Buffer.from([0x89, 0x50, 0x4e, 0x47]).toString('base64'))
    expect(r.domSnapshot).toContain('[snap_1] button')
  })

  it('full-page screenshot captures the complete CDP content bounds', async () => {
    const image = {
      isEmpty: () => false,
      toPNG: () => Buffer.from([0x89, 0x50, 0x4e, 0x47]),
      getSize: () => ({ width: 1440, height: 4200 }),
    }
    createFromBuffer.mockReturnValue(image)
    sendCommand.mockImplementation(async (method: string, params: Record<string, unknown> = {}) => {
      cdpCalls.push({ method, params })
      if (method === 'Page.getLayoutMetrics') {
        return { cssContentSize: { width: 1439.2, height: 4199.1 } }
      }
      if (method === 'Page.captureScreenshot') return { data: Buffer.from('png').toString('base64') }
      return { nodes: [] }
    })
    execJs.mockResolvedValue({
      url: 'https://example.com', title: 'Example', count: 0,
      snapshot: 'URL: https://example.com',
    })

    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_screenshot', { fullPage: true })

    expect(r.success).toBe(true)
    expect(capturePage).not.toHaveBeenCalled()
    expect(cdpCalls).toContainEqual({ method: 'Page.getLayoutMetrics', params: {} })
    expect(cdpCalls).toContainEqual({
      method: 'Page.captureScreenshot',
      params: {
        format: 'png', fromSurface: true, captureBeyondViewport: true,
        clip: { x: 0, y: 0, width: 1440, height: 4200, scale: 1 },
      },
    })
    expect(createFromBuffer).toHaveBeenCalledOnce()
    expect(r.width).toBe(1440)
    expect(r.height).toBe(4200)
  })

  // ── real-input (CDP) click/type + element refs ──────────────────────────────

  it('find stamps a ref and returns element metadata', async () => {
    // In-page JS returns the descriptive object the real handler builds.
    execJs.mockResolvedValue({
      tag: 'input', role: 'textbox', name: 'user', id: 'username',
      type: 'text', value: '', placeholder: 'Username', text: '',
      rect: { x: 10, y: 20, w: 200, h: 30 },
    })
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_find', { selector: '#username' })
    expect(r.success).toBe(true)
    expect(r.ref).toBe('el_1')
    expect(r.tag).toBe('input')
    expect(r.name).toBe('user')
    expect(String(execJs.mock.calls[0][0])).toContain('timed out waiting for selector')
  })

  it('find fails clearly when the selector matches multiple elements without nth', async () => {
    execJs.mockResolvedValue({ error: 'selector matched 3 elements; pass nth (1-based) or refine the selector' })
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_find', { selector: 'input' })
    expect(r.success).toBe(false)
    expect(r.summary).toContain('matched 3 elements')
  })

  it('snapshot returns visible semantic controls with reusable refs', async () => {
    execJs.mockResolvedValue({
      url: 'https://www.baidu.com/', title: '百度一下', count: 2,
      snapshot: 'URL: https://www.baidu.com/\nTitle: 百度一下\nInteractive elements:\n[snap_x_1] searchbox "百度一下"\n[snap_x_2] button "百度一下"',
    })
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_snapshot', {})
    expect(r.success).toBe(true)
    expect(r.count).toBe(2)
    expect(r.snapshot).toContain('[snap_x_1] searchbox')
    const jsArg = String(execJs.mock.calls[0][0])
    expect(jsArg).toContain('Interactive elements:')
    expect(jsArg).toContain('data-fengyu-ref')
    expect(jsArg).toContain('getBoundingClientRect')
  })

  it('click dispatches a real CDP mouse move+press+release sequence', async () => {
    // The in-page actionability wait returns a hit-tested point.
    execJs.mockResolvedValue({ x: 150, y: 300 })
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_click', { selector: '#login' })
    expect(r.success).toBe(true)
    expect(r.clicked).toBe(true)
    expect(String(execJs.mock.calls[0][0])).toContain('elementFromPoint')
    expect(String(execJs.mock.calls[0][0])).toContain('element is moving')
    // Three CDP Input.dispatchMouseEvent calls: mouseMoved, mousePressed, mouseReleased.
    const mouse = cdpCalls.filter((c) => c.method === 'Input.dispatchMouseEvent')
    expect(mouse).toHaveLength(3)
    expect(mouse.map((c) => c.params.type)).toEqual(['mouseMoved', 'mousePressed', 'mouseReleased'])
    // Each must carry the resolved coordinates and the left button.
    for (const c of mouse) {
      expect(c.params.x).toBe(150)
      expect(c.params.y).toBe(300)
      expect(c.params.button).toBe('left')
    }
  })

  it('hover dispatches only a real CDP mouse move', async () => {
    execJs.mockResolvedValueOnce({ x: 42, y: 84 }).mockResolvedValueOnce('Hover page')
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_hover', { ref: 'snap_hover_1' })
    expect(r.success).toBe(true)
    expect(r.hovered).toBe(true)
    const mouse = cdpCalls.filter((c) => c.method === 'Input.dispatchMouseEvent')
    expect(mouse).toHaveLength(1)
    expect(mouse[0].params).toMatchObject({ type: 'mouseMoved', x: 42, y: 84, buttons: 0 })
  })

  it('scroll dispatches a bounded CDP wheel event at a target', async () => {
    execJs.mockResolvedValueOnce({ x: 200, y: 300 }).mockResolvedValueOnce('Scrolled page')
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_scroll', {
      selector: '.panel', deltaX: -50, deltaY: 99_999,
    })
    expect(r.success).toBe(true)
    expect(r.deltaY).toBe(10_000)
    const mouse = cdpCalls.filter((c) => c.method === 'Input.dispatchMouseEvent')
    expect(mouse.map((c) => c.params.type)).toEqual(['mouseMoved', 'mouseWheel'])
    expect(mouse[1].params).toMatchObject({ x: 200, y: 300, deltaX: -50, deltaY: 10_000 })
  })

  it('batch performs snapshot and click in one handler call', async () => {
    execJs
      .mockResolvedValueOnce({
        url: 'https://example.com', title: 'Example', count: 1,
        snapshot: 'URL: https://example.com\n[snap_1] button "Go"',
      })
      .mockResolvedValueOnce({ x: 20, y: 30 })
      .mockResolvedValueOnce('After click')
    const s = new BrowserSession()
    s.ensureWindow()

    const r = await handleBrowserOp(s, 'browser_batch', { action: 'click', ref: 'snap_1' })

    expect(r.success).toBe(true)
    expect(r.summary).toBe('snapshot + click completed')
    expect(r.snapshot).toContain('[snap_1] button')
    expect(Array.isArray(r.results)).toBe(true)
    expect(cdpCalls.filter((c) => c.method === 'Input.dispatchMouseEvent')).toHaveLength(3)
  })

  it('click by ref reuses the data-fengyu-ref attribute selector', async () => {
    execJs.mockResolvedValue({ x: 50, y: 60 })
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_click', { ref: 'el_2' })
    expect(r.success).toBe(true)
    expect(r.summary).toContain('el_2')
    // The in-page locator must resolve via the stamped attribute, not a bare selector.
    expect(execJs).toHaveBeenCalledWith(expect.stringContaining('data-fengyu-ref'))
  })

  it('click uses strict selector matching unless nth or ref disambiguates it', async () => {
    execJs.mockRejectedValue(new Error('selector matched 2 elements; pass nth (1-based), a ref, or refine the selector'))
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_click', { selector: 'button' })
    expect(r.success).toBe(false)
    expect(r.summary).toContain('selector matched 2 elements')
    expect(cdpCalls).toHaveLength(0)
  })

  it('type pointer-focuses, keyboard-clears, inserts, and verifies the value', async () => {
    execJs.mockResolvedValueOnce({ x: 100, y: 120 }).mockResolvedValueOnce(true).mockResolvedValueOnce('alice')
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_type', { selector: '#user', text: 'alice', clear: true })
    expect(r.success).toBe(true)
    expect(r.filled).toBe(true)
    // The insert must go through the browser's real text-edit pipeline, not value assignment.
    const insert = cdpCalls.filter((c) => c.method === 'Input.insertText')
    expect(insert).toHaveLength(1)
    expect(insert[0].params.text).toBe('alice')
    // Clear must use trusted keyboard editing, not a direct value assignment.
    const keys = cdpCalls.filter((c) => c.method === 'Input.dispatchKeyEvent')
    expect(keys.map((c) => c.params.key)).toEqual(['a', 'a', 'Backspace', 'Backspace'])
    const jsArg = String(execJs.mock.calls[0][0])
    expect(jsArg).toContain('elementFromPoint')
    expect(jsArg).not.toContain("el.value = ''")
    expect(r.value).toBe('alice')
  })

  it('type without clear preserves the current selection and omits keyboard clearing', async () => {
    execJs.mockResolvedValueOnce({ x: 100, y: 120 }).mockResolvedValueOnce(true)
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_type', { selector: '#user', text: 'bob', clear: false })
    expect(r.success).toBe(true)
    expect(cdpCalls.filter((c) => c.method === 'Input.dispatchKeyEvent')).toHaveLength(0)
    expect(cdpCalls.filter((c) => c.method === 'Input.insertText')).toHaveLength(1)
    expect(execJs).toHaveBeenCalledTimes(2)
  })

  it('type reports failure when a controlled field restores a different value', async () => {
    execJs.mockResolvedValueOnce({ x: 100, y: 120 }).mockResolvedValueOnce(true).mockResolvedValueOnce('server-value')
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_type', { selector: '#user', text: 'alice' })
    expect(r.success).toBe(false)
    expect(r.summary).toContain('typed text did not persist')
  })

  it('type cancels before sending keys when the target did not receive focus', async () => {
    execJs.mockResolvedValueOnce({ x: 100, y: 120 }).mockResolvedValueOnce(false)
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_type', { selector: '#user', text: 'alice' })
    expect(r.success).toBe(false)
    expect(r.summary).toContain('did not receive focus')
    expect(cdpCalls.filter((c) => c.method === 'Input.insertText')).toHaveLength(0)
  })

  it('press focuses the referenced input and dispatches Enter through CDP', async () => {
    execJs.mockResolvedValueOnce({ x: 100, y: 120 }).mockResolvedValueOnce(true)
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_press', { ref: 'snap_x_1', key: 'Enter' })
    expect(r.success).toBe(true)
    expect(r.pressed).toBe(true)
    const keys = cdpCalls.filter((c) => c.method === 'Input.dispatchKeyEvent')
    expect(keys.map((c) => c.params.type)).toEqual(['keyDown', 'keyUp'])
    expect(keys.map((c) => c.params.key)).toEqual(['Enter', 'Enter'])
    expect(keys[0].params.text).toBe('\r')
    expect(execJs.mock.calls.some((call) => String(call[0]).includes('data-fengyu-ref'))).toBe(true)
  })

  it('press can target the active page without a selector', async () => {
    execJs.mockResolvedValue('Active page')
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_press', { key: 'Escape' })
    expect(r.success).toBe(true)
    expect(r.summary).toContain('active page')
    expect(cdpCalls.filter((c) => c.method === 'Input.dispatchMouseEvent')).toHaveLength(0)
    expect(cdpCalls.filter((c) => c.method === 'Input.dispatchKeyEvent')).toHaveLength(2)
  })

  it('select chooses an exact native option and dispatches input/change', async () => {
    execJs
      .mockResolvedValueOnce({ x: 100, y: 140 })
      .mockResolvedValueOnce({ value: 'zh-CN', label: '简体中文', index: 2 })
      .mockResolvedValueOnce('Settings')
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_select', { ref: 'language', option: '简体中文' })
    expect(r.success).toBe(true)
    expect(r.value).toBe('zh-CN')
    expect(r.label).toBe('简体中文')
    const selectJs = String(execJs.mock.calls[1][0])
    expect(selectJs).toContain('HTMLSelectElement')
    expect(selectJs).toContain("new Event('input'")
    expect(selectJs).toContain("new Event('change'")
  })

  it('press rejects unknown keys before dispatching keyboard input', async () => {
    execJs.mockResolvedValueOnce({ x: 100, y: 120 }).mockResolvedValueOnce(true)
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_press', { selector: '#q', key: 'LaunchRocket' })
    expect(r.success).toBe(false)
    expect(r.summary).toContain('unsupported key')
    expect(cdpCalls.filter((c) => c.method === 'Input.dispatchKeyEvent')).toHaveLength(0)
  })

  it('wait_for reports timeout as a failed operation', async () => {
    execJs.mockResolvedValue(false)
    vi.useFakeTimers()
    try {
      const s = new BrowserSession()
      s.ensureWindow()
      const result = handleBrowserOp(s, 'browser_wait_for', { selector: '#late', timeout: 1 })
      await vi.advanceTimersByTimeAsync(1_100)
      const r = await result
      expect(r.success).toBe(false)
      expect(r.ok).toBe(false)
      expect(r.summary).toBe('wait timed out')
      expect(String(execJs.mock.calls[0][0])).toContain('getBoundingClientRect')
      expect(String(execJs.mock.calls[0][0])).not.toContain('offsetParent')
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('BrowserSession automation-window guards', () => {
  beforeEach(() => {
    openHandler = null
    for (const k of Object.keys(wcEvents)) delete wcEvents[k]
    shellOpenExternal.mockClear()
  })

  it('registers an open handler and a will-navigate guard on ensureWindow', () => {
    const s = new BrowserSession()
    s.ensureWindow()
    expect(openHandler).not.toBeNull()
    expect(wcEvents['will-navigate']).toBeDefined()
  })

  it('denies window.open and delegates http(s) to the system browser', () => {
    const s = new BrowserSession()
    s.ensureWindow()
    expect(openHandler!({ url: 'https://example.com/ads' })).toEqual({ action: 'deny' })
    expect(shellOpenExternal).toHaveBeenCalledWith('https://example.com/ads')
  })

  it('denies window.open for non-http(s) without shell.openExternal', () => {
    const s = new BrowserSession()
    s.ensureWindow()
    expect(openHandler!({ url: 'file:///etc/passwd' })).toEqual({ action: 'deny' })
    expect(shellOpenExternal).not.toHaveBeenCalled()
  })

  it('blocks will-navigate escapes from the web but allows cross-origin http(s)', () => {
    const s = new BrowserSession()
    s.ensureWindow()
    const blocked = vi.fn()
    wcEvents['will-navigate']!({ preventDefault: blocked }, 'file:///etc/passwd')
    expect(blocked).toHaveBeenCalledTimes(1)
    // Cross-origin browsing is this window's purpose — only non-web schemes are stopped.
    const allowed = vi.fn()
    wcEvents['will-navigate']!({ preventDefault: allowed }, 'https://accounts.example.com/login')
    expect(allowed).not.toHaveBeenCalled()
  })
})
