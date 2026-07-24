import { app, BrowserWindow } from 'electron'

/**
 * Acquire the single-instance lock. If a second instance launches, the first
 * instance's `second-instance` handler shows + focuses the existing window
 * (also restoring it from the tray). Returns false (and quits) when not the primary.
 */
export function acquireSingleInstanceLock(
  onSecondInstance: (win: BrowserWindow | null) => void,
): boolean {
  const gotLock = app.requestSingleInstanceLock()
  if (!gotLock) {
    app.quit()
    return false
  }
  app.on('second-instance', () => {
    onSecondInstance(BrowserWindow.getAllWindows()[0] ?? null)
  })
  return true
}
