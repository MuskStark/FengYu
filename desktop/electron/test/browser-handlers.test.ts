import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub electron with a mockable webContents/debugger. The session module imports
// { BrowserWindow } from 'electron', so we mock it before importing SUT.
const execJs = vi.fn()
const loadURL = vi.fn()
const capturePage = vi.fn()
const attach = vi.fn()
const sendCommand = vi.fn()
const detach = vi.fn()

vi.mock('electron', () => ({
  BrowserWindow: vi.fn().mockImplementation(() => ({
    isDestroyed: () => false,
    destroy: vi.fn(),
    webContents: {
      loadURL,
      executeJavaScript: execJs,
      capturePage,
      debugger: { attach, sendCommand, detach, isAttached: () => false },
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
    attach.mockReset(); sendCommand.mockReset(); detach.mockReset()
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
    // Empty a11y tree from CDP.
    sendCommand.mockResolvedValue({ nodes: [] })
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_screenshot', {})
    expect(r.success).toBe(true)
    expect(String(r.imagePath)).toMatch(/\.png$/)
  })
})
