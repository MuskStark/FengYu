import { app, BrowserWindow } from 'electron'

/**
 * Acquire the single-instance lock. If a second instance launches, the first
 * instance's `second-instance` handler shows + focuses the existing window
 * (also restoring it from the tray). Returns false (and quits) when not the primary.
 *
 * `getMainWindow`, when provided, returns main.ts's explicit main-window reference — preferred
 * over any URL heuristic. It is null before the window is created (startup / bootstrap failure).
 */
export function acquireSingleInstanceLock(
  onSecondInstance: (win: BrowserWindow | null) => void,
  getMainWindow?: () => BrowserWindow | null,
): boolean {
  const gotLock = app.requestSingleInstanceLock()
  if (!gotLock) {
    app.quit()
    return false
  }
  app.on('second-instance', () => {
    const explicit = getMainWindow?.()
    if (explicit && !explicit.isDestroyed()) {
      onSecondInstance(explicit)
      return
    }
    // Fallback heuristic (no explicit reference yet): the first live window that
    // has settled on a URL. Before the URL resolves getURL() returns '' (and
    // isLoading() is true), so an unresolved window must not be shown+focused.
    // When only such windows exist, no-op — the main window will take focus when
    // it appears.
    const main = BrowserWindow.getAllWindows().find((win) => {
      if (win.isDestroyed()) return false
      const wc = win.webContents
      if (wc.isLoading()) return false
      return wc.getURL() !== ''
    })
    if (!main) return
    onSecondInstance(main)
  })
  return true
}
