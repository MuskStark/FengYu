import { ipcMain, BrowserWindow, shell } from 'electron'
import { autoUpdater } from 'electron-updater'
import { app } from 'electron'
import {
  isWindowsPortable,
  checkPortableUpdate,
  downloadAndExtractPortable,
  applyPortableUpdate,
} from '../updater/portable-updater'
import { configureUpdateFeed, updateApiBase, updateDownloadPageUrl } from '../updater/update-feed'

/**
 * Renderer-driven update flow, distinct from the startup check in `auto-updater.ts`.
 *
 * P0-9 boundary: this module ONLY acts on an explicit renderer request (the user clicked
 * "update now" in the UI). It never auto-downloads on a bare check, and it leaves the
 * signedRelease flag from `auto-updater.ts` untouched. The consent that authorizes an
 * unsigned install comes from the user gesture, not from flipping the signed flag.
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
  // effect immediately without a restart. In-process IPC — works offline. Only the env var is
  // mutated; validation happens lazily in updateApiBase() at check time.
  ipcMain.handle('update:set-api-base', (_event, url: unknown) => {
    process.env.FENGYU_UPDATE_API_BASE = typeof url === 'string' ? url : ''
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
  ipcMain.handle('update:download-install', async (): Promise<UpdateInstallResult> => {
    // Windows portable zip: download + extract + spawn the replace-and-restart bat.
    if (isWindowsPortable()) {
      try {
        const info = await checkPortableUpdate()
        if (!info) return { action: 'manual', releaseUrl: releasePageUrl() }
        broadcast('update:state', { state: 'downloading' })
        const extractDir = await downloadAndExtractPortable(info, (percent) =>
          broadcast('update:progress', { percent, transferred: 0, total: 0, bytesPerSecond: 0 }),
        )
        applyPortableUpdate(extractDir)
        // app.quit() triggers before-quit → killBackend (tree-kill of the JVM) → the detached
        // bat waits for this PID to exit, robocopies the new tree, and relaunches Infinia.exe.
        app.quit()
        return { action: 'restarting' }
      } catch (err) {
        console.error('[updater] portable download-install failed:', err)
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

    await autoUpdater.downloadUpdate()
    // quitAndInstall fires before-quit AFTER closing windows (per electron-updater docs), so the
    // backend-kill cleanup in main.ts still runs.
    autoUpdater.quitAndInstall()
    return { action: 'restarting' }
  })
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
