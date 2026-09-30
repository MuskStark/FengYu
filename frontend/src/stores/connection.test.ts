import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useConnectionStore } from './connection'

const mocks = vi.hoisted(() => ({
  health: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  api: {
    health: mocks.health,
  },
}))

describe('connection store — boot gate', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('resolves true on the first healthy poll without waiting', async () => {
    mocks.health.mockResolvedValue({ status: 'ok' })
    const conn = useConnectionStore()

    await expect(conn.waitForBackend(1_000, 1)).resolves.toBe(true)
    expect(conn.state).toBe('connected')
    expect(mocks.health).toHaveBeenCalledTimes(1)
  })

  it('keeps polling a booting backend and connects once it answers', async () => {
    // Desktop boot: the window exists before the JVM is up — the first probes fail.
    mocks.health
      .mockRejectedValueOnce(new Error('backend not listening'))
      .mockRejectedValueOnce(new Error('backend not listening'))
      .mockResolvedValue({ status: 'ok' })
    const conn = useConnectionStore()

    await expect(conn.waitForBackend(5_000, 1)).resolves.toBe(true)
    expect(mocks.health).toHaveBeenCalledTimes(3)
    expect(conn.state).toBe('connected')
  })

  it('treats a non-ok status as not-yet-ready and keeps polling', async () => {
    mocks.health
      .mockResolvedValueOnce({ status: 'starting' })
      .mockResolvedValue({ status: 'ok' })
    const conn = useConnectionStore()

    await expect(conn.waitForBackend(5_000, 1)).resolves.toBe(true)
    expect(mocks.health).toHaveBeenCalledTimes(2)
  })

  it('gives up after the deadline and resolves false (caller opens the shell degraded)', async () => {
    mocks.health.mockRejectedValue(new Error('backend down'))
    const conn = useConnectionStore()

    await expect(conn.waitForBackend(20, 1)).resolves.toBe(false)
    // The state stays non-connected so StatusBar can surface offline.
    expect(conn.state).not.toBe('connected')
  })
})
