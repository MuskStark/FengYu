/**
 * The single HTTP transport for every domain service — ported from the legacy
 * `api/client.ts` axios instance (token header, Accept-Language, backend error
 * text extraction, auth-expired event) with two changes:
 *
 * - apiBase/token come from the platform layer, not `window.fengyu` sniffing;
 * - the UI locale is injected via {@link setHttpLocaleProvider} at bootstrap so
 *   this module stays free of any i18n framework import.
 */
import axios, { type AxiosInstance } from 'axios'
import { getPlatform } from '@/platform'

export const http: AxiosInstance = axios.create({
  baseURL: getPlatform().apiBase(),
  headers: { 'Content-Type': 'application/json' },
})

type LocaleProvider = () => string
let localeProvider: LocaleProvider = () => 'en'

/** Bootstrap wires the app's i18n language here (e.g. `() => i18n.language`). */
export function setHttpLocaleProvider(provider: LocaleProvider): void {
  localeProvider = provider
}

// Attach the FengYu token to every request except /api/health (readiness probes must stay
// header-free). The setup wizard rides the same launch token as everything else once auth is
// configured; the header is simply ignored when auth is off (first browser-dev launch).
http.interceptors.request.use((config) => {
  const url = config.url ?? ''
  if (!url.includes('/api/health')) {
    const token = getPlatform().token()
    if (token) {
      config.headers.set('X-FengYu-Token', token)
    }
  }
  config.headers.set('Accept-Language', localeProvider() || 'en')
  return config
})

// Carry the backend's own error text ({"error": "..."} / {"message": "..."}) on the
// AxiosError message so catch blocks can surface the real failure reason (e.g. the
// update channel's 503 body) instead of axios's generic "Request failed with status
// code N". The error object itself is rejected unchanged — isAxiosError callers
// (router 404 handling) keep working.
http.interceptors.response.use(undefined, (error) => {
  const data = error?.response?.data
  let message: string | undefined
  if (data && typeof data === 'object') {
    if (typeof data.error === 'string' && data.error) message = data.error
    else if (typeof data.message === 'string' && data.message) message = data.message
  }
  if (message) error.message = message
  notifyAuthExpired(error)
  return Promise.reject(error)
})

/**
 * Global custom event fired when the backend rejects our token (HTTP 401): the
 * credentials died — typically a backend restart minted a new token while this UI
 * kept the old one. The app shell listens and offers a reload (the desktop preload
 * re-reads the fresh token on every page load). The event is throttled here so a
 * burst of failing requests paints one banner, not one per request.
 */
export const AUTH_EXPIRED_EVENT = 'fengyu:auth-expired'
const AUTH_EXPIRED_COOLDOWN_MS = 10_000
let lastAuthExpiredAt = 0

function notifyAuthExpired(error: unknown): void {
  const status = (error as { response?: { status?: number } } | null)?.response?.status
  if (status !== 401) return
  const url = (error as { config?: { url?: string } } | null)?.config?.url ?? ''
  // Setup-mode surface: token-bypassed by design (the wizard runs before auth exists),
  // and /api/account/** 401s mean the optional cloud session dropped — a flow the
  // account view already handles by falling back to the local account. Neither is
  // a dead backend credential, so neither may raise the reload banner.
  if (url.includes('/api/setup') || url.includes('/api/account')) return
  // The wizard route itself rides whatever token existed at launch; a 401 there is
  // surfaced by the wizard's own error handling, not a "credentials expired" episode.
  // The desktop shell uses hash history, the browser build path history — check both.
  if (typeof window !== 'undefined'
    && (window.location.pathname === '/setup' || window.location.hash.startsWith('#/setup'))) return
  const now = Date.now()
  if (now - lastAuthExpiredAt < AUTH_EXPIRED_COOLDOWN_MS) return
  lastAuthExpiredAt = now
  window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT))
}
