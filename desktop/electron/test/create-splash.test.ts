import { describe, it, expect, vi } from 'vitest'
// Stub electron before importing the module under test.
vi.mock('electron', () => ({
  BrowserWindow: vi.fn(),
  app: { getLocale: () => 'en-US' },
}))

import { sendProgress, destroySplash } from '../src/window/create-splash'

describe('sendProgress', () => {
  it('is a no-op when splash is null', () => {
    expect(() => sendProgress(null, 'health-ready')).not.toThrow()
  })

  it('is a no-op when splash is destroyed', () => {
    const fakeWebContents = { send: vi.fn() }
    const splash = {
      isDestroyed: () => true,
      webContents: fakeWebContents,
    }
    expect(() => sendProgress(splash as any, 'health-ready')).not.toThrow()
    expect(fakeWebContents.send).not.toHaveBeenCalled()
  })

  it('sends splash:progress with stage + ts when alive', () => {
    const fakeWebContents = { send: vi.fn() }
    const splash = {
      isDestroyed: () => false,
      webContents: fakeWebContents,
    }
    sendProgress(splash as any, 'port-ready')
    expect(fakeWebContents.send).toHaveBeenCalledWith('splash:progress', {
      stage: 'port-ready',
      ts: expect.any(Number),
    })
  })
})

describe('destroySplash', () => {
  it('is a no-op when splash is null', () => {
    expect(() => destroySplash(null)).not.toThrow()
  })

  it('is a no-op when splash is already destroyed', () => {
    const splash = { isDestroyed: () => true, destroy: vi.fn() }
    destroySplash(splash as any)
    expect(splash.destroy).not.toHaveBeenCalled()
  })

  it('calls destroy when alive', () => {
    const splash = { isDestroyed: () => false, destroy: vi.fn() }
    destroySplash(splash as any)
    expect(splash.destroy).toHaveBeenCalledOnce()
  })
})
