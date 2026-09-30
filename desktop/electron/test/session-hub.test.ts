import { describe, expect, it, vi } from 'vitest'
import { BrowserSessionHub } from '../src/browser/session-hub'

vi.mock('../src/browser/session', () => ({
  BrowserSession: class {
    ensureWindow(): void {}
    close(): void {}
    describe(): Record<string, unknown> {
      return { open: false, url: '', title: '' }
    }
  },
}))

describe('BrowserSessionHub resource limits', () => {
  it('caps contexts and tabs per logical browser session', async () => {
    const hub = new BrowserSessionHub({} as never)
    for (let i = 0; i < 15; i++) {
      expect(hub.newContext({}).success).toBe(true)
    }
    expect(() => hub.newContext({})).toThrow(/maximum number of contexts/)

    const withTabs = new BrowserSessionHub({} as never)
    for (let i = 0; i < 15; i++) {
      void withTabs.newTab({}, async () => ({ success: true, summary: 'opened' }))
    }
    // The context starts with its `main` tab, so the sixteenth creation is over budget.
    await expect(withTabs.newTab({}, async () => ({ success: true, summary: 'opened' })))
      .rejects.toThrow(/maximum number of tabs/)
    hub.closeAll()
    withTabs.closeAll()
  })

  it('caps concurrently addressable logical sessions', () => {
    const hub = new BrowserSessionHub({} as never)
    for (let i = 0; i < 31; i++) {
      hub.resolve({ _sessionId: `session-${i}` })
    }
    expect(() => hub.resolve({ _sessionId: 'one-too-many' }))
      .toThrow(/maximum number of sessions/)
    hub.closeAll()
  })
})
