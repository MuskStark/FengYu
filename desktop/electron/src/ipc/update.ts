import { ipcMain, BrowserWindow, shell } from 'electron'
import { autoUpdater } from 'electron-updater'
import { app } from 'electron'
import {
  isWindowsPortable,
  checkPortableUpdate,
  downloadAndExtractPortable,
  applyPortableUpdate,
} from '../updater/portable-updater'

/**
 * Renderer-driven update flow, distinct from the startup check in `auto-updater.ts`.
 *
 * P0-9 boundary: this module ONLY acts on an explicit renderer request (the user clicked
 * "update now" in the UI). It never auto-downloads on a bare check, and it leaves the
 * signedRelease flag from `auto-updater.ts` untouched. The consent that authorizes an
 * unsigned install comes from the user gesture, not from flipping the signed flag.
 *
 * Platform split: on Windows/Linux the user-consented path calls downloadUpdate() +
 * quitAndInstall() (the OS may warn about an unsigned binary — expected). On macOS an
 * unsigned replace cannot relaunch under Gatekeeper, so we open the releases page for a
 * manual download+drag-in instead — the only reliable unsigned-mac path until code-signing
 * lands.
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
        return { updateAvailable: false, version: null, releaseUrl: releasePageUrl() }
      }
    }
    autoUpdater.autoDownload = false
    autoUpdater.autoInstallOnAppQuit = false
    try {
      const result = await autoUpdater.checkForUpdates()
      const info = result?.updateInfo
      return {
        updateAvailable: !!info && info.version !== autoUpdater.currentVersion,
        version: info?.version ?? null,
        releaseUrl: extractReleaseUrl(info),
      }
    } catch (err) {
      console.error('[updater] update:check failed:', err)
      return { updateAvailable: false, version: null, releaseUrl: releasePageUrl() }
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
  return 'https://github.com/MuskStark/FengYu/releases'
}

/** Send a payload to every live renderer window (guards isDestroyed). */
function broadcast(channel: string, payload: unknown): void {
  for (const win of BrowserWindow.getAllWindows()) {
    if (!win.isDestroyed()) win.webContents.send(channel, payload)
  }
}

