import { app } from 'electron'
import { spawn, spawnSync } from 'node:child_process'
import { createWriteStream, existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { tmpdir } from 'node:os'
import { dirname, join, win32 as windowsPath } from 'node:path'

const PORTABLE_MARKER = 'fengyu-portable-zip'

/**
 * Windows portable-zip self-update.
 *
 * electron-updater cannot update a portable/zip build — its NsisUpdater is hardcoded to run a
 * `-setup.exe` installer with `elevate.exe` + `app-update.yml`, none of which exist in a
 * portable zip. This module implements the full pipeline ourselves: check the latest GitHub
 * release, download the new portable zip, extract it (via the system `tar`, available on
 * Windows 10 1803+), then spawn a detached `.bat` that waits for this app to exit, robocopies
 * the new tree over the old one, and relaunches `Infinia.exe`.
 */

export interface PortableUpdateInfo {
  version: string
  zipUrl: string
  sha256: string | null
  releaseUrl: string
  releaseName: string
}

interface GitHubAsset {
  name: string
  browser_download_url: string
  digest?: string | null
}

interface GitHubRelease {
  tag_name: string
  name: string | null
  html_url: string
  assets: GitHubAsset[]
}

/**
 * Resolve the release-check URL. When FENGYU_UPDATE_API_BASE is set (intranet deployment), it
 * points at the FY-Proxy distribution center which serves a single GitHub-compatible release
 * object at /fengyu-releases/api/releases/latest. Otherwise it falls back to the GitHub API.
 * Mirrors the backend's `fengyu.updates.api-base` semantics: set → intranet only, unset → GitHub.
 */
const RELEASES_API = (repo: string) => {
  const apiBase = (process.env.FENGYU_UPDATE_API_BASE || '').replace(/\/+$/, '')
  return apiBase
    ? `${apiBase}/fengyu-releases/api/releases/latest?channel=windows-portable`
    : `https://api.github.com/repos/${repo}/releases?per_page=1`
}

/**
 * True when running inside a Windows portable zip (not an NSIS install).
 *
 * New packages carry an explicit marker written by scripts/after-pack.cjs. The uninstaller check
 * keeps already-published ZIPs working: electron-builder writes app-update.yml into the shared
 * appOutDir whenever NSIS and ZIP are built together, so absence of app-update.yml was never a
 * reliable discriminator. A real installed NSIS app has `Uninstall <exe-name>.exe` beside the app;
 * an extracted ZIP does not.
 */
export function isWindowsPortable(): boolean {
  if (process.platform !== 'win32') return false
  if (!app.isPackaged) return false
  if (existsSync(join(process.resourcesPath, PORTABLE_MARKER))) return true

  const exePath = app.getPath('exe')
  const exeName = windowsPath.basename(exePath, windowsPath.extname(exePath))
  const uninstaller = windowsPath.join(windowsPath.dirname(exePath), `Uninstall ${exeName}.exe`)
  return !existsSync(uninstaller)
}

/** The directory containing `Infinia.exe` (the portable app root). */
export function resolveAppRoot(): string {
  return dirname(app.getPath('exe'))
}

/** The current app version, e.g. `4.0.0-beta.1`. */
export function currentVersion(): string {
  return app.getVersion()
}

/** Query the latest release and return the portable zip asset if it is newer than current. */
export async function checkPortableUpdate(
  repo = 'MuskStark/FengYu',
  fetchImpl: typeof fetch = fetch,
): Promise<PortableUpdateInfo | null> {
  const resp = await fetchImpl(RELEASES_API(repo), {
    headers: { 'User-Agent': 'FengYu-Updater', Accept: 'application/vnd.github+json' },
  })
  if (!resp.ok) throw new Error(`Release check returned HTTP ${resp.status}`)
  const body = await resp.json()
  // GitHub returns an array [release]; FY-Proxy returns a single release object (or [] when empty).
  const release: GitHubRelease | null = Array.isArray(body)
    ? body.length > 0 ? body[0] : null
    : body
  if (!release) return null
  const latest = stripLeadingV(release.tag_name)
  const expectedName = `Infinia-${latest}-win32-x64-portable.zip`
  const asset = (release.assets ?? []).find((a) => a.name === expectedName)
  if (!asset) return null
  if (compareVersions(latest, currentVersion()) <= 0) return null
  const sha256 = parseSha256Digest(asset.digest)
  if (process.env.FENGYU_UPDATE_API_BASE && !sha256) {
    throw new Error('FY-Proxy portable update metadata is missing a valid SHA-256 digest')
  }
  return {
    version: latest,
    zipUrl: asset.browser_download_url,
    sha256,
    releaseUrl: release.html_url,
    releaseName: release.name ?? latest,
  }
}

/**
 * Download the portable zip and extract it into a staging dir. `onProgress(percent)` fires
 * during download (0–100). Returns the extracted directory (which contains `Infinia.exe`).
 */
export async function downloadAndExtractPortable(
  info: PortableUpdateInfo,
  onProgress: (percent: number) => void = () => {},
  fetchImpl: typeof fetch = fetch,
): Promise<string> {
  const staging = mkdtempSync(join(tmpdir(), 'fengyu-portable-update-'))
  const zipPath = join(staging, 'portable.zip')
  const extractDir = join(staging, 'extracted')

  try {
    await downloadFile(info.zipUrl, zipPath, info.sha256, onProgress, fetchImpl)
    mkdirSync(extractDir, { recursive: true })
    // Windows 10 1803+ ships bsdtar as tar.exe; it handles .zip archives natively.
    const result = spawnSync('tar', ['-xf', zipPath, '-C', extractDir], { windowsHide: true })
    if (result.status !== 0) {
      throw new Error(
        `tar extraction failed (status ${result.status}): ${String(result.stderr || result.stdout)}`,
      )
    }
    // The zip may extract into a top-level folder (Infinia-<version>-win-x64-portable/) or flat.
    const exeFlat = join(extractDir, 'Infinia.exe')
    if (existsSync(exeFlat)) return extractDir
    const nested = findNestedExe(extractDir)
    if (nested) return dirname(nested)
    throw new Error('Extracted archive does not contain Infinia.exe')
  } finally {
    // The zip file itself is no longer needed; keep the extracted dir for applyPortableUpdate.
    try { rmSync(zipPath, { force: true }) } catch { /* ignore */ }
  }
}

/**
 * Spawn a detached `.bat` that waits for this app's PID to exit, robocopies the new tree over
 * the old directory, then relaunches Infinia.exe. Call `app.quit()` immediately after — the
 * script owns the rest. Waiting for PID death is mandatory: before-quit's tree-kill of the
 * backend JVM is fire-and-forget, and the JAR/exe file locks are not released until those
 * processes are actually gone.
 */
export function applyPortableUpdate(extractDir: string): void {
  const appRoot = resolveAppRoot()
  const pid = process.pid
  const logFile = join(tmpdir(), `fengyu-portable-update-${pid}.log`)
  const scriptPath = join(tmpdir(), `fengyu-portable-update-${pid}.bat`)

  const bat = [
    '@echo off',
    `REM Auto-generated portable self-update. PID ${pid}.`,
    'chcp 65001 >nul',
    ':waitold',
    `tasklist /FI "PID eq ${pid}" 2>nul | find "${pid}" >nul`,
    'if not errorlevel 1 (',
    '  timeout /t 1 /nobreak >nul',
    '  goto waitold',
    ')',
    'REM Give the OS a beat to release file handles after the process exited.',
    'timeout /t 2 /nobreak >nul',
    `robocopy "${extractDir}" "${appRoot}" /E /NFL /NDL /NJH /NJS /NP >nul 2>&1`,
    `start "" "${join(appRoot, 'Infinia.exe')}"`,
    `del "%~f0"`,
  ].join('\r\n')
  writeFileSync(scriptPath, bat, 'utf8')

  const child = spawn('cmd', ['/c', scriptPath], {
    detached: true,
    windowsHide: true,
    shell: false,
    stdio: 'ignore',
  })
  child.unref()
  void logFile // log path reserved for debugging; bat output is suppressed via /NJS
}

// ── helpers ──────────────────────────────────────────────────────────────

async function downloadFile(
  url: string,
  dest: string,
  expectedSha256: string | null,
  onProgress: (percent: number) => void,
  fetchImpl: typeof fetch,
): Promise<void> {
  const resp = await fetchImpl(url, { headers: { 'User-Agent': 'FengYu-Updater' } })
  if (!resp.ok || !resp.body) throw new Error(`Download failed: HTTP ${resp.status}`)
  const total = Number(resp.headers.get('content-length') || 0)
  let received = 0
  const sha256 = createHash('sha256')
  const stream = createWriteStream(dest)
  const reader = (
    resp.body as unknown as { getReader: () => { read: () => Promise<{ done: boolean; value?: Uint8Array }> } }
  ).getReader()
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      if (value) {
        received += value.byteLength
        sha256.update(value)
        if (total > 0) onProgress(Math.min(100, Math.round((received / total) * 100)))
        await new Promise<void>((resolve) => stream.write(Buffer.from(value), () => resolve()))
      }
    }
  } finally {
    await new Promise<void>((resolve) => stream.end(resolve))
  }
  const actualSha256 = sha256.digest('hex')
  if (expectedSha256 && actualSha256 !== expectedSha256) {
    throw new Error(`Portable update SHA-256 mismatch: expected ${expectedSha256}, got ${actualSha256}`)
  }
}

