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
