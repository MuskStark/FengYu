import { afterAll, describe, expect, it, vi } from 'vitest'

const captured = vi.hoisted(() => ({
  handler: null as ((request: {
    frame: { top: unknown } | null
    securityOrigin: string
  }, callback: (streams: Record<string, unknown>) => void) => void) | null,
  sources: [{ id: 'screen:0', name: 'Screen' }],
}))

vi.mock('electron', () => ({
  session: {
    defaultSession: {
      setDisplayMediaRequestHandler: vi.fn((fn: typeof captured.handler) => {
        captured.handler = fn
      }),
    },
  },
  desktopCapturer: {
    getSources: vi.fn(() => Promise.resolve(captured.sources)),
  },
}))

import { registerDisplayMediaHandler } from '../src/ipc/displayMedia'

const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
afterAll(() => warnSpy.mockRestore())

describe('getDisplayMedia gate (audit P2: no zero-interaction top-document capture)', () => {
  it('serves a nested (plugin iframe) frame the primary screen', async () => {
    registerDisplayMediaHandler()
    const shellFrame: { top: unknown } = { top: null }
    shellFrame.top = shellFrame
    const pluginFrame = { top: shellFrame }
    const callback = vi.fn()
    captured.handler!(
      { frame: pluginFrame, securityOrigin: 'http://127.0.0.1:24056' },
      callback,
    )
    await new Promise((resolve) => setImmediate(resolve))
    expect(callback).toHaveBeenCalledWith({ video: captured.sources[0] })
  })

  it('denies a request from the top-level shell document itself', () => {
    const shellFrame: { top: unknown } = { top: null }
    shellFrame.top = shellFrame
    const callback = vi.fn()
    captured.handler!({ frame: shellFrame, securityOrigin: 'app://shell' }, callback)
    expect(callback).toHaveBeenCalledWith({})
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('top-level document'))
  })

  it('denies a request whose frame already navigated away (null frame, fail-closed)', () => {
    const callback = vi.fn()
    captured.handler!({ frame: null, securityOrigin: 'http://127.0.0.1:5173' }, callback)
    expect(callback).toHaveBeenCalledWith({})
  })
})
