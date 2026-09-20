import { describe, it, expect, vi, afterEach } from 'vitest'

vi.mock('electron', () => ({
  app: { isPackaged: false, getPath: vi.fn(() => '/fake/user-data') },
}))

import { bootstrapWorkingDirectory } from '../src/desktop/bootstrap-cwd'
import { join } from 'node:path'

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
      platform: 'win32',
      exePath: '/Apps/Infinia/Infinia.exe',
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
      platform: 'darwin',
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
      platform: 'darwin',
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
      platform: 'darwin',
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
      platform: 'darwin',
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
      platform: 'darwin',
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

describe('bootstrapWorkingDirectory (Windows executable-directory anchor)', () => {
  // Platform-neutral absolute paths: the `win32` gate is a plain platform check, and the
  // module's path math must stay host-separator-consistent (vitest also runs on macOS/Linux).
  const exeDir = '/Apps/Infinia'
  const exePath = `${exeDir}/Infinia.exe`
  const newRoot = join(exeDir, '.fengyu')
  const userDataPath = '/Users/a/AppData/Roaming/fengyu-desktop'
  const oldRoot = join(userDataPath, '.fengyu')

  /** Writable exe-dir fs doubles (mkdir + probe write succeed; nothing pre-exists). */
  const writableFs = () => ({
    mkdir,
    exists: vi.fn(() => false),
    writeFile: vi.fn(),
    unlink: vi.fn(),
  })

  it('anchors to the executable directory when it is writable (fresh install)', () => {
    const rename = vi.fn()
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...writableFs(),
      rename,
      copyTree: vi.fn(),
      removeTree: vi.fn(),
    })
    expect(result).toEqual({ changed: true, directory: exeDir, fallbackUsed: false })
    expect(chdir).toHaveBeenCalledWith(exeDir)
    expect(rename).not.toHaveBeenCalled() // nothing legacy to move
  })

  it('moves a legacy userData .fengyu to the executable directory (same-volume rename)', () => {
    const rename = vi.fn()
    const fs = writableFs()
    fs.exists.mockImplementation((p: string) => p === oldRoot)
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
      rename,
      copyTree: vi.fn(),
      removeTree: vi.fn(),
    })
    expect(result).toEqual({
      changed: true,
      directory: exeDir,
      fallbackUsed: false,
      migrated: { from: oldRoot, to: newRoot },
    })
    expect(rename).toHaveBeenCalledWith(oldRoot, newRoot)
  })

  it('falls back to a recursive copy when the rename spans volumes (EXDEV)', () => {
    const fs = writableFs()
    fs.exists.mockImplementation((p: string) => p === oldRoot)
    const copyTree = vi.fn()
    const removeTree = vi.fn()
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
      rename: vi.fn(() => {
        throw new Error('EXDEV: cross-device link not permitted')
      }),
      copyTree,
      removeTree,
    })
    expect(result.migrated).toEqual({ from: oldRoot, to: newRoot })
    expect(copyTree).toHaveBeenCalledWith(oldRoot, newRoot)
    expect(removeTree).toHaveBeenCalledWith(oldRoot)
  })

  it('keeps the userData anchor (and never migrates) when the exe directory is unwritable', () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const rename = vi.fn()
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath: '/Program Files/Infinia/Infinia.exe',
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      mkdir: (dir) => {
        if (dir.startsWith('/Program Files')) throw new Error('EPERM')
      },
      exists: vi.fn(() => true), // legacy tree present in userData
      writeFile: vi.fn(),
      unlink: vi.fn(),
      rename,
      copyTree: vi.fn(),
      removeTree: vi.fn(),
    })
    expect(result).toEqual({ changed: true, directory: userDataPath, fallbackUsed: false })
    expect(chdir).toHaveBeenCalledWith(userDataPath)
    expect(rename).not.toHaveBeenCalled()
    expect(errSpy).toHaveBeenCalledWith(expect.stringContaining('is not writable'))
  })

  it('prefers an existing executable-directory root and leaves the legacy tree untouched', () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const fs = writableFs()
    fs.exists.mockImplementation((p: string) => p === newRoot || p === oldRoot)
    const rename = vi.fn()
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
      rename,
      copyTree: vi.fn(),
      removeTree: vi.fn(),
    })
    expect(result.directory).toBe(exeDir)
    expect(result.migrated).toBeUndefined()
    expect(rename).not.toHaveBeenCalled()
    expect(errSpy).toHaveBeenCalledWith(expect.stringContaining('leaving the legacy tree'))
  })

  it('falls back to userData when the migration fails (partial copy cleaned up)', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const fs = writableFs()
    fs.exists.mockImplementation((p: string) => p === oldRoot)
    const removeTree = vi.fn()
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
      rename: vi.fn(() => {
        throw new Error('EXDEV')
      }),
      copyTree: vi.fn(() => {
        throw new Error('EIO')
      }),
      removeTree,
    })
    // The intact legacy tree still lives under userData, so the anchor stays there.
    expect(result).toEqual({ changed: true, directory: userDataPath, fallbackUsed: false })
    expect(result.migrated).toBeUndefined()
    expect(removeTree).toHaveBeenCalledWith(newRoot)
  })

  it('does not anchor to the exe dir when the probe write is denied (empty probe root cleaned up)', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const removeTree = vi.fn()
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      mkdir,
      exists: vi.fn(() => false),
      writeFile: vi.fn(() => {
        throw new Error('EACCES')
      }),
      unlink: vi.fn(),
      rename: vi.fn(),
      copyTree: vi.fn(),
      removeTree,
    })
    expect(result.directory).toBe(userDataPath)
    // The probe-created empty root must not linger and block a future migration.
    expect(removeTree).toHaveBeenCalledWith(newRoot)
  })

  it('re-launch with cwd already at the exe dir reports unchanged (no re-chdir)', () => {
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => exeDir,
      chdir,
      ...writableFs(),
      rename: vi.fn(),
      copyTree: vi.fn(),
      removeTree: vi.fn(),
    })
    expect(result).toEqual({ changed: false, directory: exeDir, fallbackUsed: false })
    expect(chdir).not.toHaveBeenCalled()
  })
})
