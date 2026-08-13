import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createServer } from 'node:http'

/** Grab an OS-assigned free port then release it, so the next bind can pin it. */
async function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const s = createServer()
    s.on('error', reject)
    s.listen(0, '127.0.0.1', () => {
      const addr = s.address()
      const port = addr && typeof addr === 'object' ? addr.port : 0
      s.close(() => resolve(port))
    })
  })
}

// We test the bridge as a real HTTP round-trip: start the listener, then fetch.
const handleBrowserOp = vi.fn()
vi.mock('../src/browser/handlers', () => ({ handleBrowserOp: (...a: unknown[]) => handleBrowserOp(...a) }))
vi.mock('../src/browser/session', () => ({ BrowserSession: class {
  ensureWindow() { return {} }
  describe() { return { open: true, url: '', title: '' } }
  close() {}
} }))

const { startBrowserBridge } = await import('../src/browser/bridge')

describe('startBrowserBridge', () => {
  let bridge: { port: number; token: string; close: () => void } | null = null
  beforeEach(() => { handleBrowserOp.mockReset() })
  afterEach(() => { bridge?.close(); bridge = null })

  it('rejects requests with no/wrong token', async () => {
    bridge = await startBrowserBridge({} as never)
    const res = await fetch(`http://127.0.0.1:${bridge.port}/invoke`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ method: 'browser_close', params: {} }),
    })
    expect(res.status).toBe(401)
  })

  it('routes invoke to handleBrowserOp and returns the envelope', async () => {
    bridge = await startBrowserBridge({} as never)
    handleBrowserOp.mockResolvedValue({ success: true, summary: 'ok', closed: true })
    const res = await fetch(`http://127.0.0.1:${bridge.port}/invoke`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Browser-Token': bridge.token },
      body: JSON.stringify({ method: 'browser_close', params: {} }),
    })
    expect(res.status).toBe(200)
    const json = await res.json()
    expect(json.success).toBe(true)
    expect(handleBrowserOp).toHaveBeenCalledTimes(1)
  })

  it('serializes overlapping browser operations', async () => {
    bridge = await startBrowserBridge({} as never)
    let releaseFirst!: () => void
    const firstBlocked = new Promise<void>((resolve) => { releaseFirst = resolve })
    const order: string[] = []
    handleBrowserOp.mockImplementation(async (_session, method: string) => {
      order.push(`start:${method}`)
      if (method === 'first') await firstBlocked
      order.push(`end:${method}`)
      return { success: true, summary: method }
    })
    const invoke = (method: string) => fetch(`http://127.0.0.1:${bridge!.port}/invoke`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Browser-Token': bridge!.token },
      body: JSON.stringify({ method, params: {} }),
    })
    const first = invoke('first')
    await vi.waitFor(() => expect(order).toEqual(['start:first']))
    const second = invoke('second')
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(order).toEqual(['start:first'])
    releaseFirst()
    await Promise.all([first, second])
    expect(order).toEqual(['start:first', 'end:first', 'start:second', 'end:second'])
  })

  it('rejects non-POST / non-/invoke paths', async () => {
    bridge = await startBrowserBridge({} as never)
    const res = await fetch(`http://127.0.0.1:${bridge.port}/foo`, {
      headers: { 'X-Browser-Token': bridge.token },
    })
    expect(res.status).toBe(404)
  })

  it('honours a pinned port and token from opts', async () => {
    const port = await freePort()
    const token = 'zf-fixed-dev-token'
    bridge = await startBrowserBridge({} as never, { port, token })
    // Effective address is exactly the pinned one (used by the dev connect-mode flow so the
    // IDE-launched JVM can be told where to call back).
    expect(bridge.port).toBe(port)
    expect(bridge.token).toBe(token)
    // The pinned token authenticates; a wrong one is rejected.
    const ok = await fetch(`http://127.0.0.1:${bridge.port}/invoke`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Browser-Token': token },
      body: JSON.stringify({ method: 'browser_close', params: {} }),
    })
    expect(ok.status).toBe(200)
    const bad = await fetch(`http://127.0.0.1:${bridge.port}/invoke`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Browser-Token': 'wrong' },
      body: JSON.stringify({ method: 'browser_close', params: {} }),
    })
    expect(bad.status).toBe(401)
  })

  it('defaults to OS-assigned port and generated token when opts omitted', async () => {
    bridge = await startBrowserBridge({} as never)
    // Regression guard: no opts → keeps the original random-address behaviour.
    expect(bridge.port).toBeGreaterThan(0)
    expect(bridge.token).toMatch(/^zf-[0-9a-f]{64}$/)
  })

  it('creates and selects independently routed tabs', async () => {
    bridge = await startBrowserBridge({ close: () => undefined } as never)
    handleBrowserOp.mockResolvedValue({ success: true, summary: 'navigated', url: 'https://example.com', title: 'Example' })
    const invoke = (method: string, params: Record<string, unknown>) => fetch(`http://127.0.0.1:${bridge!.port}/invoke`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Browser-Token': bridge!.token },
      body: JSON.stringify({ method, params: { _sessionId: 'java-session', ...params } }),
    }).then((res) => res.json())

    const opened = await invoke('browser_new_tab', { url: 'https://example.com' })
    expect(opened.success).toBe(true)
    expect(opened.sessionId).toBe('java-session')
    expect(opened.contextId).toBe('default')
    expect(opened.tabId).toMatch(/^tab_/)

    const selected = await invoke('browser_select_tab', { tabId: opened.tabId })
    expect(selected.success).toBe(true)
    expect(selected.tabId).toBe(opened.tabId)
  })

  it('creates contexts that remain isolated from the default context', async () => {
    bridge = await startBrowserBridge({ close: () => undefined } as never)
    const invoke = (method: string, params: Record<string, unknown> = {}) => fetch(`http://127.0.0.1:${bridge!.port}/invoke`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Browser-Token': bridge!.token },
      body: JSON.stringify({ method, params: { _sessionId: 'context-session', ...params } }),
    }).then((res) => res.json())

    const created = await invoke('browser_new_context')
    expect(created.success).toBe(true)
    expect(created.contextId).toMatch(/^context_/)
    const listed = await invoke('browser_contexts', { _contextId: created.contextId })
    expect(listed.contexts).toHaveLength(2)
    expect(listed.contexts).toEqual(expect.arrayContaining([
      expect.objectContaining({ contextId: 'default' }),
      expect.objectContaining({ contextId: created.contextId, active: true }),
    ]))
  })
})
