/**
 * Backend base URL + token resolution.
 *
 * In dev, Vite proxies /api and /plugin-ui to the backend, so the base URL is
 * empty (same-origin). In the Electron desktop shell the backend runs on a
 * loopback port and the preload exposes `apiBase()`/`token()` snapshots on
 * `window.fengyu` (see electron-env.d.ts).
 *
 * Token precedence: window.fengyu (Electron) → Vite env → ''.
 */

export function getApiBase(): string {
  if (typeof window !== 'undefined' && window.fengyu) {
    return window.fengyu.apiBase()
  }
  return import.meta.env.VITE_FENGYU_API_BASE ?? ''
}

export function getToken(): string {
  if (typeof window !== 'undefined' && window.fengyu) {
    return window.fengyu.token()
  }
  return import.meta.env.VITE_FENGYU_TOKEN ?? ''
}

/** Prefix a backend path (e.g. a plugin uiEntry) with the API base URL. */
export function backendUrl(path: string): string {
  const base = getApiBase()
  if (!base) return path
  return base.replace(/\/$/, '') + path
}

interface ResolvedPluginAsset {
  url: string
  /**
   * Whether the URL sits on an origin distinct from the shell's. When false, a sandboxed iframe
   * hosting this asset must NOT be granted `allow-same-origin` — the safe degradation is an
   * opaque origin for the third-party plugin JS (see PluginView.vue).
   */
  distinctOrigin: boolean
}

function resolvePluginAsset(path: string): ResolvedPluginAsset {
  const base = getApiBase()
  if (typeof window === 'undefined') {
    return base
      ? { url: base.replace(/\/$/, '') + path, distinctOrigin: true }
      : { url: path, distinctOrigin: false }
  }

  if (base) {
    const url = new URL(path, base.replace(/\/$/, '') + '/')
    if (url.origin !== window.location.origin) return { url: url.toString(), distinctOrigin: true }
    if (url.hostname === 'localhost' || url.hostname === '127.0.0.1') {
      url.hostname = url.hostname === 'localhost' ? '127.0.0.1' : 'localhost'
      return { url: url.toString(), distinctOrigin: true }
    }
    // Same origin with no loopback hostname to swap (e.g. a shared-origin web deployment):
    // return the absolute URL but flag it — the caller must drop allow-same-origin.
    return { url: url.toString(), distinctOrigin: false }
  }

  if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
    const backendPort = import.meta.env.VITE_FENGYU_BACKEND_PORT ?? '24056'
    const samePort = window.location.port === backendPort
    const host = samePort
      ? (window.location.hostname === 'localhost' ? '127.0.0.1' : 'localhost')
      : window.location.hostname
    return { url: `${window.location.protocol}//${host}:${backendPort}${path}`, distinctOrigin: true }
  }
  return { url: new URL(path, window.location.href).toString(), distinctOrigin: false }
}

/**
 * Prefix a plugin asset path (e.g. a uiEntry) with an origin that is distinct from the
 * shell when possible. The URL carries `?shellOrigin=<this shell's origin>`: the plugin
 * SDK inside the frame pins its postMessage bridge to exactly that origin, and refuses to
 * bridge when the parameter is absent. That closes the accidental-bridging hole left by
 * the old wildcard (`*`) default — it is NOT a boundary against a hostile embedder:
 * anyone who controls the iframe src can append a forged `shellOrigin` and address the
 * frame. A real defense needs server-side hardening; this parameter is a guard rail,
 * not authentication.
 */
export function pluginAssetUrl(path: string): string {
  const resolved = resolvePluginAsset(path)
  try {
    const url = new URL(resolved.url,
      typeof window === 'undefined' ? 'http://127.0.0.1' : window.location.href)
    if (typeof window !== 'undefined') {
      url.searchParams.set('shellOrigin', window.location.origin)
    }
    return url.toString()
  } catch {
    return resolved.url
  }
}

/**
 * Whether {@link pluginAssetUrl} puts {@link path} on an origin distinct from the shell's.
 * Consumers embedding the asset in a sandboxed iframe must omit `allow-same-origin` when
 * this returns false, otherwise `allow-scripts + allow-same-origin` on the shell origin
 * lets third-party plugin JS reach the parent DOM and the host bridge.
 */
export function pluginAssetIsolated(path: string): boolean {
  return resolvePluginAsset(path).distinctOrigin
}
