import { spawnBackend } from './spawn'
import { pollHealth } from '../util/health'
import { detectSetupMode } from './handshake'
import type { RuntimeLayout } from './runtime-layout'
import type { AotJvmArgs } from './aot-cache'
import type { BackendChild } from './supervisor'
import type { BootStage } from '../ipc/boot'

export interface StartedBackend {
  child: BackendChild
  port: number
  setupMode: boolean
}

export interface StartBackendOptions {
  layout: RuntimeLayout
  token: string
  requestedPort: number
  /** Startup-cache overlay forwarded to spawnBackend (see aot-cache.ts). Optional. */
  aot?: AotJvmArgs
  shouldCancel?: () => boolean
  fetchImpl?: typeof fetch
  onBackendLine?: (line: string) => void
  /** Backend stderr, for the child's whole lifetime (crash/warning output). Optional. */
  onBackendErrLine?: (line: string) => void
  /** Forwarded to spawn (port-ready) and health (health-ready). Optional. */
  onProgress?: (stage: BootStage) => void
  /**
   * Called with the child as soon as it is spawned (before the health wait), so a
   * caller can wire its own exit race around the wait — a backend that dies mid-wait
   * must fail fast instead of parking behind the 120s health deadline. Optional.
   */
  onSpawn?: (child: BackendChild) => void
  /**
   * Forwarded to spawnBackend: called with the wrapped child immediately after the JVM
   * process exists, so a caller can install its global quit-time teardown handle before
   * any startup stage can fail and kill the child internally. Optional.
   */
  onChildSpawned?: (child: BackendChild) => void
  /** Diagnostic sink for the setup-probe retry warning (defaults to console.warn). Optional. */
  log?: (message: string) => void
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
    aot: opts.aot,
    shouldCancel: opts.shouldCancel,
    onLine: opts.onBackendLine,
    onErrLine: opts.onBackendErrLine,
    onProgress: opts.onProgress,
    onChildSpawned: opts.onChildSpawned,
  })
  opts.onSpawn?.(child)

  try {
    // No token on the health probe: /api/health is token-bypassed (see util/health.ts).
    await pollHealth({
      port,
      shouldCancel: opts.shouldCancel,
      fetchImpl: opts.fetchImpl,
      onProgress: opts.onProgress,
    })
  } catch (err) {
    child.kill()
    throw err
  }

  const setupMode = await probeSetupMode(port, token, opts.fetchImpl, opts.shouldCancel, opts.log).catch((err) => {
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
 *
 * Exported because the first boot runs it AFTER creating the main window (the SPA load
 * overlaps the JVM boot; see main.ts) — the setup-restart path still uses startBackend.
 *
 * `log` is the diagnostic sink for the retry warning. It defaults to console.warn (lost in a
 * packaged GUI launch, where no console is attached); main.ts passes the electron-log-backed
 * logger.warn so the retry trace lands in desktop.log.
 */
export async function probeSetupMode(
  port: number,
  token: string,
  fetchImpl: typeof fetch = fetch,
  shouldCancel?: () => boolean,
  log: (message: string) => void = console.warn,
): Promise<boolean> {
  try {
    return await checkSetupMode(port, token, fetchImpl)
  } catch (err) {
    if (shouldCancel?.()) throw err
    log(
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
