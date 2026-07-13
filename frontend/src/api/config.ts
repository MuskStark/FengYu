/**
 * Backend base URL + token resolution.
 *
 * In dev, Vite proxies /api and /plugin-ui to the backend, so the base URL is
 * empty (same-origin). In a packaged Tauri build the backend runs on a local
 * port and injects both the base URL and the token onto `window`.
 *
 * Token precedence: window global (Tauri) → Vite env → ''.
 */

export function getApiBase(): string {
  if (typeof window !== 'undefined' && window.__FENGYU_API_BASE__) {
    return window.__FENGYU_API_BASE__
  }
  return import.meta.env.VITE_FENGYU_API_BASE ?? ''
}

export function getToken(): string {
  if (typeof window !== 'undefined' && window.__FENGYU_TOKEN__) {
    return window.__FENGYU_TOKEN__
  }
  return import.meta.env.VITE_FENGYU_TOKEN ?? ''
}

/** Prefix a backend path (e.g. a plugin uiEntry) with the API base URL. */
export function backendUrl(path: string): string {
  const base = getApiBase()
  if (!base) return path
  return base.replace(/\/$/, '') + path
}

/**
 * Resolve sandboxed plugin assets onto a distinct loopback origin.
 *
 * Plugin iframes need `allow-same-origin` so their ESM entrypoints execute, but they must not
 * share the shell's origin because `allow-scripts + allow-same-origin` would otherwise let a
 * third-party plugin reach the parent DOM. A different loopback port (dev/Tauri) or hostname
 * (same-port web serving) preserves that isolation boundary.
 */
export function pluginAssetUrl(path: string): string {
  const base = getApiBase()
  if (typeof window === 'undefined') return base ? base.replace(/\/$/, '') + path : path

  if (base) {
    const url = new URL(path, base.replace(/\/$/, '') + '/')
    if (url.origin !== window.location.origin) return url.toString()
    if (url.hostname === 'localhost' || url.hostname === '127.0.0.1') {
      url.hostname = url.hostname === 'localhost' ? '127.0.0.1' : 'localhost'
      return url.toString()
    }
    return path
  }

  if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
    const backendPort = import.meta.env.VITE_FENGYU_BACKEND_PORT ?? '24056'
    const samePort = window.location.port === backendPort
    const host = samePort
      ? (window.location.hostname === 'localhost' ? '127.0.0.1' : 'localhost')
      : window.location.hostname
    return `${window.location.protocol}//${host}:${backendPort}${path}`
  }
  return path
}
