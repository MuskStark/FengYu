import { describe, it, expect, vi } from 'vitest'
import { pollHealth } from '../src/util/health'

describe('pollHealth', () => {
  it('returns ok on HTTP 200', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    await expect(
      pollHealth({
        port: 24056,
        token: 't',
        fetchImpl: fetchImpl as unknown as typeof fetch,
      }),
    ).resolves.toBeUndefined()
    expect(fetchImpl).toHaveBeenCalledOnce()
  })

  it('retries on non-200 then succeeds', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 503 })
      .mockResolvedValueOnce({ ok: true, status: 200 })
    const sleep = vi.fn().mockResolvedValue(undefined)
    await pollHealth({
      port: 24056,
      token: 't',
      fetchImpl: fetchImpl as unknown as typeof fetch,
      sleep,
      intervalMs: 0,
    })
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })

  it('uses the full external backend base URL', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    await pollHealth({
      baseUrl: 'https://localhost:24443/',
      token: 't',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    })
    expect(fetchImpl).toHaveBeenCalledWith(
      'https://localhost:24443/api/health',
      expect.any(Object),
    )
  })

  it('throws on timeout', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: false, status: 503 })
    const sleep = vi.fn().mockResolvedValue(undefined)
    await expect(
      pollHealth({
        port: 24056,
        token: 't',
        fetchImpl: fetchImpl as unknown as typeof fetch,
        sleep,
        intervalMs: 0,
        deadlineMs: 0, // immediate deadline
      }),
    ).rejects.toThrow(/timed out/)
  })

  it('aborts when shouldCancel returns true', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: false, status: 503 })
    await expect(
      pollHealth({
        port: 24056,
        token: 't',
        fetchImpl: fetchImpl as unknown as typeof fetch,
        shouldCancel: () => true,
      }),
    ).rejects.toThrow(/cancel/)
  })
})
