import { describe, it, expect, vi, afterEach } from 'vitest'

vi.mock('electron', () => ({
  app: { isPackaged: false, getPath: vi.fn(() => '/fake/user-data') },
}))

import { bootstrapWorkingDirectory } from '../src/desktop/bootstrap-cwd'

const realCwd = process.cwd()
const chdir = vi.fn()
const mkdir = vi.fn()

afterEach(() => {
  vi.restoreAllMocks()
  // Never leak a test chdir into the process (the injected chdir default is real
  // process.chdir only when a test omits it — restore defensively regardless).
  process.chdir(realCwd)
  vi.clearAllMocks()
})

describe('bootstrapWorkingDirectory (P1-9 packaged cwd anchor)', () => {
  it('is a no-op for dev runs (never packaged)', () => {
    const result = bootstrapWorkingDirectory({
      isPackaged: false,
      userDataPath: '/anchor',
      cwd: () => '/some/dir',
      chdir,
      mkdir,
    })
    expect(result).toEqual({ changed: false, directory: '/some/dir', fallbackUsed: false })
    expect(chdir).not.toHaveBeenCalled()
    expect(mkdir).not.toHaveBeenCalled()
  })

  it('packaged launch chdirs to userData, creating the directory first', () => {
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      userDataPath: '/Users/a/Library/Application Support/Infinia',
      cwd: () => '/',
      chdir,
      mkdir,
    })
    expect(result).toEqual({
      changed: true,
      directory: '/Users/a/Library/Application Support/Infinia',
      fallbackUsed: false,
    })
    // chdir requires the target to exist: userData must be materialized first.
    expect(mkdir).toHaveBeenCalledWith('/Users/a/Library/Application Support/Infinia')
    expect(chdir).toHaveBeenCalledWith('/Users/a/Library/Application Support/Infinia')
  })

  it('does nothing when the cwd already IS userData (re-launch in place)', () => {
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      userDataPath: '/anchor/.',
      cwd: () => '/anchor',
      chdir,
      mkdir,
    })
    expect(result.changed).toBe(false)
    expect(chdir).not.toHaveBeenCalled()
  })

  it('falls back to the OS temp directory when userData cannot be anchored', () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      userDataPath: '/read-only/user-data',
      fallbackPath: '/tmp',
      cwd: () => '/',
      chdir: (dir) => {
        if (dir === '/read-only/user-data') throw new Error('EROFS: read-only file system')
        chdir(dir)
      },
      mkdir,
    })
    expect(result).toEqual({ changed: true, directory: '/tmp', fallbackUsed: true })
    expect(chdir).toHaveBeenCalledWith('/tmp')
    expect(errSpy).toHaveBeenCalledWith(expect.stringContaining('falling back to /tmp'))
  })

  it('keeps the original cwd (and never throws) when nothing can be anchored', () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      userDataPath: '/bad/one',
      fallbackPath: '/bad/two',
      cwd: () => '/',
      chdir: () => {
        throw new Error('EPERM')
      },
      mkdir,
    })
    expect(result).toEqual({ changed: false, directory: '/', fallbackUsed: false })
    expect(errSpy).toHaveBeenCalledWith(expect.stringContaining('keeping /'))
  })

  it('treats a mkdir failure at userData as an anchor failure (falls through to tmpdir)', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      userDataPath: '/no-permission/user-data',
      fallbackPath: '/tmp',
      cwd: () => '/',
      chdir,
      mkdir: (dir) => {
        if (dir === '/no-permission/user-data') throw new Error('EACCES')
      },
    })
    expect(result).toEqual({ changed: true, directory: '/tmp', fallbackUsed: true })
  })
})
