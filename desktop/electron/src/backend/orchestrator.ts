import { spawnBackend } from './spawn'
import { pollHealth } from '../util/health'
import { detectSetupMode } from './handshake'
import type { RuntimeLayout } from './runtime-layout'
import type { BackendChild } from './supervisor'
import type { SplashStage } from '../window/splash-i18n'

export interface StartedBackend {
  child: BackendChild
  port: number
  setupMode: boolean
}

export interface StartBackendOptions {
  layout: RuntimeLayout
  token: string
  requestedPort: number
  shouldCancel?: () => boolean
  fetchImpl?: typeof fetch
  onBackendLine?: (line: string) => void
  /** Forwarded to spawn (port-ready) and health (health-ready). Optional. */
  onProgress?: (stage: SplashStage) => void
}

/**
 * Spawn the backend, wait for /api/health, probe SETUP mode.
 * Mirrors Rust `start_backend`. Any failure terminates the child and throws.
 */
export async function startBackend(opts: StartBackendOptions): Promise<StartedBackend> {
  const { layout, token, requestedPort } = opts
  const { child, port } = await spawnBackend({
    layout,
    token,
    requestedPort,
    shouldCancel: opts.shouldCancel,
    onLine: opts.onBackendLine,
    onProgress: opts.onProgress,
  })

  try {
    await pollHealth({
      port,
      token,
      shouldCancel: opts.shouldCancel,
      fetchImpl: opts.fetchImpl,
      onProgress: opts.onProgress,
    })
  } catch (err) {
    child.kill()
    throw err
  }

  const setupMode = await probeSetupMode(port, token, opts.fetchImpl, opts.shouldCancel).catch((err) => {
    child.kill()
    throw err
  })

  if (opts.shouldCancel?.()) {
    child.kill()
    throw new Error('backend startup cancelled')
  }

  return { child, port, setupMode }
}

/**
 * Probe SETUP mode with one retry. By the time this runs the backend has already been answering
 * /api/health for a while, so a merely *slow* first /api/setup/status response (GC pause, lazy
 * handler init) must never get a healthy backend killed: each attempt gets a generous 10s timeout
 * and a failure is retried once before startBackend treats the probe as genuinely broken.
 *
 * A 404 is NOT a failure: /api/setup/** is token-bypassed and therefore only mapped in the
 * SETUP-mode context (FengYuApplication excludes SetupController). A backend that just passed
 * the health probe on the same port+token yet 404s here is an already-configured APP-mode
 * backend — definitive, no retry. Mirrors the SPA router guard's 404 handling.
 */
async function probeSetupMode(
  port: number,
  token: string,
  fetchImpl: typeof fetch = fetch,
  shouldCancel?: () => boolean,
): Promise<boolean> {
  try {
    return await checkSetupMode(port, token, fetchImpl)
  } catch (err) {
    if (shouldCancel?.()) throw err
    console.warn(
      `[desktop] setup status probe failed (${err instanceof Error ? err.message : String(err)}); retrying once`,
    )
    return await checkSetupMode(port, token, fetchImpl)
  }
}

async function checkSetupMode(
  port: number,
  token: string,
  fetchImpl: typeof fetch = fetch,
): Promise<boolean> {
  const url = `http://127.0.0.1:${port}/api/setup/status`
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 10_000)
  try {
    const resp = await fetchImpl(url, {
      headers: { 'X-FengYu-Token': token },
      signal: controller.signal,
    })
    if (resp.status === 404) {
      // APP mode does not serve /api/setup/** — see probeSetupMode. Already configured.
      return false
    }
    if (!resp.ok) {
      throw new Error(`setup status request failed: HTTP ${resp.status}`)
    }
    const body = await resp.text()
    return detectSetupMode(body)
  } finally {
    clearTimeout(timer)
  }
}
