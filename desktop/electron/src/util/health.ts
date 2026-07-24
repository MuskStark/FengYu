/**
 * Poll GET /api/health until 200 or the deadline.
 *
 * Timing mirrors Rust `wait_for_health`: 30s overall, 300ms interval, 2s per-request,
 * HTTP 200 = ready. Cancellable.
 */

export interface PollHealthOptions {
  port: number
  token: string
  fetchImpl?: typeof fetch
  /** Default: setTimeout-based. */
  sleep?: (ms: number) => Promise<void>
  shouldCancel?: () => boolean
  deadlineMs?: number
  intervalMs?: number
  requestTimeoutMs?: number
}

const defaultSleep = (ms: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, ms))

export async function pollHealth(opts: PollHealthOptions): Promise<void> {
  const {
    port,
    token,
    fetchImpl = fetch,
    sleep = defaultSleep,
    shouldCancel = () => false,
    deadlineMs = 30_000,
    intervalMs = 300,
    requestTimeoutMs = 2_000,
  } = opts

  const url = `http://127.0.0.1:${port}/api/health`
  const deadline = Date.now() + deadlineMs
  while (Date.now() < deadline) {
    if (shouldCancel()) throw new Error('backend health check cancelled')
    try {
      const controller = new AbortController()
      const timer = setTimeout(() => controller.abort(), requestTimeoutMs)
      const resp = await fetchImpl(url, {
        headers: { 'X-FengYu-Token': token },
        signal: controller.signal,
      })
      clearTimeout(timer)
      if (resp.status === 200) return
    } catch {
      // network error / abort → keep polling until deadline
    }
    await sleep(intervalMs)
  }
  if (shouldCancel()) throw new Error('backend health check cancelled')
  throw new Error('backend health check timed out')
}
