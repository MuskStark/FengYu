import { ipcMain, BrowserWindow, dialog, shell } from 'electron'
import { autoUpdater } from 'electron-updater'
import { app } from 'electron'
import {
  isWindowsPortable,
  checkPortableUpdate,
  downloadAndExtractPortable,
  applyPortableUpdate,
} from '../updater/portable-updater'
import { configureUpdateFeed, updateApiBase, updateDownloadPageUrl, validateUpdateApiBase } from '../updater/update-feed'
import { logUpdate } from '../updater/update-log'
import { markUpdateInstallRestart } from '../desktop/graceful-quit'

/**
 * Renderer-driven update flow, distinct from the startup check in `auto-updater.ts`.
 *
 * P0-9 boundary: this module ONLY acts on an explicit renderer request (the user clicked
 * "update now" in the UI). It never auto-downloads on a bare check, and it leaves the
 * signedRelease flag from `auto-updater.ts` untouched. The renderer's click is only a
 * request: every download+install path re-confirms with a NATIVE dialog
 * (confirmNativeInstall) so a compromised renderer cannot drive an install alone.
 *
 * Platform split: on Windows/Linux, a user-consented FY-Proxy update calls downloadUpdate() +
 * quitAndInstall() (the OS may warn about an unsigned binary — expected). The shared public
 * GitHub feed and unsigned macOS builds use manual download because they cannot safely identify
 * and replace the current lite/JRE variant.
 */

export interface UpdateCheckPayload {
  updateAvailable: boolean
  version: string | null
  releaseUrl: string | null
}

export type UpdateInstallResult =
  | { action: 'restarting' }
  | { action: 'manual'; releaseUrl: string }

let progressWired = false
// One install at a time: double-invoking would run two checks + two consent dialogs and race
// two downloads (the portable path would even apply twice via two detached replace bats).
let installInFlight = false

export function registerUpdateIpc(): void {
  // Wire progress/state pushes once. autoUpdater is a process-wide singleton, so the listeners
  // are idempotent across multiple registerUpdateIpc() calls (defensive — it's called once).
  if (!progressWired) {
    autoUpdater.on('download-progress', (info) => broadcast('update:progress', info))
    autoUpdater.on('update-downloaded', () => broadcast('update:state', { state: 'downloaded' }))
    autoUpdater.on('error', (err) => broadcast('update:state', { state: 'error', message: String(err) }))
    progressWired = true
  }

  // Push the update-channel proxy URL from the renderer into the main process. The next update
  // check reads `process.env.FENGYU_UPDATE_API_BASE` fresh (see update-feed.ts), so this takes
  // effect immediately without a restart. In-process IPC — works offline. The FULL rule set
  // (not just the scheme) is validated HERE via the shared `validateUpdateApiBase` — the same
  // rules `updateApiBase()` applies at check time — so a compromised renderer cannot smuggle
  // file:/other schemes or credential/query-bearing URLs into the updater. Plain http stays
  // allowed: intranet FY-Proxy feeds over HTTP are an intentionally supported deployment.
  ipcMain.handle('update:set-api-base', (_event, url: unknown) => {
    if (url === '') {
      process.env.FENGYU_UPDATE_API_BASE = ''
      console.log('[updater] update api-base cleared (public GitHub feed)')
      return
    }
    if (typeof url !== 'string') {
      throw new Error('update api-base must be a string (or empty string to clear)')
    }
    const parsed = validateUpdateApiBase(url)
    process.env.FENGYU_UPDATE_API_BASE = url
    // Log the origin only — never echo a URL that could carry credentials or a noisy path.
    console.log(`[updater] update api-base set to ${parsed.origin}`)
  })

  // Check only — never downloads. The startup check (auto-updater.ts) keeps its own notify-only
  // behavior; this is the renderer's "is there something new?" probe for the About page.
  ipcMain.handle('update:check', async (): Promise<UpdateCheckPayload> => {
    // Windows portable zip: electron-updater can't handle it; use the custom portable pipeline.
    if (isWindowsPortable()) {
      try {
        const info = await checkPortableUpdate()
        return {
          updateAvailable: !!info,
          version: info?.version ?? null,
          releaseUrl: info?.releaseUrl ?? releasePageUrl(),
        }
      } catch (err) {
        console.error('[updater] portable update:check failed:', err)
        logUpdate(`[check] portable update check FAILED: ${err instanceof Error ? err.message : String(err)}`)
        throw err
      }
    }
    autoUpdater.autoDownload = false
    autoUpdater.autoInstallOnAppQuit = false
    try {
      configureUpdateFeed()
      const result = await autoUpdater.checkForUpdates()
      const info = result?.updateInfo
      const currentVersion = typeof autoUpdater.currentVersion === 'string'
        ? autoUpdater.currentVersion
        : autoUpdater.currentVersion.version
      return {
        updateAvailable: !!info && info.version !== currentVersion,
        version: info?.version ?? null,
        releaseUrl: extractReleaseUrl(info),
      }
    } catch (err) {
      console.error('[updater] update:check failed:', err)
      throw err
    }
  })

  // User-consented install. Reaches here only after the renderer's "update now" click.
  // Re-entry guard: a second invoke while one is in flight fails fast — no second check,
  // consent dialog, download, or portable replace bat.
  ipcMain.handle('update:download-install', async (): Promise<UpdateInstallResult> => {
    if (installInFlight) {
      throw new Error('an update download/install is already in progress')
    }
    installInFlight = true
    try {
      return await downloadAndInstall()
    } finally {
      installInFlight = false
    }
  })
}