function parseSha256Digest(digest: string | null | undefined): string | null {
  if (!digest) return null
  const match = /^sha256:([0-9a-f]{64})$/i.exec(digest.trim())
  return match ? match[1].toLowerCase() : null
}

/** Find an `Infinia.exe` one level deep inside the extract dir (nested-zip case). */
function findNestedExe(dir: string): string | null {
  let entries: string[]
  try {
    entries = readdirSync(dir)
  } catch {
    return null
  }
  for (const entry of entries) {
    const candidate = join(dir, entry, 'Infinia.exe')
    if (existsSync(candidate)) return candidate
  }
  return null
}

function stripLeadingV(tag: string): string {
  return tag.startsWith('v') || tag.startsWith('V') ? tag.slice(1) : tag
}

/**
 * App-semver compare for `MAJOR.MINOR.PATCH` + optional `-alpha/-beta/-rc.N`. Returns >0 if
 * `left` is newer, 0 if equal, <0 if `right` is newer. Mirrors the backend's ordering so the
 * frontend and shell agree on which version wins.
 */
export function compareVersions(left: string, right: string): number {
  const a = numeric(left)
  const b = numeric(right)
  for (let i = 0; i < 3; i++) {
    if (a[i] !== b[i]) return a[i] - b[i]
  }
  const la = preRelease(left)
  const lb = preRelease(right)
  if (la.label === lb.label) return la.num - lb.num
  return preReleaseRank(la.label) - preReleaseRank(lb.label)
}

function numeric(version: string): [number, number, number] {
  const core = version.split('-')[0].split('.')
  const out: [number, number, number] = [0, 0, 0]
  for (let i = 0; i < Math.min(core.length, 3); i++) out[i] = parseInt(core[i], 10) || 0
  return out
}

function preRelease(version: string): { label: string; num: number } {
  const dash = version.indexOf('-')
  if (dash < 0) return { label: '', num: 0 }
  const suffix = version.slice(dash + 1)
  const dot = suffix.indexOf('.')
  const label = dot < 0 ? suffix : suffix.slice(0, dot)
  const num = dot < 0 ? 0 : parseInt(suffix.slice(dot + 1), 10) || 0
  return { label, num }
}

function preReleaseRank(label: string): number {
  if (!label) return 4 // release
  if (label === 'alpha') return 1
  if (label === 'beta') return 2
  if (label === 'rc') return 3
  return 0
}
