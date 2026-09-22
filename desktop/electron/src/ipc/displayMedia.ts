import { desktopCapturer, session } from 'electron'

/**
 * Register the `getDisplayMedia` handler for renderer/plugin frames (Electron ≥17 requires an
 * explicit handler; without one every getDisplayMedia call rejects with NotAllowedError —
 * which is exactly what plugin UIs such as FY-QRSync's screen-region recognizer hit).
 *
 * Trust model: PluginView adds `allow="display-capture"` only when a plugin manifest declares
 * the `screen.capture` permission (see PluginView.vue). This handler still serves **screens
 * only** (no window capture) and defaults to the primary screen. On macOS the app also needs
 * the system Screen Recording permission.
 */
export function registerDisplayMediaHandler(): void {
  session.defaultSession.setDisplayMediaRequestHandler((_request, callback) => {
    desktopCapturer
      .getSources({ types: ['screen'], thumbnailSize: { width: 0, height: 0 } })
      .then((sources) => {
        if (sources.length === 0) {
          callback({})
          return
        }
        // Primary screen first: getSources orders by display id, and plugin UIs that need a
        // specific monitor offer in-frame region selection over the full capture anyway.
        callback({ video: sources[0] })
      })
      .catch((error: unknown) => {
        console.error('[display-media] source enumeration failed', error)
        callback({})
      })
  })
}
