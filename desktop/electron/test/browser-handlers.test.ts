import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub electron with a mockable webContents/debugger. The session module imports
// { BrowserWindow } from 'electron', so we mock it before importing SUT.
//
// `sendCommand` is captured into `cdpCalls` so click/type tests can assert the exact
// CDP Input.* sequence (mouseMoved → mousePressed → mouseReleased, and insertText),
// which is the whole point of the real-input rewrite: JS-synthesised el.click() and
// el.value= assignment would leave these arrays empty.
const execJs = vi.fn()
const loadURL = vi.fn()
const capturePage = vi.fn()
const attach = vi.fn()
const detach = vi.fn()
const isAttached = vi.fn(() => false)
const cdpCalls: Array<{ method: string; params: Record<string, unknown> }> = []

vi.mock('electron', () => ({
  BrowserWindow: vi.fn().mockImplementation(() => ({
    isDestroyed: () => false,
    destroy: vi.fn(),
    webContents: {
      loadURL,
      executeJavaScript: execJs,
      capturePage,
      once: vi.fn(),
      on: vi.fn(),
      debugger: {
        attach,
        detach,
        isAttached,
        sendCommand: vi.fn(async (method: string, params: Record<string, unknown> = {}) => {
          cdpCalls.push({ method, params })
          // Accessibility.getFullAXTree is the only non-Input caller; return an empty tree.
          return { nodes: [] }
        }),
      },
      getURL: () => 'https://example.com',
      getTitle: () => 'Example',
    },
  })),
}))

// Import AFTER mocks are registered.
const { BrowserSession } = await import('../src/browser/session')
const { handleBrowserOp } = await import('../src/browser/handlers')

describe('handleBrowserOp', () => {
  beforeEach(() => {
    execJs.mockReset(); loadURL.mockReset(); capturePage.mockReset()
    attach.mockReset(); detach.mockReset(); isAttached.mockReset(); isAttached.mockReturnValue(false)
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
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_screenshot', {})
    expect(r.success).toBe(true)
    expect(String(r.imagePath)).toMatch(/\.png$/)
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
  })

  it('find fails clearly when the selector matches multiple elements without nth', async () => {
    execJs.mockResolvedValue({ error: 'selector matched 3 elements; pass nth (1-based) or refine the selector' })
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_find', { selector: 'input' })
    expect(r.success).toBe(false)
    expect(r.summary).toContain('matched 3 elements')
  })

  it('click dispatches a real CDP mouse move+press+release sequence', async () => {
    // The pre-click JS returns the element centre point.
    execJs.mockResolvedValue({ x: 150, y: 300 })
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_click', { selector: '#login' })
    expect(r.success).toBe(true)
    expect(r.clicked).toBe(true)
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

  it('type focuses, clears, and inserts text via CDP Input.insertText', async () => {
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_type', { selector: '#user', text: 'alice', clear: true })
    expect(r.success).toBe(true)
    expect(r.filled).toBe(true)
    // The insert must go through the browser's real text-edit pipeline, not value assignment.
    const insert = cdpCalls.filter((c) => c.method === 'Input.insertText')
    expect(insert).toHaveLength(1)
    expect(insert[0].params.text).toBe('alice')
    // The pre-type JS must call focus() and clear value (clear:true path).
    const jsArg = String(execJs.mock.calls[0][0])
    expect(jsArg).toContain('.focus()')
    expect(jsArg).toContain("el.value = ''")
  })

  it('type without clear omits the value-reset branch', async () => {
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_type', { selector: '#user', text: 'bob', clear: false })
    expect(r.success).toBe(true)
    const jsArg = String(execJs.mock.calls[0][0])
    expect(jsArg).toContain('.focus()')
    expect(jsArg).not.toContain("el.value = ''")
  })
})
