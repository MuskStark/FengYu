import { autoUpdater } from 'electron-updater'
import { dialog } from 'electron'

/**
 * Check for updates (async, non-blocking). Source: GitHub Releases (latest*.yml).
 * Alpha builds are unsigned — electron-updater supports unsigned updates on Windows
 * (NSIS); macOS users must allow Gatekeeper manually.
 */
export async function checkForUpdates(): Promise<void> {
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
