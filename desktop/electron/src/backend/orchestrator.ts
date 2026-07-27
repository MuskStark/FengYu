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

  const setupMode = await checkSetupMode(port, token, opts.fetchImpl).catch((err) => {
    child.kill()
    throw err
  })

  if (opts.shouldCancel?.()) {
    child.kill()
    throw new Error('backend startup cancelled')
  }

  return { child, port, setupMode }
}

async function checkSetupMode(
  port: number,
  token: string,
  fetchImpl: typeof fetch = fetch,
): Promise<boolean> {
  const url = `http://127.0.0.1:${port}/api/setup/status`
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 2_000)
  try {
    const resp = await fetchImpl(url, {
      headers: { 'X-FengYu-Token': token },
      signal: controller.signal,
    })
    if (!resp.ok) {
      throw new Error(`setup status request failed: HTTP ${resp.status}`)
    }
    const body = await resp.text()
    return detectSetupMode(body)
  } finally {
    clearTimeout(timer)
  }
}
