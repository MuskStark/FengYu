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
  if (typeof window !== 'undefined' && window.__ZHIFLOW_API_BASE__) {
    return window.__ZHIFLOW_API_BASE__
  }
  return import.meta.env.VITE_ZHIFLOW_API_BASE ?? ''
}

export function getToken(): string {
  if (typeof window !== 'undefined' && window.__ZHIFLOW_TOKEN__) {
    return window.__ZHIFLOW_TOKEN__
  }
  return import.meta.env.VITE_ZHIFLOW_TOKEN ?? ''
}

/** Prefix a backend path (e.g. a plugin uiEntry) with the API base URL. */
export function backendUrl(path: string): string {
  const base = getApiBase()
  if (!base) return path
  return base.replace(/\/$/, '') + path
}
