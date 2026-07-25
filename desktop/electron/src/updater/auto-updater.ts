import { autoUpdater } from 'electron-updater'
import { dialog } from 'electron'
import { existsSync } from 'node:fs'
import { join } from 'node:path'

/**
 * Check for updates (async, non-blocking). Source: GitHub Releases (latest*.yml).
 * Alpha builds are unsigned — electron-updater supports unsigned updates on Windows
 * (NSIS); macOS users must allow Gatekeeper manually.
 */
export async function checkForUpdates(): Promise<void> {
  // JRE variant bundles its own jlink JRE under <resourcesPath>/jre. The updater feed
  // (latest*.yml) only references the lite variant, so auto-update would silently downgrade
  // JRE users to the Java-dependent lite build. Skip the check until per-variant feeds exist.
  if (existsSync(join(process.resourcesPath, 'jre'))) {
    console.log('[updater] JRE variant detected; skipping auto-update (would downgrade to lite)')
    return
  }
  try {
    const result = await autoUpdater.checkForUpdates()
    if (!result?.updateInfo) return
    const choice = await dialog.showMessageBox({
      type: 'question',
      buttons: ['Download & install', 'Later'],
      defaultId: 0,
      title: 'Update available',
      message: `Infinia ${result.updateInfo.version} is available. Download and install now?`,
    })
    if (choice.response === 0) {
      await autoUpdater.downloadUpdate()
      autoUpdater.quitAndInstall()
    }
  } catch (err) {
    console.error('[updater] check failed:', err)
  }
}
