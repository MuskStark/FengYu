import type { BrowserWindow } from 'electron'

/**
 * Sender guard for privileged ipcMain channels (defense-in-depth).
 *
 * Today only the main window's webContents can reach these handlers: automation windows
 * live on `persist:fengyu-browser*` partition sessions with no preload, and sandboxed
 * plugin iframes cannot send IPC at all (Electron injects the preload into the main frame
 * only unless `nodeIntegrationInSubFrames` is set). Enforcing "main window only" here
 * keeps that property true by construction for any FUTURE default-session window or
 * frame instead of relying on convention.
 *
 * When no resolver is installed (unit-test registrations run without a main window),
 * the guard allows every sender — production main.ts installs the resolver in
 * bootstrap(), before the first renderer can load.
 */
let resolver: (() => BrowserWindow | null) | null = null

/** Install the main-window resolver used by {@link isMainWindowSender} (null uninstalls). */
export function setMainWindowResolver(next: (() => BrowserWindow | null) | null): void {
  resolver = next
}

/** True when `sender` is the main window's webContents (always true when unconfigured). */
export function isMainWindowSender(sender: unknown): boolean {
  if (!resolver) return true
  const win = resolver()
  if (!win || win.isDestroyed()) return false
  return sender === win.webContents
}
