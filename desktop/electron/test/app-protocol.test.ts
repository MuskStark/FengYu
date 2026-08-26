import { describe, it, expect, vi } from 'vitest'

const captured = vi.hoisted(() => ({
  privileged: null as Array<{ scheme: string }> | null,
  handle: null as ((scheme: string, fn: (request: { url: string }) => Response) => void) | null,
}))

vi.mock('electron', () => ({
  protocol: {
    registerSchemesAsPrivileged: vi.fn((list: Array<{ scheme: string }>) => {
      captured.privileged = list
    }),
    handle: vi.fn((scheme: string, fn: (request: { url: string }) => Response) => {
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
})
