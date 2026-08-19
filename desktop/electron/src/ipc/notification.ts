import { ipcMain, Notification, BrowserWindow } from 'electron'

/**
 * Register the `notification:show` IPC handler — the renderer's unified
 * notification store calls it when a notification arrives while the window is
 * NOT visible (minimized to tray / another app focused), so the user still gets
 * a native OS notification instead of an in-app toast nobody sees. Clicking it
 * shows + focuses the main window.
 *
 * Returns false when the platform cannot show OS notifications (no desktop
 * notification daemon, kiosk session, ...); the renderer then simply stays with
 * the in-app notification center.
 */
export function registerNotificationIpc(getMainWindow: () => BrowserWindow | null): void {
  ipcMain.handle(
    'notification:show',
    (_event, opts: { title: string; body?: string }) => {
      if (!Notification.isSupported()) return false
      const native = new Notification({
        title: String(opts?.title ?? ''),
        body: String(opts?.body ?? ''),
      })
      native.on('click', () => {
        const win = getMainWindow()
        if (win) {
          win.show()
          win.focus()
        }
      })
      native.show()
      return true
    },
  )
}