async function downloadAndInstall(): Promise<UpdateInstallResult> {
  // Windows portable zip: download + extract + spawn the replace-and-restart bat.
  if (isWindowsPortable()) {
    try {
      logUpdate('[install] user requested install; resolving latest release')
      const info = await checkPortableUpdate()
      if (!info) {
        logUpdate('[install] no portable update available on the feed; falling back to manual download')
        return { action: 'manual', releaseUrl: releasePageUrl() }
      }
      if (!confirmNativeInstall(info.version, info.releaseName)) {
        logUpdate(`[install] user DECLINED the install dialog for ${info.version}; falling back to manual download`)
        return { action: 'manual', releaseUrl: info.releaseUrl }
      }
      logUpdate(`[install] consent given for ${info.version}; downloading ${info.zipUrl}`)
      broadcast('update:state', { state: 'downloading' })
      const extractDir = await downloadAndExtractPortable(info, (percent) =>
        broadcast('update:progress', { percent, transferred: 0, total: 0, bytesPerSecond: 0 }),
      )
      logUpdate(`[install] download + extract complete; applying replace script`)
      applyPortableUpdate(extractDir)
      // app.quit() triggers before-quit → the backend tree is force-killed (the graceful-quit
      // handler skips its wait for update restarts via markUpdateInstallRestart) → the detached
      // bat waits for this PID to exit, robocopies the new tree, and relaunches Infinia.exe.
      markUpdateInstallRestart()
      // A renderer beforeunload (FlowBuilder's unsaved-changes guard) silently vetoes window
      // close in Electron and ABORTS app.quit() — the shell process would linger while the
      // replace bat waits on its PID forever (the stuck "find <pid>" console). destroy()
      // bypasses close/beforeunload entirely; backend teardown still runs via before-quit.
      const windows = BrowserWindow.getAllWindows()
      logUpdate(`[install] destroying ${windows.length} window(s) and quitting for the replace script`)
      for (const win of windows) {
        if (!win.isDestroyed()) win.destroy()
      }
      app.quit()
      // Backstop: if anything else still vetoes the quit, exit hard so the replace bat is
      // never left waiting on a live PID. before-quit has already force-killed the backend.
      setTimeout(() => app.exit(0), 10_000).unref()
      return { action: 'restarting' }
    } catch (err) {
      console.error('[updater] portable download-install failed:', err)
      logUpdate(`[install] portable install FAILED: ${err instanceof Error ? err.message : String(err)}`)
      broadcast('update:state', { state: 'error', message: String(err) })
      return { action: 'manual', releaseUrl: releasePageUrl() }
    }
  }

  autoUpdater.autoDownload = false
  autoUpdater.autoInstallOnAppQuit = false

  // GitHub publishes one shared latest*.yml for lite + JRE and the last build overwrites it.
  // Installing from that ambiguous feed can switch variants. FY-Proxy has separate feeds and
  // is the only electron-updater source that is safe to install automatically at runtime.
  try {
    if (!updateApiBase()) {
      await shell.openExternal(releasePageUrl())
      return { action: 'manual', releaseUrl: releasePageUrl() }
    }
    configureUpdateFeed()
  } catch (err) {
    console.error('[updater] invalid intranet update feed:', err)
    await shell.openExternal(releasePageUrl())
    return { action: 'manual', releaseUrl: releasePageUrl() }
  }

  // macOS: an unsigned quitAndInstall leaves the app unable to relaunch (Gatekeeper). Open the
  // releases page for a manual download + drag-in until a signed+notarized build exists.
  if (process.platform === 'darwin') {
    await shell.openExternal(releasePageUrl())
    return { action: 'manual', releaseUrl: releasePageUrl() }
  }

  // Native consent gate — the renderer's click is a request, not authorization. A
  // main-process dialog (mirroring offerAutoInstall in auto-updater.ts) must confirm
  // before anything is downloaded and installed into the app directory.
  const version = await latestFeedVersion()
  if (!confirmNativeInstall(version)) {
    return { action: 'manual', releaseUrl: releasePageUrl() }
  }

  await autoUpdater.downloadUpdate()
  // quitAndInstall fires before-quit AFTER closing windows (per electron-updater docs), so the
  // backend cleanup in main.ts still runs — as a force-kill, not a graceful wait
  // (markUpdateInstallRestart), so the installer is not delayed behind a shutdown.
  markUpdateInstallRestart()
  autoUpdater.quitAndInstall()
  return { action: 'restarting' }
}

