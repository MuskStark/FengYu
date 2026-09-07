import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * P3: the dev Vite child must never inherit the main process's auth tokens. On Linux
 * `/proc/<pid>/environ` is world-readable, so a full `{...process.env}` passthrough would
 * publish the per-launch backend API token (and the sidecar token) to every local user
 * for the shell's whole lifetime.
 */

const spawnMock = vi.hoisted(() => vi.fn())

vi.mock('node:child_process', () => ({ spawn: spawnMock }))
vi.mock('node:fs', () => ({ existsSync: vi.fn(() => true) }))
vi.mock('node:net', () => ({
  // Never "listening": the poll loop is irrelevant to these tests; the start deadline
  // rejects quickly and the spawn arguments have already been captured.
  connect: vi.fn(() => {
    const handlers: Record<string, () => void> = {}
    const sock = {
      once: (evt: string, fn: () => void) => {
        handlers[evt] = fn
      },
      destroy: () => undefined,
    }
    setImmediate(() => handlers.error?.())
    return sock
  }),
}))

import { childEnvWithoutTokens, startDevFrontend } from '../src/desktop/dev-frontend'

function fakeChild() {
  return {
    stdout: { on: vi.fn() },
    stderr: { on: vi.fn() },
    once: vi.fn(),
    on: vi.fn(),
    off: vi.fn(),
    kill: vi.fn(),
    killed: false,
    pid: 4321,
  }
}

beforeEach(() => {
  spawnMock.mockReset()
  spawnMock.mockImplementation(() => fakeChild())
})

describe('childEnvWithoutTokens', () => {
  it('strips FENGYU_TOKEN and FENGYU_AUTH_TOKEN but keeps everything else', () => {
    const env = {
      PATH: '/usr/bin',
      HOME: '/home/dev',
      FENGYU_TOKEN: 'secret-api-token',
      FENGYU_AUTH_TOKEN: 'secret-sidecar-token',
      LANG: 'en_US.UTF-8',
    }
    const childEnv = childEnvWithoutTokens(env)
    expect(childEnv).toEqual({ PATH: '/usr/bin', HOME: '/home/dev', LANG: 'en_US.UTF-8' })
  })

  it('does not mutate the input environment', () => {
    const env = { FENGYU_TOKEN: 'secret', KEEP: 'me' }
    childEnvWithoutTokens(env)
    expect(env).toEqual({ FENGYU_TOKEN: 'secret', KEEP: 'me' })
  })

  it('leaves a token-free environment untouched', () => {
    const env = { PATH: '/usr/bin' }
    expect(childEnvWithoutTokens(env)).toEqual({ PATH: '/usr/bin' })
  })
})

describe('startDevFrontend spawn environment', () => {
  it('spawns Vite with the token-stripped environment', async () => {
    process.env.FENGYU_TOKEN = 'secret-api-token'
    process.env.FENGYU_AUTH_TOKEN = 'secret-sidecar-token'
    const previousPath = process.env.PATH
    try {
      // Rejects on the (immediate) deadline — but the spawn arguments are already asserted.
      await expect(
        startDevFrontend({ repoRoot: '/repo', deadlineMs: 5, log: () => {}, isQuitting: () => false }),
      ).rejects.toThrow(/did not bind/)
      expect(spawnMock).toHaveBeenCalledTimes(1)
      const spawnOptions = spawnMock.mock.calls[0][2] as { env: NodeJS.ProcessEnv }
      expect(spawnOptions.env.FENGYU_TOKEN).toBeUndefined()
      expect(spawnOptions.env.FENGYU_AUTH_TOKEN).toBeUndefined()
      expect(spawnOptions.env.PATH).toBe(previousPath)
    } finally {
      delete process.env.FENGYU_TOKEN
      delete process.env.FENGYU_AUTH_TOKEN
    }
  })
})
