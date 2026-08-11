import { autoUpdater } from 'electron-updater'
import { existsSync } from 'node:fs'
import { join } from 'node:path'

interface FeedConfigurableUpdater {
  setFeedURL(options: {
    provider: 'generic'
    url: string
    useMultipleRangeRequest: boolean
  }): void
  disableDifferentialDownload: boolean
}

/** Return the configured intranet proxy base, or null when the normal GitHub feed should be used. */
export function updateApiBase(): string | null {
  const raw = (process.env.FENGYU_UPDATE_API_BASE || '').trim().replace(/\/+$/, '')
  if (!raw) return null
  let parsed: URL
  try {
    parsed = new URL(raw)
  } catch {
    throw new Error('FENGYU_UPDATE_API_BASE must be an absolute HTTP(S) URL')
  }
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error('FENGYU_UPDATE_API_BASE must use HTTP or HTTPS')
  }
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error('FENGYU_UPDATE_API_BASE must not contain credentials, query parameters, or a fragment')
  }
  return raw
}

/**
 * Pull the persisted update-proxy URL from the backend's settings store and seed it into
 * `process.env.FENGYU_UPDATE_API_BASE` before the first update check runs. This makes a
 * Settings-UI change survive a relaunch without requiring the user to reconfigure the launcher.
 *
 * Loopback-only and runs entirely offline (no external network). Any failure — backend not yet
 * reachable, SETUP mode (no user context), malformed response, network error — is swallowed and
 * leaves the env var at whatever the launch environment provided (the GitHub default if unset).
 * Safe to call once per startup; later renderer-driven `update:set-api-base` IPC overrides it.
 *
 * @param backendApiBase the loopback origin, e.g. `http://127.0.0.1:24056`
 * @param token          the per-launch bearer token (X-FengYu-Token header)
 */
export async function bootstrapUpdateApiBaseFromBackend(
  backendApiBase: string,
  token: string,
): Promise<void> {
  const res = await fetch(`${backendApiBase}/api/settings`, {
    headers: { 'X-FengYu-Token': token },
  })
  if (!res.ok) return
  const body = (await res.json()) as { updateApiBase?: unknown }
  const value = typeof body.updateApiBase === 'string' ? body.updateApiBase.trim().replace(/\/+$/, '') : ''
  // Empty value → clear any launch-time env so the default GitHub feed is used.
  process.env.FENGYU_UPDATE_API_BASE = value
}

/**
 * Redirect electron-updater to FY-Proxy when configured. The proxy exposes separate lite/JRE
 * feeds, preventing the two variants' identically named latest*.yml files from overwriting each
 * other. Differential download is disabled for this feed so a basic intranet HTTP deployment
 * does not need multipart byte-range or historical blockmap support.
 */
export function configureUpdateFeed(
  updater: FeedConfigurableUpdater = autoUpdater,
  hasBundledJre?: boolean,
): string | null {
  const base = updateApiBase()
  if (!base) return null
  const bundledJre = hasBundledJre ??
    (typeof process.resourcesPath === 'string' && existsSync(join(process.resourcesPath, 'jre')))
  const variant = bundledJre ? 'jre' : 'lite'
  const feedUrl = `${base}/fengyu-updates/${variant}`
  updater.setFeedURL({ provider: 'generic', url: feedUrl, useMultipleRangeRequest: false })
  updater.disableDifferentialDownload = true
  return feedUrl
}

export function updateDownloadPageUrl(): string {
  const base = updateApiBase()
  return base ? `${base}/admin` : 'https://github.com/MuskStark/FengYu/releases'
}