/**
 * Native confirmation for a download+install (mirrors `offerAutoInstall` in auto-updater.ts,
 * but synchronous — the decision must be made before any destructive step). The message names
 * the feed host the code would come from; `notes` is a short release-name/notes summary shown
 * under the version.
 */
function confirmNativeInstall(version: string | null, notes?: string): boolean {
  const detail = notesSummary(notes)
  const source = installSourceHost()
  const fromSource = source ? ` from ${source}` : ''
  const choice = dialog.showMessageBoxSync({
    type: 'question',
    buttons: ['Install', 'Cancel'],
    defaultId: 0,
    cancelId: 1,
    title: 'Update Infinia',
    message: version
      ? `Download and install Infinia ${version}${fromSource} now?`
      : `Download and install the update${fromSource} now?`,
    ...(detail ? { detail } : {}),
  })
  return choice === 0
}

/**
 * Host of the feed an install would download from: the configured FY-Proxy host, or the GitHub
 * default when no proxy is set. Shown in the consent dialog so the user can see where code
 * would be installed from. Null when the configured override is malformed (that is surfaced
 * separately at check time).
 */
function installSourceHost(): string | null {
  try {
    const base = updateApiBase()
    return base ? new URL(base).host : 'github.com'
  } catch {
    return null
  }
}

/** Resolve the version the current feed would install (null when the feed has no update info). */
async function latestFeedVersion(): Promise<string | null> {
  try {
    const result = await autoUpdater.checkForUpdates()
    const info = result?.updateInfo
    if (!info) return null
    return typeof info.version === 'string' ? info.version : null
  } catch (err) {
    console.error('[updater] pre-consent version check failed:', err)
    return null
  }
}

/** Flatten release notes (string or electron-updater's array form) into a short one-liner. */
function notesSummary(notes: unknown): string {
  let text: string
  if (typeof notes === 'string') {
    text = notes
  } else if (Array.isArray(notes) && typeof (notes[0] as { note?: unknown } | undefined)?.note === 'string') {
    text = (notes[0] as { note: string }).note
  } else {
    return ''
  }
  return text.replace(/\s+/g, ' ').trim().slice(0, 200)
}

/** Extract a GitHub release tag URL from electron-updater's UpdateInfo when present. */
function extractReleaseUrl(info: { releaseNotes?: unknown } | undefined): string | null {
  if (!info) return null
  const notes = info.releaseNotes
  if (typeof notes === 'string') {
    const match = notes.match(/https:\/\/github\.com\/MuskStark\/FengYu\/releases\/tag\/[^\s")]+/)
    if (match) return match[0]
  }
  return releasePageUrl()
}

function releasePageUrl(): string {
  try {
    return updateDownloadPageUrl()
  } catch (err) {
    console.error('[updater] invalid intranet update download page:', err)
    return 'https://github.com/MuskStark/FengYu/releases'
  }
}

/** Send a payload to every live renderer window (guards isDestroyed). */
function broadcast(channel: string, payload: unknown): void {
  for (const win of BrowserWindow.getAllWindows()) {
    if (!win.isDestroyed()) win.webContents.send(channel, payload)
  }
}
