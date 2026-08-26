import { protocol } from 'electron'
import { readFileSync } from 'node:fs'
import { extname, join, normalize, sep } from 'node:path'

/**
 * app:// — the packaged shell's loader scheme (M-6).
 *
 * The packaged build used to load the SPA via `loadFile` (a file:// URL). Electron's
 * `webRequest.onHeadersReceived` never fires for file:// requests, and a meta CSP is
 * unusable there too (file:// pages have an OPAQUE origin, so no source expression
 * matches, not even 'self') — the production renderer effectively had NO CSP at all.
 *
 * Loading `app://shell/index.html` instead gives the SPA a real, non-opaque origin:
 * the CSP meta tag baked by the frontend build (webReleaseCsp in vite.config.ts)
 * becomes honorable, and the origin is stable for the backend's CORS allowlist.
 *
 * The scheme is registered as `standard` + `secure` (must happen before app ready);
 * files are read through the synchronous fs layer so inside-asar paths
 * (frontend-dist/** is packed into app.asar) resolve transparently.
 */
export const APP_SCHEME = 'app'
export const APP_HOST = 'shell'
export const APP_INDEX = 'app://shell/index.html'

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.wasm': 'application/wasm',
  '.txt': 'text/plain; charset=utf-8',
}

export function registerAppScheme(): void {
  protocol.registerSchemesAsPrivileged([
    { scheme: APP_SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } },
  ])
}

/**
 * Maps an app:// request path onto a file inside `root`, or null when the path escapes the
 * root (traversal), points at a directory, or cannot be decoded. Pure — unit-tested.
 */
export function resolveAppPath(root: string, rawPathname: string): string | null {
  let pathname = rawPathname
  try {
    pathname = decodeURIComponent(rawPathname)
  } catch {
    return null
  }
  if (pathname.includes('\0')) return null
  const segments = pathname.split(/[\\/]+/).filter((part) => part.length > 0 && part !== '.')
  if (segments.some((part) => part === '..')) return null
  const resolved = normalize(join(root, ...segments))
  if (resolved !== root && !resolved.startsWith(root + sep)) return null
  return segments.length === 0 ? join(root, 'index.html') : resolved
}

export function handleAppProtocol(frontendDist: string): void {
  const root = normalize(frontendDist)
  protocol.handle(APP_SCHEME, (request) => {
    let target: string | null = null
    try {
      const { pathname } = new URL(request.url)
      target = resolveAppPath(root, pathname)
    } catch {
      target = null
    }
    if (target === null) return new Response('not found', { status: 404 })
    try {
      const body = readFileSync(target)
      const type = MIME[extname(target).toLowerCase()] ?? 'application/octet-stream'
      return new Response(body, { headers: { 'Content-Type': type } })
    } catch {
      return new Response('not found', { status: 404 })
    }
  })
}
