import { desktopCapturer, session } from 'electron'

/**
 * Register the `getDisplayMedia` handler for renderer/plugin frames (Electron ≥17 requires an
 * explicit handler; without one every getDisplayMedia call rejects with NotAllowedError —
 * which is exactly what plugin UIs such as FY-QRSync's screen-region recognizer hit).
 *
 * Trust model: installed plugins are already user-authorized at install time (the marketplace
 * gate), and the iframe side must additionally opt in via `allow="display-capture"` on the
 * plugin frame (see PluginView.vue). There is no manifest-level capture permission yet —
 * until one exists this serves **screens only** (no window capture) and defaults to the
 * primary screen. On macOS the app still needs the system Screen Recording permission.
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
      .catch(() => callback({}))
  })
}
