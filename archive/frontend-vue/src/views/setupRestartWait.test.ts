import { describe, expect, it } from 'vitest'
import { waitForAppMode } from './setupRestartWait'

/** Axios-shaped rejection: axios marks its errors with isAxiosError; the poll loop
 * only inspects that flag plus response.status, so a plain object reproduces it. */
function httpError(status: number): Error {
  const err = new Error(`Request failed with status code ${status}`) as Error & {
    isAxiosError: boolean
    response: { status: number }
  }
  err.isAxiosError = true
  err.response = { status }
  return err
}

/** Deterministic clock: each sleep(500) advances virtual time so the deadline logic
 * runs without real timers. */
function fakeClock() {
  let t = 0
  return {
    now: () => t,
    sleep: async (ms: number) => {
      t += ms
    },
  }
}

describe('setup wizard restart wait', () => {
  it('resolves app once health is ok and /api/setup/status 404s', async () => {
    const clock = fakeClock()
    const result = await waitForAppMode(
      {
        health: async () => ({ status: 'ok' }),
        setupStatus: async () => {
          throw httpError(404)
        },
        ...clock,
      },
      30_000,
    )
    expect(result).toBe('app')
  })

  it('keeps waiting through the SETUP backend 1s grace period (200 initialized:true)', async () => {
    const clock = fakeClock()
    let calls = 0
    const result = await waitForAppMode(
      {
        health: async () => ({ status: 'ok' }),
        setupStatus: async () => {
          calls++
          if (calls < 3) return { initialized: true } // still-exiting SETUP backend
          throw httpError(404) // restarted APP backend
        },
        ...clock,
      },
      30_000,
    )
    expect(result).toBe('app')
    expect(calls).toBe(3)
  })

  it('keeps polling while health reports a non-ok status', async () => {
    const clock = fakeClock()
    let calls = 0
    const result = await waitForAppMode(
      {
        health: async () => {
          calls++
          return calls < 3 ? { status: 'starting' } : { status: 'ok' }
        },
        setupStatus: async () => {
          throw httpError(404)
        },
        ...clock,
      },
      30_000,
    )
    expect(result).toBe('app')
  })

  it('treats non-404 failures as still-down and keeps polling', async () => {
    const clock = fakeClock()
    let calls = 0
    const result = await waitForAppMode(
      {
        health: async () => ({ status: 'ok' }),
        setupStatus: async () => {
          calls++
          throw calls < 2 ? new Error('network error') : httpError(500)
        },
        ...clock,
      },
      4_000,
    )
    expect(result).toBe('timeout')
    expect(calls).toBeGreaterThanOrEqual(2)
  })

  it('times out when APP mode is never confirmed', async () => {
    const clock = fakeClock()
    let polls = 0
    const result = await waitForAppMode(
      {
        health: async () => {
          polls++
          throw new Error('connect ECONNREFUSED')
        },
        setupStatus: async () => {
          throw new Error('connect ECONNREFUSED')
        },
        ...clock,
      },
      5_000,
    )
    expect(result).toBe('timeout')
    // 500 ms interval over a 5 s budget — the loop kept its promise to keep trying.
    expect(polls).toBe(10)
  })
})
