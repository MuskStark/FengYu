import { app, BrowserWindow } from 'electron'

/**
 * Acquire the single-instance lock. If a second instance launches, the first
 * instance's `second-instance` handler shows + focuses the existing window
 * (also restoring it from the tray). Returns false (and quits) when not the primary.
 *
 * `getMainWindow`, when provided, returns main.ts's explicit main-window reference — preferred
 * over any URL heuristic. It is null while only the splash exists (startup / bootstrap failure).
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
    // Fallback heuristic: getAllWindows()[0] is the splash during startup (it is created first) —
    // focusing the frameless, focusable:false splash is worse than nothing (it gets destroyed
    // moments later). Prefer the main window: the first live window that is not the splash.
    // Before the URL resolves getURL() returns '' (and isLoading() is true), so an unresolved
    // splash would otherwise pass the "not splash.html" test and get shown+focused — skip
    // windows that have not settled on a URL yet. When only such windows exist, no-op — the
    // main window will take focus when it appears.
    const main = BrowserWindow.getAllWindows().find((win) => {
      if (win.isDestroyed()) return false
      const wc = win.webContents
      if (wc.isLoading()) return false
      const url = wc.getURL()
      if (url === '') return false
      return !url.includes('splash.html')
    })
    if (!main) return
    onSecondInstance(main)
  })
  return true
}
