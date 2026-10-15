import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Mock } from 'vitest'
import { http } from './impl/http'

vi.mock('./impl/http', () => ({ http: { post: vi.fn() } }))
vi.mock('@/platform', () => ({ getPlatform: () => ({ apiBase: () => '' }) }))

/** Minimal EventSource stand-in: records every instance so tests can fire lifecycle
 *  events (error/open) and assert on connects, closes, and URLs. */
class FakeEventSource {
  static instances: FakeEventSource[] = []
  readonly url: string
  onerror: (() => void) | null = null
  closed = false
  private readonly handlers = new Map<string, Array<(event: unknown) => void>>()

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, handler: (event: unknown) => void): void {
    this.handlers.set(type, [...(this.handlers.get(type) ?? []), handler])
  }

  emit(type: string, event?: unknown): void {
    for (const handler of this.handlers.get(type) ?? []) handler(event)
  }

  close(): void {
    this.closed = true
  }
}

const post = http.post as unknown as Mock

/** A fresh singleton per test — module state (source, backoff) must not leak between cases. */
async function freshClient() {
  vi.resetModules()
  FakeEventSource.instances = []
  const { mcpAppsEvents } = await import('./mcpAppsEvents')
  return mcpAppsEvents
}

/** Drops the most recent source, the way a backend restart or network blip would. */
function dropLiveSource(): void {
  FakeEventSource.instances[FakeEventSource.instances.length - 1].onerror?.()
}

describe('mcpAppsEvents self-heal (reconnect with capped backoff)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('EventSource', FakeEventSource)
    vi.stubGlobal('window', {
      setTimeout: (fn: () => void, ms?: number) => setTimeout(fn, ms),
      clearTimeout: (id: ReturnType<typeof setTimeout>) => clearTimeout(id),
    })
    post.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('reconnects with a fresh ticket after a transport drop, keeping listeners fed', async () => {
    const client = await freshClient()
    post.mockResolvedValueOnce({ data: { ticket: 't1' } }).mockResolvedValue({ data: { ticket: 't2' } })
    const seen: Array<{ serverId: string; uri: string }> = []
    await client.acquire((event) => seen.push(event))
    expect(FakeEventSource.instances).toHaveLength(1)

    dropLiveSource()
    expect(FakeEventSource.instances[0].closed).toBe(true)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(FakeEventSource.instances[1].url).toContain('ticket=t2')

    FakeEventSource.instances[1].emit('resource-updated', {
      data: JSON.stringify({ serverId: 'srv', uri: 'file:///x' }),
    })
    expect(seen).toEqual([{ serverId: 'srv', uri: 'file:///x' }])
  })

  it('doubles the retry delay per failure up to the 15s cap, resetting once a connection opens', async () => {
    const client = await freshClient()
    post.mockResolvedValue({ data: { ticket: 't' } })
    await client.acquire(() => {})

    // 1s, 2s, 4s, 8s …
    dropLiveSource()
    await vi.advanceTimersByTimeAsync(999)
    expect(FakeEventSource.instances).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    dropLiveSource()
    await vi.advanceTimersByTimeAsync(1_999)
    expect(FakeEventSource.instances).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(1)
    dropLiveSource()
    await vi.advanceTimersByTimeAsync(4_000)
    dropLiveSource()
    await vi.advanceTimersByTimeAsync(8_000)
    expect(FakeEventSource.instances).toHaveLength(5)
    // … then capped at 15s.
    dropLiveSource()
    await vi.advanceTimersByTimeAsync(14_999)
    expect(FakeEventSource.instances).toHaveLength(5)
    await vi.advanceTimersByTimeAsync(1)
    expect(FakeEventSource.instances).toHaveLength(6)

    // A successful (re)connection restarts the cadence at 1s.
    FakeEventSource.instances[5].emit('open')
    dropLiveSource()
    await vi.advanceTimersByTimeAsync(1_000)
    expect(FakeEventSource.instances).toHaveLength(7)
  })

  it('retries a failed ticket mint instead of stranding registered listeners', async () => {
    const client = await freshClient()
    post.mockRejectedValueOnce(new Error('backend asleep')).mockResolvedValue({ data: { ticket: 't1' } })
    await expect(client.acquire(() => {})).resolves.toBeUndefined()
    expect(FakeEventSource.instances).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('ignores an error from a superseded source — no duplicate reconnect, live source untouched', async () => {
    const client = await freshClient()
    post.mockResolvedValue({ data: { ticket: 't1' } })
    const seen: string[] = []
    await client.acquire((event) => seen.push(event.uri))
    const first = FakeEventSource.instances[0]
    first.onerror?.()
    await vi.advanceTimersByTimeAsync(1_000)
    const second = FakeEventSource.instances[1]

    // A late duplicate error from the dead first source must neither mint again…
    first.onerror?.()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(FakeEventSource.instances).toHaveLength(2)
    // …nor detach the live successor.
    second.emit('resource-updated', { data: JSON.stringify({ serverId: 'srv', uri: 'still-live' }) })
    expect(seen).toEqual(['still-live'])
  })

  it('a re-acquire during the backoff window connects immediately; the pending timer becomes a no-op', async () => {
    const client = await freshClient()
    post.mockResolvedValue({ data: { ticket: 't' } })
    await client.acquire(() => {})
    dropLiveSource()
    await client.acquire(() => {})
    expect(FakeEventSource.instances).toHaveLength(2)
    await vi.runAllTimersAsync()
    expect(FakeEventSource.instances).toHaveLength(2)
  })

  it('stops reconnecting when the last listener leaves, and closes the live source', async () => {
    const client = await freshClient()
    post.mockResolvedValue({ data: { ticket: 't1' } })
    const listener = (): void => {}
    await client.acquire(listener)
    dropLiveSource()
    client.release(listener)
    expect(client.listenerCount()).toBe(0)
    await vi.runAllTimersAsync()
    // No reconnection attempt was scheduled or fired.
    expect(FakeEventSource.instances).toHaveLength(1)

    // With the stream still live, the last release closes it outright.
    await client.acquire(listener)
    expect(FakeEventSource.instances[1].closed).toBe(false)
    client.release(listener)
    expect(FakeEventSource.instances[1].closed).toBe(true)
  })
})
