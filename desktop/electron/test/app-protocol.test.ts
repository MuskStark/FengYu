import { describe, it, expect, vi } from 'vitest'

const captured = vi.hoisted(() => ({
  privileged: null as Array<{ scheme: string }> | null,
  handle: null as
    | ((scheme: string, fn: (request: { url: string; headers?: Headers }) => Response) => void)
    | null,
}))

vi.mock('electron', () => ({
  protocol: {
    registerSchemesAsPrivileged: vi.fn((list: Array<{ scheme: string }>) => {
      captured.privileged = list
    }),
    handle: vi.fn((scheme: string, fn: (request: { url: string; headers?: Headers }) => Response) => {
      if (scheme === 'app') captured.handle = fn
    }),
  },
}))

// readFileSync is exercised through a fake fs surface so the handler can be tested end-to-end.
const files = vi.hoisted(() => new Map<string, string>())
vi.mock('node:fs', () => ({
  readFileSync: vi.fn((path: string) => {
    if (!files.has(path)) throw new Error('ENOENT')
    return files.get(path)
  }),
}))

import { APP_INDEX, handleAppProtocol, registerAppScheme, resolveAppPath } from '../src/window/app-protocol'

describe('app:// shell protocol (M-6)', () => {
  it('registers a standard+secure privileged scheme before use', () => {
    registerAppScheme()
    expect(captured.privileged?.[0]?.scheme).toBe('app')
  })

  it('resolves only paths inside the frontend root, with an index.html fallback', () => {
    const root = '/app/frontend-dist'
    expect(resolveAppPath(root, '/')).toBe(`${root}/index.html`)
    expect(resolveAppPath(root, '/assets/app.js')).toBe(`${root}/assets/app.js`)
    expect(resolveAppPath(root, '/assets/%61pp.js')).toBe(`${root}/assets/app.js`)
    // Traversal in every encoding is rejected.
    expect(resolveAppPath(root, '/../secret.txt')).toBeNull()
    expect(resolveAppPath(root, '/assets/../../secret.txt')).toBeNull()
    expect(resolveAppPath(root, '/%2e%2e/secret.txt')).toBeNull()
    expect(resolveAppPath(root, '/bad%zz')).toBeNull()
  })

  it('resolves a Windows-style frontend root identically on every platform', () => {
    // rc.1 regression: node:path's win32 join/normalize produced backslash paths,
    // so every app:// request escaped the root and 404'd on Windows. URL space is
    // POSIX now — a backslash root must fold to forward slashes on ANY platform.
    const root = 'C:\\app\\frontend-dist'
    expect(resolveAppPath(root, '/')).toBe('C:/app/frontend-dist/index.html')
    expect(resolveAppPath(root, '/assets/app.js')).toBe('C:/app/frontend-dist/assets/app.js')
    expect(resolveAppPath(root, '/assets/../../secret.txt')).toBeNull()
  })

  it('serves files with a MIME type and 404s escapes and misses', () => {
    files.set('/app/frontend-dist/index.html', '<!doctype html>')
    files.set('/app/frontend-dist/assets/app.js', 'console.log(1)')
    handleAppProtocol('/app/frontend-dist')

    const ok = captured.handle?.({ url: APP_INDEX }) as Response
    expect(ok?.status).toBe(200)
    expect(ok?.headers.get('Content-Type')).toContain('text/html')

    const js = captured.handle?.({ url: 'app://shell/assets/app.js' }) as Response
    expect(js?.headers.get('Content-Type')).toContain('text/javascript')

    const traversal = captured.handle?.({ url: 'app://shell/../main.js' }) as Response
    expect(traversal?.status).toBe(404)

    const missing = captured.handle?.({ url: 'app://shell/nope.js' }) as Response
    expect(missing?.status).toBe(404)
  })

  it('serves the index document for history routes on document navigations', () => {
    // BrowserRouter reloads ask for extensionless routes with no backing file — the
    // document navigation (Accept: text/html) must boot the SPA. Dotted route
    // segments (plugin ids) are routes, not assets. Asset-like requests never
    // accept text/html and keep their real 404; traversal never falls back.
    const nav = captured.handle?.({
      url: 'app://shell/flows',
      headers: new Headers({ accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8' }),
    }) as Response
    expect(nav?.status).toBe(200)
    expect(nav?.headers.get('Content-Type')).toContain('text/html')

    const dotted = captured.handle?.({
      url: 'app://shell/plugin/com.example.foo',
      headers: new Headers({ accept: 'text/html' }),
    }) as Response
    expect(dotted?.status).toBe(200)
    expect(dotted?.headers.get('Content-Type')).toContain('text/html')

    const asset = captured.handle?.({
      url: 'app://shell/nope.js',
      headers: new Headers({ accept: '*/*' }),
    }) as Response
    expect(asset?.status).toBe(404)

    // Undecodable paths are rejected by resolveAppPath (target null) and never
    // fall back, whatever the accept header. (Dot segments, literal or %2e-encoded,
    // never get that far — the URL parser folds them into an in-root path first.)
    const bad = captured.handle?.({
      url: 'app://shell/bad%zz',
      headers: new Headers({ accept: 'text/html' }),
    }) as Response
    expect(bad?.status).toBe(404)
  })
})
