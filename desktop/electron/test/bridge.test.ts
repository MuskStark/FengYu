import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// We test the bridge as a real HTTP round-trip: start the listener, then fetch.
const handleBrowserOp = vi.fn()
vi.mock('../src/browser/handlers', () => ({ handleBrowserOp: (...a: unknown[]) => handleBrowserOp(...a) }))
vi.mock('../src/browser/session', () => ({ BrowserSession: class {} }))

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

  it('rejects non-POST / non-/invoke paths', async () => {
    bridge = await startBrowserBridge({} as never)
    const res = await fetch(`http://127.0.0.1:${bridge.port}/foo`, {
      headers: { 'X-Browser-Token': bridge.token },
    })
    expect(res.status).toBe(404)
  })
})
