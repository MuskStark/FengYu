import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Capture the ipcMain handlers the module registers against. vi.mock is hoisted
// above imports, so the capture object must be hoisted too (notification-ipc pattern).
const electron = vi.hoisted(() => ({
  send: vi.fn(),
  openPath: vi.fn(async () => ''),
  writeText: vi.fn(),
  quit: vi.fn(),
  handlers: new Map<string, (event: unknown, ...args: unknown[]) => unknown>(),
  listeners: new Map<string, (event: unknown, ...args: unknown[]) => void>(),
}))

vi.mock('electron', () => ({
  app: { quit: electron.quit },
  clipboard: { writeText: electron.writeText },
  shell: { openPath: electron.openPath },
  ipcMain: {
    handle: vi.fn((channel: string, handler: (event: unknown, ...args: unknown[]) => unknown) => {
      electron.handlers.set(channel, handler)
    }),
    on: vi.fn((channel: string, handler: (event: unknown, ...args: unknown[]) => void) => {
      electron.listeners.set(channel, handler)
    }),
  },
}))

import {
  ACK_FALLBACK_MS,
  classifyBootFailure,
  registerBootIpc,
  tagBootFailure,
  type BootState,
} from '../src/ipc/boot'

const info = vi.fn()
const onRetry = vi.fn()
const onAckTimeout = vi.fn()
const fakeWindow = { isDestroyed: () => false, webContents: { send: electron.send } }
const noWindow = () => null

function boot(opts: Partial<Parameters<typeof registerBootIpc>[0]> = {}) {
  return registerBootIpc({
    logger: { info },
    getWindow: () => fakeWindow as never,
    logsDir: () => '/tmp/fengyu-logs',
    onRetry,
    onAckTimeout,
    ...opts,
  })
}

function handle<T>(channel: string, ...args: unknown[]): T {
  const handler = electron.handlers.get(channel)
  if (!handler) throw new Error(`no handler registered for ${channel}`)
  return handler({} as never, ...args) as T
}

const failed: BootState = { phase: 'failed', reason: 'backend-exited', exitCode: 1, detail: 'boom' }

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  electron.handlers.clear()
  electron.listeners.clear()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('registerBootIpc — boot:state push + ack fallback', () => {
  it('pushes state to the window and arms the fallback timer until acked', () => {
    const api = boot()
    api.pushBootState(failed)
    expect(electron.send).toHaveBeenCalledWith('boot:state', failed)

    vi.advanceTimersByTime(ACK_FALLBACK_MS - 1)
    expect(onAckTimeout).not.toHaveBeenCalled()
    handle('boot:failure-visible')
    vi.advanceTimersByTime(ACK_FALLBACK_MS)
    expect(onAckTimeout).not.toHaveBeenCalled()
    expect(info).toHaveBeenCalled()
  })

  it('falls back to onAckTimeout when the renderer never acks', () => {
    const api = boot()
    api.pushBootState(failed)
    vi.advanceTimersByTime(ACK_FALLBACK_MS)
    expect(onAckTimeout).toHaveBeenCalledWith(failed)
  })

  it('disarms the timer when a later push leaves the failed phase (retry started)', () => {
    const api = boot()
    api.pushBootState(failed)
    api.pushBootState({ phase: 'booting', attempt: 2 })
    vi.advanceTimersByTime(ACK_FALLBACK_MS * 2)
    expect(onAckTimeout).not.toHaveBeenCalled()
  })

  it('re-arms the timer on a second failure', () => {
    const api = boot()
    api.pushBootState(failed)
    handle('boot:failure-visible')
    api.pushBootState({ ...failed, attempt: 2 })
    vi.advanceTimersByTime(ACK_FALLBACK_MS)
    expect(onAckTimeout).toHaveBeenCalledTimes(1)
  })

  it('never throws when the window is gone', () => {
    const api = boot({ getWindow: noWindow })
    expect(() => api.pushBootState(failed)).not.toThrow()
    // The fallback must still arm — a dead renderer can never ack.
    vi.advanceTimersByTime(ACK_FALLBACK_MS)
    expect(onAckTimeout).toHaveBeenCalled()
  })
})

describe('registerBootIpc — boot:get-state pull', () => {
  it('returns the last pushed state (null before any push)', async () => {
    const api = boot()
    expect(await handle<Promise<unknown>>('boot:get-state')).toBeNull()
    api.pushBootState(failed)
    expect(await handle<Promise<unknown>>('boot:get-state')).toEqual(failed)
  })
})

describe('registerBootIpc — boot:retry guard', () => {
  it('runs onRetry once at a time and reports failures', async () => {
    boot()
    let rejectFirst: (reason: Error) => void = () => {}
    onRetry.mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          rejectFirst = reject
        }),
    )
    const first = handle<Promise<{ ok: boolean; error?: string }>>('boot:retry')
    // While the first retry is still pending, a second call is rejected without
    // invoking onRetry again.
    const second = await handle<Promise<{ ok: boolean; error?: string }>>('boot:retry')
    expect(second.ok).toBe(false)
    expect(onRetry).toHaveBeenCalledTimes(1)
    rejectFirst(new Error('respawn failed'))
    const settled = await first
    expect(settled).toEqual({ ok: false, error: 'respawn failed' })
  })
})

describe('registerBootIpc — open-logs / clipboard / quit', () => {
  it('opens the logs dir and reports openPath errors as null', async () => {
    boot()
    electron.openPath.mockResolvedValueOnce('cannot open')
    const bad = await handle<Promise<string | null>>('boot:open-logs')
    expect(bad).toBeNull()
    electron.openPath.mockResolvedValueOnce('')
    const good = await handle<Promise<string | null>>('boot:open-logs')
    expect(good).toBe('/tmp/fengyu-logs')
  })

  it('writes clipboard text defensively and quits on boot:quit', async () => {
    boot()
    await handle<Promise<boolean>>('clipboard:write-text', 'diagnostics')
    expect(electron.writeText).toHaveBeenCalledWith('diagnostics')
    electron.listeners.get('boot:quit')!({} as never)
    expect(electron.quit).toHaveBeenCalled()
  })
})

describe('boot failure classification', () => {
  it('round-trips tags and defaults untagged errors to health-deadline', () => {
    const err = tagBootFailure(new Error('deadline'), 'setup-probe-failed')
    expect(classifyBootFailure(err).reason).toBe('setup-probe-failed')
    const exited = Object.assign(new Error('backend exited'), { exitCode: 137 })
    const plain = classifyBootFailure(exited)
    expect(plain.reason).toBe('health-deadline')
    expect(plain.exitCode).toBe(137)
    expect(classifyBootFailure(new Error('x')).exitCode).toBeNull()
  })

  it('keeps the first tag — a coarse re-tag must not mask the precise reason', () => {
    const err = tagBootFailure(tagBootFailure(new Error('exited'), 'backend-exited'), 'retry-spawn-failed')
    expect(classifyBootFailure(err).reason).toBe('backend-exited')
  })
})
