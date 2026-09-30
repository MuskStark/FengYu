import { describe, it, expect, vi } from 'vitest'
import { pollHealth } from '../src/util/health'

/**
 * /api/health is token-bypassed by the backend (TokenAuthFilter) and the SPA's axios client
 * attaches no X-FengYu-Token to it (frontend/src/api/client.ts) — the desktop probe sends the
 * same header-free request. (Contrast: /api/setup/status in orchestrator.ts is NOT bypassed
 * and keeps the token.)
 */
describe('pollHealth', () => {
  it('returns ok on HTTP 200', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    await expect(
      pollHealth({
        port: 24056,
        fetchImpl: fetchImpl as unknown as typeof fetch,
      }),
    ).resolves.toBeUndefined()
    expect(fetchImpl).toHaveBeenCalledOnce()
  })

  it('probes /api/health without the X-FengYu-Token header (token-bypass endpoint)', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    await pollHealth({
      port: 24056,
      fetchImpl: fetchImpl as unknown as typeof fetch,
    })
    const [url, init] = fetchImpl.mock.calls[0] as [string, RequestInit | undefined]
    expect(url).toBe('http://127.0.0.1:24056/api/health')
    const headers = (init?.headers ?? {}) as Record<string, string>
    expect(headers['X-FengYu-Token']).toBeUndefined()
    expect(Object.keys(headers)).not.toContain('X-FengYu-Token')
  })

  it('retries on non-200 then succeeds', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 503 })
      .mockResolvedValueOnce({ ok: true, status: 200 })
    const sleep = vi.fn().mockResolvedValue(undefined)
    await pollHealth({
      port: 24056,
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
        fetchImpl: fetchImpl as unknown as typeof fetch,
        shouldCancel: () => true,
      }),
    ).rejects.toThrow(/cancel/)
  })

  it('invokes onProgress with health-ready on first 200', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    const onProgress = vi.fn()
    await pollHealth({
      port: 24056,
      fetchImpl: fetchImpl as unknown as typeof fetch,
      onProgress,
    })
    expect(onProgress).toHaveBeenCalledOnce()
    expect(onProgress).toHaveBeenCalledWith('health-ready')
  })
})
