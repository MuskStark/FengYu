import { afterEach, describe, expect, it, vi } from 'vitest'
import { createDesktopPlatform } from './desktop'

/** Minimal preload-bridge stub: only the fields createDesktopPlatform touches. */
function bridgeStub(overrides: Record<string, unknown> = {}) {
  return {
    platform: 'darwin',
    apiBase: () => '',
    token: () => '',
    initialTheme: () => 'dark',
    setTheme: () => {},
    pickFile: () => Promise.resolve(null),
    pickDirectory: () => Promise.resolve(null),
    onEndpoint: () => {},
    ...overrides,
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('desktop endpoint resolution (push wins over a late pull)', () => {
  it('a pull resolving AFTER a push must not roll the endpoint back', async () => {
    let push: ((state: { apiBase: string; token: string }) => void) | null = null
    let resolvePull: (state: { apiBase: string; token: string } | null) => void = () => {}
    const pull = new Promise<{ apiBase: string; token: string } | null>(resolve_ => {
      resolvePull = resolve_
    })
    const windowStub = {
      fengyu: bridgeStub({
        onEndpoint: (cb: (state: { apiBase: string; token: string }) => void) => {
          push = cb
        },
        getEndpoint: () => pull,
      }),
    }
    vi.stubGlobal('window', windowStub)

    const platform = createDesktopPlatform()
    expect(platform.apiBase()).toBe('') // snapshot empty before the spawn resolves

    push!({ apiBase: 'http://127.0.0.1:24056', token: 'fresh' })
    // The pull was sent BEFORE the push; it resolves with an OLDER snapshot now.
    resolvePull({ apiBase: 'http://127.0.0.1:9999', token: 'stale' })
    await Promise.resolve()
    await Promise.resolve()

    expect(platform.apiBase()).toBe('http://127.0.0.1:24056')
    expect(platform.token()).toBe('fresh')
  })

  it('adopts the pull when no push has landed (older preloads / post-boot reloads)', async () => {
    const windowStub = {
      fengyu: bridgeStub({
        onEndpoint: () => {},
        getEndpoint: () => Promise.resolve({ apiBase: 'http://127.0.0.1:24056', token: 'snapshot' }),
      }),
    }
    vi.stubGlobal('window', windowStub)
    const platform = createDesktopPlatform()
    await Promise.resolve()
    await Promise.resolve()
    expect(platform.apiBase()).toBe('http://127.0.0.1:24056')
    expect(platform.token()).toBe('snapshot')
  })
})
