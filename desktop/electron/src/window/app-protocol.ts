import { protocol } from 'electron'
import { readFileSync } from 'node:fs'
import { extname, normalize } from 'node:path'
import { join as posixJoin, normalize as posixNormalize } from 'node:path/posix'

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
 *
 * URL space is always POSIX: the (possibly Windows) root is folded to forward slashes so the
 * containment check compares identically on every platform — node:path's join/normalize on
 * win32 produce backslash paths, which made every request escape the root and 404 on Windows.
 * readFileSync happily accepts forward-slash paths on win32, so no back-conversion is needed.
 */
export function resolveAppPath(root: string, rawPathname: string): string | null {
  const normalizedRoot = normalize(root).split(/[\\/]+/).join('/')
  let pathname = rawPathname
  try {
    pathname = decodeURIComponent(rawPathname)
  } catch {
    return null
  }
  if (pathname.includes('\0')) return null
  const segments = pathname.split(/[\\/]+/).filter((part) => part.length > 0 && part !== '.')
  if (segments.some((part) => part === '..')) return null
  const resolved = posixNormalize(posixJoin(normalizedRoot, ...segments))
  if (resolved !== normalizedRoot && !resolved.startsWith(normalizedRoot + '/')) return null
  return segments.length === 0 ? normalizedRoot + '/index.html' : resolved
}

export function handleAppProtocol(frontendDist: string): void {
  protocol.handle(APP_SCHEME, (request) => {
    let target: string | null = null
    try {
      const { pathname } = new URL(request.url)
      target = resolveAppPath(frontendDist, pathname)
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
