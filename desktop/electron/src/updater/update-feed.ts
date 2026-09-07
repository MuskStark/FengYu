import { autoUpdater } from 'electron-updater'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

interface FeedConfigurableUpdater {
  setFeedURL(options: {
    provider: 'generic'
    url: string
    channel: 'latest'
    useMultipleRangeRequest: boolean
  }): void
  disableDifferentialDownload: boolean
}

/**
 * Full update api-base validation, shared by the renderer-facing IPC boundary
 * (`update:set-api-base` in ipc/update.ts) and `updateApiBase()`: an absolute http(s) URL with
 * no embedded credentials, query parameters, or fragment. Validating at BOTH ends keeps the
 * renderer from writing a URL (e.g. `http://user:pass@proxy:8088`) that every later update
 * check would reject with a confusing error. Plain http is intentionally allowed — intranet
 * FY-Proxy feeds over HTTP are a supported deployment. Throws an Error with an actionable
 * message on violation; returns the parsed URL (callers use `.origin` for credential-free
 * logging).
 */
export function validateUpdateApiBase(raw: string): URL {
  let parsed: URL
  try {
    parsed = new URL(raw)
  } catch {
    throw new Error('update api-base must be an absolute HTTP(S) URL')
  }
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error('update api-base must use HTTP or HTTPS')
  }
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error('update api-base must not contain credentials, query parameters, or a fragment')
  }
  return parsed
}

/** Return the configured intranet proxy base, or null when the normal GitHub feed should be used. */
export function updateApiBase(): string | null {
  const raw = (process.env.FENGYU_UPDATE_API_BASE || '').trim().replace(/\/+$/, '')
  if (!raw) return null
  validateUpdateApiBase(raw)
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
 * True only for a packaged Debian installation. electron-updater writes this marker into deb
 * packages and uses the same marker to select its DebUpdater implementation.
 */
export function isDebPackage(resourcesPath = process.resourcesPath): boolean {
  if (process.platform !== 'linux' || typeof resourcesPath !== 'string') return false
  const marker = join(resourcesPath, 'package-type')
  try {
    return existsSync(marker) && readFileSync(marker, 'utf8').trim() === 'deb'
  } catch {
    return false
  }
}

/**
 * What `configureUpdateFeed` decided the updater should use (P1-3):
 *  - `default`          — no store channel configured; keep electron-updater's default
 *                         GitHub provider (app-update.yml → latest.yml / latest-mac.yml /
 *                         latest-linux.yml from the MuskStark/FengYu releases).
 *  - `store`            — the store channel serves this package; a variant-specific generic
 *                         feed was configured and `feedUrl` returned.
 *  - `github-fallback`  — the store channel is configured but does not serve this package
 *                         (macOS, Windows NSIS, AppImage, …). Falling back to the default
 *                         GitHub feed; callers should surface `notice` in their logs.
 *  - `unsupported`      — the store channel is configured AND this is a JRE-bundling build:
 *                         the store serves no JRE-specific feed, and the shared public
 *                         GitHub latest*.yml cannot distinguish lite from JRE builds, so
 *                         there is no feed that can safely update this package. Callers
 *                         must surface `reason` as an actionable error instead of silently
 *                         skipping the check.
 */
export type UpdateFeedOutcome =
  | { kind: 'default' }
  | { kind: 'store'; feedUrl: string }
  | { kind: 'github-fallback'; notice: string }
  | { kind: 'unsupported'; reason: string }

/** The GitHub releases page — manual-download target whenever no safe auto-update feed exists. */
export const GITHUB_RELEASES_URL = 'https://github.com/MuskStark/FengYu/releases'

/**
 * Decide which update feed the current package + channel combination can use, and configure
 * electron-updater when the store channel serves it. The store contract intentionally
 * supports only the lite Debian package here (Windows portable ZIP is handled by
 * portable-updater.ts and never reaches this function); every other package now FALLS BACK
 * to the default GitHub provider instead of throwing, with one exception: JRE-bundling
 * builds have no safe feed on either channel (see `UpdateFeedOutcome.unsupported`).
 * Differential download is disabled so a basic intranet HTTP deployment does not need
 * multipart byte-range or historical blockmap support.
 *
 * Can still throw for a MALFORMED configured base URL (validateUpdateApiBase) — callers
 * keep their existing catch for that case.
 */
export function configureUpdateFeed(
  updater: FeedConfigurableUpdater = autoUpdater,
  hasBundledJre?: boolean,
): UpdateFeedOutcome {
  const base = updateApiBase()
  if (!base) return { kind: 'default' }
  const bundledJre = hasBundledJre ??
    (typeof process.resourcesPath === 'string' && existsSync(join(process.resourcesPath, 'jre')))
  if (bundledJre) {
    return {
      kind: 'unsupported',
      reason:
        'The configured update channel does not serve updates for the JRE-bundled build, and the ' +
        'shared GitHub feed cannot distinguish lite from JRE builds. Update checks are disabled for ' +
        'this package; download new versions manually from the releases page.',
    }
  }
  if (!isDebPackage()) {
    return {
      kind: 'github-fallback',
      notice:
        `The configured update channel (${new URL(base).host}) does not serve updates for this ` +
        'platform/package yet; falling back to the default GitHub release feed.',
    }
  }
  const feedUrl = `${base}/fengyu-updates/deb`
  // Pin the generic provider to latest-linux.yml. Without an explicit channel,
  // electron-updater can retain a prerelease channel and request beta-linux.yml/rc-linux.yml,
  // while FY-Proxy intentionally exposes one channel-selected feed.
  updater.setFeedURL({
    provider: 'generic',
    url: feedUrl,
    channel: 'latest',
    useMultipleRangeRequest: false,
  })
  updater.disableDifferentialDownload = true
  return { kind: 'store', feedUrl }
}

export function updateDownloadPageUrl(): string {
  // The store's SPA home (/web redirects to / on the store) replaces the old
  // FY-Proxy file listing; GitHub releases remain the anonymous fallback.
  const base = updateApiBase()
  return base ? `${base}/web` : GITHUB_RELEASES_URL
}
