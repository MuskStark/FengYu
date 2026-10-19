import { desktopCapturer, session } from 'electron'

/**
 * Register the `getDisplayMedia` handler for renderer/plugin frames (Electron ≥17 requires an
 * explicit handler; without one every getDisplayMedia call rejects with NotAllowedError —
 * which is exactly what plugin UIs such as FY-QRSync's screen-region recognizer hit).
 *
 * Trust model: the React plugin iframe host adds `allow="display-capture"` only when a plugin
 * manifest declares the `screen.capture` permission (see PluginPage.tsx), and only NESTED
 * frames are served — a request from the top-level shell document is denied (audit P2): the
 * SPA itself never calls getDisplayMedia, so a top-frame request means compromised shell
 * content attempting a zero-interaction full-screen capture. This handler still serves
 * **screens only** (no window capture) and defaults to the primary screen. On macOS the
 * app also needs the system Screen Recording permission.
 */
export function registerDisplayMediaHandler(): void {
  session.defaultSession.setDisplayMediaRequestHandler((request, callback) => {
    const frame = request.frame
    // frame.top === frame marks the shell's own document; frame == null means the
    // requesting frame navigated away or died — deny both, fail-closed.
    if (!frame || frame.top === frame) {
      console.warn('[display-media] denied a capture request from the top-level document '
        + `(origin ${request.securityOrigin}); only plugin UI frames may capture`)
      callback({})
      return
    }
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
