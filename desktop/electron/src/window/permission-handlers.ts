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
 * it on a manifest permission — do not widen this list. Display capture is allowed here
 * because Chromium still checks the `display-capture` web permission before dispatching to
 * the separate setDisplayMediaRequestHandler, which constrains capture to screens only.
 */
export function permissionDecision(permission: string): boolean {
  return permission === 'clipboard-sanitized-write' || permission === 'display-capture'
}

/**
 * Chromium performs a generic `media`/`video` permission check before it emits the
 * `display-capture` request handled by setDisplayMediaRequestHandler. Allowing this check does
 * not grant camera access: getUserMedia still emits a `media` permission request, which
 * permissionDecision intentionally denies.
 */
export function permissionCheckDecision(permission: string, mediaType?: string): boolean {
  return permissionDecision(permission) || (permission === 'media' && mediaType === 'video')
}

/**
 * Electron 43 reports getDisplayMedia from a cross-origin iframe as a `media` request with an
 * empty mediaTypes list before invoking setDisplayMediaRequestHandler. Camera and microphone
 * requests contain `video` and/or `audio`, so they remain denied by the default policy.
 */
export function permissionRequestDecision(permission: string, mediaTypes?: string[]): boolean {
  return permissionDecision(permission)
    || (permission === 'media' && Array.isArray(mediaTypes) && mediaTypes.length === 0)
}

export function registerPermissionHandlers(): void {
  session.defaultSession.setPermissionCheckHandler((_contents, permission, _origin, details) =>
    permissionCheckDecision(permission, details.mediaType))
  session.defaultSession.setPermissionRequestHandler((_contents, permission, callback, details) => {
    const mediaTypes = 'mediaTypes' in details ? details.mediaTypes : undefined
    callback(permissionRequestDecision(permission, mediaTypes))
  })
}
