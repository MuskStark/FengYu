import { describe, it, expect, vi, afterEach } from 'vitest'

/**
 * P1-9: the logger's directory creation must never crash startup. A packaged launch with
 * an unwritable runtime root (cwd=/ on a read-only volume) degrades to <tmpdir>/fengyu-logs
 * with a stderr warning; electron-log then writes there instead of throwing.
 */

const logMock = vi.hoisted(() => ({
  info: vi.fn(),
  error: vi.fn(),
  warn: vi.fn(),
}))

vi.mock('electron-log', () => ({
  default: {
    info: logMock.info,
    error: logMock.error,
    warn: logMock.warn,
    transports: {
      file: { resolvePathFn: null as unknown as () => string, maxSize: 0, level: '' },
      console: { level: '' },
    },
  },
}))

const mkdirMock = vi.fn()

vi.mock('node:fs', () => ({
  // logger.ts also imports appendFileSync (the backend tee) — provide a benign stub.
  appendFileSync: vi.fn(),
  mkdirSync: (...args: unknown[]) => mkdirMock(...args),
}))

import { initLogger, resolveLogDir } from '../src/desktop/logger'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

afterEach(() => {
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

describe('resolveLogDir (P1-9 unwritable runtime root)', () => {
  it('creates and returns the runtime log directory on the happy path', () => {
    mkdirMock.mockImplementation(() => {})
    const dir = resolveLogDir()
    expect(dir).toBe(join(resolve(process.cwd(), '.fengyu'), 'logs'))
    expect(mkdirMock).toHaveBeenCalledWith(dir, { recursive: true })
  })

  it('falls back to <tmpdir>/fengyu-logs and warns on stderr when the runtime root is unwritable', () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    mkdirMock.mockImplementation((dir: string) => {
      if (!dir.includes(tmpdir())) throw new Error('EROFS: read-only file system')
    })
    const dir = resolveLogDir()
    expect(dir).toBe(join(tmpdir(), 'fengyu-logs'))
    expect(mkdirMock).toHaveBeenCalledWith(join(tmpdir(), 'fengyu-logs'), { recursive: true })
    expect(errSpy).toHaveBeenCalledWith(expect.stringContaining('logs fall back to'))
  })

  it('keeps the primary path (and never throws) when even tmpdir is unwritable', () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    mkdirMock.mockImplementation(() => {
      throw new Error('EACCES')
    })
    const primary = join(resolve(process.cwd(), '.fengyu'), 'logs')
    expect(resolveLogDir()).toBe(primary)
    expect(errSpy).toHaveBeenCalledWith(expect.stringContaining(primary))
  })
})

describe('initLogger (P1-9 never throws)', () => {
  it('points electron-log at the tmpdir fallback when the runtime root is unwritable', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    mkdirMock.mockImplementation((dir: string) => {
      if (!dir.includes(tmpdir())) throw new Error('EROFS')
    })
    const log = (await import('electron-log')).default
    let init: ReturnType<typeof initLogger>
    expect(() => {
      init = initLogger()
    }).not.toThrow()
    const resolvePathFn = (log.transports.file as unknown as { resolvePathFn: () => string }).resolvePathFn
    expect(resolvePathFn()).toBe(join(tmpdir(), 'fengyu-logs', 'desktop.log'))
    // The returned backend tee must also never throw into a caller.
    expect(() => init!.backendLine('FENGYU_PORT=24056')).not.toThrow()
    expect(logMock.info).toHaveBeenCalledWith('[desktop] logger initialized')
  })
})
