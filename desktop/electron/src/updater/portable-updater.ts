import { app } from 'electron'
import { spawn, spawnSync } from 'node:child_process'
import { createWriteStream, existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { dirname, join, win32 as windowsPath } from 'node:path'
import { runtimeRoot } from '../desktop/runtime-paths'
import { logUpdate, updateLogPath } from './update-log'

const PORTABLE_MARKER = 'fengyu-portable-zip'

/**
 * Hard cap on the portable zip download, mirroring the backend's MAX_JAR_BYTES (512 MB) in
 * SelfUpdateService: a corrupt or malicious feed must never stream unbounded bytes into the
 * staging area. Enforced twice — against a declared Content-Length before any byte is read, and
 * against the bytes actually received (the header is a claim, not a fact).
 */
const MAX_DOWNLOAD_BYTES = 512 * 1024 * 1024

/**
 * Windows portable-zip self-update.
 *
 * electron-updater cannot update a portable/zip build — its NsisUpdater is hardcoded to run a
 * `-setup.exe` installer with `elevate.exe` + `app-update.yml`, none of which exist in a
 * portable zip. This module implements the full pipeline ourselves: check the latest GitHub
 * release (or FY-Proxy mirror), download the new portable zip, extract it (via the system
 * `tar`, available on Windows 10 1803+), then spawn a detached `.bat` that waits for this app
 * to exit, robocopies the new tree over the old one, and relaunches `Infinia.exe`.
 *
 * Every step appends to `<cwd>/.fengyu/logs/update.log` (see updater/update-log.ts); the
 * replace script and the download staging dir live under the app's own runtime tree, never
 * %TEMP% — intranet machines commonly block executing scripts from temp directories.
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
  // windowsPath (not the platform default) so the bat pipeline is testable off-Windows.
  return windowsPath.dirname(app.getPath('exe'))
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
  const feedUrl = RELEASES_API(repo)
  logUpdate(`[check] probing latest release: current ${currentVersion()}, feed ${feedUrl}`)
  const resp = await fetchImpl(feedUrl, {
    headers: { 'User-Agent': 'FengYu-Updater', Accept: 'application/vnd.github+json' },
  })
  if (!resp.ok) throw new Error(`Release check returned HTTP ${resp.status}`)
  const body = await resp.json()
  // GitHub returns an array [release]; FY-Proxy returns a single release object (or [] when empty).
  const release: GitHubRelease | null = Array.isArray(body)
    ? body.length > 0 ? body[0] : null
    : body
  if (!release) {
    logUpdate('[check] feed returned no release — no update')
    return null
  }
  const latest = stripLeadingV(release.tag_name)
  const expectedName = `Infinia-${latest}-win32-x64-portable.zip`
  const asset = (release.assets ?? []).find((a) => a.name === expectedName)
  if (!asset) {
    logUpdate(`[check] release ${release.tag_name} has no ${expectedName} asset — no update`)
    return null
  }
  if (compareVersions(latest, currentVersion()) <= 0) {
    logUpdate(`[check] latest ${latest} is not newer than current ${currentVersion()} — up to date`)
    return null
  }
  const sha256 = parseSha256Digest(asset.digest)
  if (process.env.FENGYU_UPDATE_API_BASE && !sha256) {
    throw new Error('FY-Proxy portable update metadata is missing a valid SHA-256 digest')
  }
  // Integrity gate for any remaining plain-HTTP source: bytes fetched over http:// are
  // tamperable in transit, so download+install is refused unless the feed publishes a digest
  // to verify against (downloadFile checks it). HTTPS keeps the digest-optional status quo —
  // the GitHub API path does not publish asset digests at all.
  if (!sha256 && !/^https:\/\//i.test(asset.browser_download_url)) {
    throw new Error(
      `Refusing portable update served over plain HTTP without a SHA-256 digest: ${asset.browser_download_url}`,
    )
  }
  logUpdate(`[check] update available: ${currentVersion()} -> ${latest} (${expectedName})`)
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
 *
 * The staging dir lives under the app's runtime tree (`.fengyu/update-staging-*`), NOT %TEMP%:
 * the replace script robocopies from it after the shell exits, and temp-cleaning policies or
 * script-execution blocks on intranet machines would break the copy mid-flight.
 */
export async function downloadAndExtractPortable(
  info: PortableUpdateInfo,
  onProgress: (percent: number) => void = () => {},
  fetchImpl: typeof fetch = fetch,
): Promise<string> {
  const staging = mkdtempSync(windowsPath.join(runtimeRoot(), 'update-staging-'))
  const zipPath = join(staging, 'portable.zip')
  const extractDir = join(staging, 'extracted')
  logUpdate(`[extract] staging dir ${staging}`)

  try {
    // Progress milestones are logged at 25% steps (the renderer gets every tick); enough to
    // see a stall in the log without one line per chunk.
    let lastMilestone = 0
    const progress = (percent: number) => {
      onProgress(percent)
      const milestone = Math.floor(percent / 25) * 25
      if (milestone > lastMilestone) {
        lastMilestone = milestone
        logUpdate(`[download] ${percent}%`)
      }
    }
    await downloadFile(info.zipUrl, zipPath, info.sha256, progress, fetchImpl)
    mkdirSync(extractDir, { recursive: true })
    logUpdate(`[extract] extracting zip via tar into ${extractDir}`)
    // Windows 10 1803+ ships bsdtar as tar.exe; it handles .zip archives natively.
    const result = spawnSync('tar', ['-xf', zipPath, '-C', extractDir], { windowsHide: true })
    if (result.status !== 0) {
      logUpdate(`[extract] FAILED: tar exit ${result.status}: ${String(result.stderr || result.stdout).slice(0, 500)}`)
      throw new Error(
        `tar extraction failed (status ${result.status}): ${String(result.stderr || result.stdout)}`,
      )
    }
    // The zip may extract into a top-level folder (Infinia-<version>-win-x64-portable/) or flat.
    const exeFlat = join(extractDir, 'Infinia.exe')
    if (existsSync(exeFlat)) {
      logUpdate(`[extract] staged new tree (flat layout) at ${extractDir}`)
      return extractDir
    }
    const nested = findNestedExe(extractDir)
    if (nested) {
      logUpdate(`[extract] staged new tree (nested layout) at ${dirname(nested)}`)
      return dirname(nested)
    }
    logUpdate(`[extract] FAILED: no Infinia.exe in the extracted archive`)
    throw new Error('Extracted archive does not contain Infinia.exe')
  } finally {
    // The zip file itself is no longer needed; keep the extracted dir for applyPortableUpdate.
    try { rmSync(zipPath, { force: true }) } catch { /* ignore */ }
  }
}

/**
 * Write the replace script into the APP ROOT and spawn it detached. The script waits for this
 * app's PID to exit, robocopies the new tree over the old directory, then relaunches
 * Infinia.exe. Call `app.quit()` immediately after — the script owns the rest. Waiting for PID
 * death is mandatory: before-quit's tree-kill of the backend JVM is fire-and-forget, and the
 * JAR/exe file locks are not released until those processes are actually gone.
 *
 * The script lives at `<appRoot>/fengyu-portable-update.bat` (NOT %TEMP%): intranet/EDR
 * policies commonly block executing scripts from temp directories — the silent killer where
 * the replace never even starts. A fixed name lets the next attempt overwrite a stale script.
 * Every step of the script appends to `.fengyu/logs/update.log` — the SAME file the
 * main-process stages wrote to, so one file reconstructs the entire update.
 *
 * The wait loop is hardened against the two field failures of a bare `tasklist | find <pid>`
 * loop (seen as an install stuck on a "find <pid>" console):
 *   - PID reuse — after the old process exits, Windows can hand its PID to an unrelated
 *     process; the loop would then pin on a stranger that never exits. Matching the image
 *     name too (`/FI "IMAGENAME eq ..."`) makes the loop end when OUR process is gone.
 *   - A stuck old process (e.g. the quit was vetoed) — after ~90 polls the script force-kills
 *     the old PID tree (`taskkill /F /T`) so file locks release and the update proceeds.
 * tasklist/find/taskkill are invoked by absolute path: a PATH-shadowing `find.exe` (Git for
 * Windows, GnuWin) sits ahead of System32 and breaks the match. Delays use `ping`, not
 * `timeout`, which errors immediately without a console input handle and would busy-spin the
 * loop.
 */
export function applyPortableUpdate(extractDir: string): void {
  const appRoot = resolveAppRoot()
  const exeName = windowsPath.basename(app.getPath('exe'))
  const pid = process.pid
  const scriptPath = windowsPath.join(appRoot, 'fengyu-portable-update.bat')
  const logPath = updateLogPath()
  logUpdate(
    `[apply] replace script written to ${scriptPath}; it will wait on PID ${pid} (${exeName}), ` +
      `robocopy from ${extractDir} into ${appRoot}, then relaunch. Script steps continue in this log.`,
  )

  const bat = [
    '@echo off',
    `REM Auto-generated Infinia portable self-update. Old PID ${pid}, generated ${new Date().toISOString()}.`,
    'chcp 65001 >nul',
    `set "LOG=${logPath}"`,
    `echo [%DATE% %TIME%] [replace] waiting for old PID ${pid} (${exeName}) to exit >> "%LOG%"`,
    'set /a tries=0',
    ':waitold',
    `%SystemRoot%\\System32\\tasklist.exe /FI "PID eq ${pid}" /FI "IMAGENAME eq ${exeName}" 2>nul | %SystemRoot%\\System32\\find.exe "${pid}" >nul`,
    'if errorlevel 1 goto waitdone',
    'set /a tries+=1',
    'if %tries% equ 1 echo [%DATE% %TIME%] [replace] old process still present (poll 1) >> "%LOG%"',
    'if %tries% geq 90 (',
    `  echo [%DATE% %TIME%] [replace] PID ${pid} still alive after ~90 polls, force-killing process tree >> "%LOG%"`,
    `  %SystemRoot%\\System32\\taskkill.exe /F /T /PID ${pid} >> "%LOG%" 2>&1`,
    '  goto waitdone',
    ')',
    'ping -n 2 127.0.0.1 >nul',
    'goto waitold',
    ':waitdone',
    'echo [%DATE% %TIME%] [replace] old process gone after %tries% polls, holding 2s for file handles >> "%LOG%"',
    'ping -n 3 127.0.0.1 >nul',
    `echo [%DATE% %TIME%] [replace] robocopy "${extractDir}" into "${appRoot}" starting (retries bounded: 5 x 2s) >> "%LOG%"`,
    // /R:5 /W:2 bounds robocopy's DEFAULT of a million retries x 30s — a still-locked file
    // must fail within seconds (and be logged) instead of hanging the script for days.
    `robocopy "${extractDir}" "${appRoot}" /E /R:5 /W:2 /NFL /NDL /NJH /NJS /NP >> "%LOG%" 2>&1`,
    'echo [%DATE% %TIME%] [replace] robocopy finished, exit code %ERRORLEVEL% (0-7 are success levels) >> "%LOG%"',
    `echo [%DATE% %TIME%] [replace] relaunching ${exeName} >> "%LOG%"`,
    `start "" "${windowsPath.join(appRoot, 'Infinia.exe')}"`,
    'echo [%DATE% %TIME%] [replace] update complete, removing script >> "%LOG%"',
    'del "%~f0"',
  ].join('\r\n')
  writeFileSync(scriptPath, bat, 'utf8')

  const child = spawn('cmd', ['/c', scriptPath], {
    detached: true,
    windowsHide: true,
    shell: false,
    stdio: 'ignore',
  })
  child.unref()
  logUpdate(`[apply] replace script spawned (cmd /c, hidden, detached); shell will quit next`)
}

// ── helpers ──────────────────────────────────────────────────────────────

async function downloadFile(
  url: string,
  dest: string,
  expectedSha256: string | null,
  onProgress: (percent: number) => void,
  fetchImpl: typeof fetch,
): Promise<void> {
  const startedAt = Date.now()
  const resp = await fetchImpl(url, { headers: { 'User-Agent': 'FengYu-Updater' } })
  if (!resp.ok || !resp.body) throw new Error(`Download failed: HTTP ${resp.status}`)
  const total = Number(resp.headers.get('content-length') || 0)
  logUpdate(`[download] fetching ${url} (declared size ${total || 'unknown'} bytes)`)
  if (total > MAX_DOWNLOAD_BYTES) {
    // Declared over the cap: refuse before a single byte is streamed (release the connection
    // best-effort on the way out).
    try { void resp.body.cancel().catch(() => {}) } catch { /* body already closed */ }
    throw new Error(
      `Portable update download refused: Content-Length ${total} exceeds the ` +
        `${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB cap`,
    )
  }
  let received = 0
  const sha256 = createHash('sha256')
  const stream = createWriteStream(dest)
  const reader = (
    resp.body as unknown as {
      getReader: () => {
        read: () => Promise<{ done: boolean; value?: Uint8Array }>
        cancel: () => Promise<void>
      }
    }
  ).getReader()
  // A write failure must surface as a rejected promise, not an unhandled 'error' event: the
  // first stream error settles this promise, and every write/end below races against it.
  const streamFailed = new Promise<never>((_, reject) => stream.once('error', reject))
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      if (!value) continue
      received += value.byteLength
      if (received > MAX_DOWNLOAD_BYTES) {
        // The header lied or is absent: abort mid-flight instead of filling the disk.
        throw new Error(
          `Portable update download aborted: ${received} bytes received exceeds the ` +
            `${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB cap`,
        )
      }
      sha256.update(value)
      if (total > 0) onProgress(Math.min(100, Math.round((received / total) * 100)))
      await Promise.race([
        new Promise<void>((resolve, reject) => {
          stream.write(Buffer.from(value), (err) => (err ? reject(err) : resolve()))
        }),
        streamFailed,
      ])
    }
    await Promise.race([
      new Promise<void>((resolve) => {
        stream.end(() => resolve()) // a flush failure surfaces via 'error' → streamFailed
      }),
      streamFailed,
    ])
    const actualSha256 = sha256.digest('hex')
    logUpdate(
      `[download] complete: ${received} bytes in ${Date.now() - startedAt} ms, sha256 ${actualSha256}` +
        (expectedSha256 ? ` (feed declared ${expectedSha256})` : ' (no digest declared)'),
    )
    if (expectedSha256 && actualSha256 !== expectedSha256) {
      logUpdate(`[download] FAILED: sha256 mismatch — expected ${expectedSha256}, got ${actualSha256}`)
      throw new Error(`Portable update SHA-256 mismatch: expected ${expectedSha256}, got ${actualSha256}`)
    }
  } catch (err) {
    logUpdate(`[download] FAILED after ${received} bytes: ${err instanceof Error ? err.message : String(err)}`)
    // Controlled failure on every path (cap hit, network error, write error, digest mismatch):
    // abort the transfer, tear down the stream, and drop the half-written zip so nothing
    // corrupt lingers in staging. The caller's finally repeats the file cleanup harmlessly.
    try { void reader.cancel().catch(() => {}) } catch { /* reader already closed */ }
    stream.destroy()
    try { rmSync(dest, { force: true }) } catch { /* ignore */ }
    throw err
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
