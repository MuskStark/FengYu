import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { RuntimeLayout } from '../src/backend/runtime-layout'
import type { BackendChild } from '../src/backend/supervisor'

/**
 * Unit tests for the backend startup flow (`src/backend/orchestrator.ts`), focused on the
 * SETUP-mode probe contract: by the time the probe runs, the backend has already passed the
 * 30s /api/health poll, so a slow/failed /api/setup/status response must not get a healthy
 * backend killed — the probe gets a generous timeout and one retry before giving up.
 */

const { mockSpawnBackend, mockPollHealth } = vi.hoisted(() => ({
  mockSpawnBackend: vi.fn(),
  mockPollHealth: vi.fn(),
}))

vi.mock('../src/backend/spawn', () => ({ spawnBackend: mockSpawnBackend }))
vi.mock('../src/util/health', () => ({ pollHealth: mockPollHealth }))

const FAKE_LAYOUT: RuntimeLayout = { jar: '/fake/FengYu.jar', plugins: '/fake/plugins' }

let spawnedChild: BackendChild

function fakeChild(): BackendChild {
  return { process: {} as BackendChild['process'], kill: vi.fn(), forceKill: vi.fn() }
}

const okSetupStatus = (initialized: boolean) => ({
  ok: true,
  text: async () => JSON.stringify({ initialized }),
})

beforeEach(() => {
  vi.clearAllMocks()
  mockPollHealth.mockResolvedValue(undefined) // health already passed
  spawnedChild = fakeChild()
  mockSpawnBackend.mockImplementation(async () => ({ child: spawnedChild, port: 24056 }))
  vi.spyOn(console, 'warn').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('startBackend setup-mode probe', () => {
  it('resolves with setupMode=false when the backend reports initialized', async () => {
    const { startBackend } = await import('../src/backend/orchestrator')
    const fetchImpl = vi.fn(async () => okSetupStatus(true)) as unknown as typeof fetch

    const started = await startBackend({ layout: FAKE_LAYOUT, token: 't', requestedPort: 24056, fetchImpl })

    expect(started.setupMode).toBe(false)
    expect(started.child.kill).not.toHaveBeenCalled()
  })

  it('resolves with setupMode=true for a first-launch backend', async () => {
    const { startBackend } = await import('../src/backend/orchestrator')
    const fetchImpl = vi.fn(async () => okSetupStatus(false)) as unknown as typeof fetch

    const started = await startBackend({ layout: FAKE_LAYOUT, token: 't', requestedPort: 24056, fetchImpl })

    expect(started.setupMode).toBe(true)
    expect(started.child.kill).not.toHaveBeenCalled()
  })

  it('treats 404 as an already-configured APP-mode backend (definitive: no retry, no kill)', async () => {
    // APP mode intentionally does not map /api/setup/** (token-bypassed wizard surface), so a
    // healthy backend answering 404 there IS the "configured" answer — same contract as the
    // SPA router guard. It must not be retried (it can never turn into a 200) nor kill the child.
    const { startBackend } = await import('../src/backend/orchestrator')
    const fetchImpl = vi.fn(async () => ({ ok: false, status: 404 })) as unknown as typeof fetch

    const started = await startBackend({ layout: FAKE_LAYOUT, token: 't', requestedPort: 24056, fetchImpl })

    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(started.setupMode).toBe(false)
    expect(started.child.kill).not.toHaveBeenCalled()
  })

  it('retries a failed probe instead of killing the healthy backend', async () => {
    const { startBackend } = await import('../src/backend/orchestrator')
    const fetchImpl = vi
      .fn()
      .mockRejectedValueOnce(new Error('first request dropped'))
      .mockResolvedValueOnce(okSetupStatus(true)) as unknown as typeof fetch

    const started = await startBackend({ layout: FAKE_LAYOUT, token: 't', requestedPort: 24056, fetchImpl })

    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect(started.setupMode).toBe(false)
    expect(started.child.kill).not.toHaveBeenCalled()
  })

  it('kills the backend only after the retry also fails', async () => {
    const { startBackend } = await import('../src/backend/orchestrator')
    const fetchImpl = vi.fn(async () => {
      throw new Error('probe broken')
    }) as unknown as typeof fetch

    await expect(
      startBackend({ layout: FAKE_LAYOUT, token: 't', requestedPort: 24056, fetchImpl }),
    ).rejects.toThrow('probe broken')
    expect(spawnedChild.kill).toHaveBeenCalled()
  })

  it('treats a hung probe as slow, not broken: abort at 10s, retry, then succeed', async () => {
    vi.useFakeTimers()
    const { startBackend } = await import('../src/backend/orchestrator')
    // First call hangs until its AbortSignal fires (the 10s per-attempt timeout); the retry answers.
    const fetchImpl = vi.fn((input: unknown, init?: { signal?: AbortSignal }) => {
      const signal = init?.signal
      if (fetchImpl.mock.calls.length === 1) {
        return new Promise((_resolve, reject) => {
          signal?.addEventListener('abort', () => reject(new Error('aborted')))
        }) as unknown as Promise<Response>
      }
      return Promise.resolve(okSetupStatus(true)) as unknown as Promise<Response>
    }) as unknown as typeof fetch

    const pending = startBackend({ layout: FAKE_LAYOUT, token: 't', requestedPort: 24056, fetchImpl })
    // Advance exactly one probe timeout: the first attempt aborts, the retry then succeeds.
    await vi.advanceTimersByTimeAsync(10_000)

    const started = await pending
    expect(started.setupMode).toBe(false)
    expect(started.child.kill).not.toHaveBeenCalled()
  })
})
