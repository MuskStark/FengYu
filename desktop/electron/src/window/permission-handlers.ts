import { session } from 'electron'

/**
 * Which web permission requests the default session grants. Everything else is denied.
 *
 * `clipboard-sanitized-write` is Chromium's sanitized navigator.clipboard.writeText path —
 * gesture-driven, write-only (no clipboard READ) — the shell's copy-to-clipboard buttons
 * depend on it. Camera/microphone/geolocation/notifications/… are all denied: Electron
 * auto-approves every request when NO handler is registered (electron#12931), so without
 * this default-deny any installed third-party plugin UI could switch on the camera with
 * no prompt on Windows/Linux (M-7). When a plugin legitimately needs media someday, gate
 * it on a manifest permission — do not widen this list. Screen capture is unaffected: it
 * goes through the separate setDisplayMediaRequestHandler (screens only).
 */
export function permissionDecision(permission: string): boolean {
  return permission === 'clipboard-sanitized-write'
}

export function registerPermissionHandlers(): void {
  session.defaultSession.setPermissionRequestHandler((_contents, permission, callback) => {
    callback(permissionDecision(permission))
  })
}
