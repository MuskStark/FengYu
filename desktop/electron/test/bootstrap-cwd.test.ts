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
  const staging = `${newRoot}.migrating`
  const userDataPath = '/Users/a/AppData/Roaming/fengyu-desktop'
  const oldRoot = join(userDataPath, '.fengyu')
  const legacyDb = join(oldRoot, 'data', 'fengyu.mv.db')

  /**
   * STATEFUL in-memory filesystem double with WINDOWS rename semantics: renaming a
   * directory onto an existing target fails — even when the target is empty — which POSIX
   * would allow. A static `exists` mock hid exactly this (the writability probe mkdir's the
   * target itself, so the probe's own leftover made the migration rename fail and the old
   * code misread that failure as "already migrated").
   */
  const windowsFs = (initial?: { dirs?: string[]; files?: string[] }) => {
    const dirs = new Set<string>([...(initial?.dirs ?? []), exeDir, userDataPath])
    const files = new Set<string>(initial?.files ?? [])
    const under = (p: string, dir: string) => p === dir || p.startsWith(`${dir}/`)
    const direct = (dir: string) =>
      [...dirs, ...files]
        .filter(p => p !== dir && p.startsWith(`${dir}/`) && !p.slice(dir.length + 1).includes('/'))
        .map(p => p.slice(dir.length + 1))
    const moveEntries = (from: string, to: string) => {
      for (const p of [...dirs]) if (under(p, from)) { dirs.delete(p); dirs.add(to + p.slice(from.length)) }
      for (const p of [...files]) if (under(p, from)) { files.delete(p); files.add(to + p.slice(from.length)) }
    }
    return {
      dirs,
      files,
      mkdir: vi.fn((dir: string) => {
        dirs.add(dir)
      }),
      exists: vi.fn((p: string) => dirs.has(p) || files.has(p)),
      writeFile: vi.fn((file: string) => {
        files.add(file)
      }),
      unlink: vi.fn((file: string) => {
        files.delete(file)
      }),
      rename: vi.fn((from: string, to: string) => {
        if (dirs.has(to) || files.has(to)) {
          throw new Error('EEXIST: a directory rename cannot replace an existing target (win32)')
        }
        if (!dirs.has(from)) throw new Error('ENOENT')
        moveEntries(from, to)
      }),
      copyTree: vi.fn((from: string, to: string) => {
        if (!dirs.has(from)) throw new Error('ENOENT')
        dirs.add(to)
        for (const p of [...dirs]) if (under(p, from) && p !== from) dirs.add(to + p.slice(from.length))
        for (const p of [...files]) if (under(p, from)) files.add(to + p.slice(from.length))
      }),
      removeTree: vi.fn((p: string) => {
        for (const d of [...dirs]) if (under(d, p)) dirs.delete(d)
        for (const f of [...files]) if (under(f, p)) files.delete(f)
      }),
      listDir: vi.fn((dir: string) => {
        if (!dirs.has(dir)) throw new Error('ENOENT')
        return direct(dir)
      }),
    }
  }

  /** The upgrade scenario: real user data lives in the legacy userData tree, exe dir is empty. */
  const legacyTree = () => windowsFs({ dirs: [oldRoot, join(oldRoot, 'data')], files: [legacyDb] })

  it('P1 regression: the probe must not leave a root behind — the legacy tree really moves', () => {
    const fs = legacyTree()
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
    })
    // With win32 rename semantics the rename onto the probe-mkdir'd empty root FAILS; the
    // old code then saw `exists(newRoot)` and falsely reported 'moved' while the user's
    // database stayed orphaned in userData and the app booted an EMPTY tree (SETUP wizard).
    expect(result.migrated).toEqual({ from: oldRoot, to: newRoot })
    expect(result.directory).toBe(exeDir)
    expect(fs.files.has(join(newRoot, 'data', 'fengyu.mv.db'))).toBe(true)
    expect(fs.exists(oldRoot)).toBe(false)
  })

  it('anchors to the executable directory when it is writable (fresh install, nothing legacy)', () => {
    const fs = windowsFs()
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
    })
    expect(result).toEqual({ changed: true, directory: exeDir, fallbackUsed: false })
    expect(fs.rename).not.toHaveBeenCalled() // nothing legacy to move
    // The probe-created root is cleaned up again: the app anchors the exe dir, and the
    // backend materializes the tree itself.
    expect(fs.dirs.has(newRoot)).toBe(false)
  })

  it('falls back to a recursive copy through a staging sibling when the rename spans volumes (EXDEV)', () => {
    const fs = legacyTree()
    const realRename = fs.rename.getMockImplementation()
    fs.rename.mockImplementation((from: string, to: string) => {
      if (from === oldRoot) throw new Error('EXDEV: cross-device link not permitted')
      realRename(from, to)
    })
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
    })
    expect(result.migrated).toEqual({ from: oldRoot, to: newRoot })
    expect(fs.copyTree).toHaveBeenCalledWith(oldRoot, staging)
    expect(fs.rename).toHaveBeenCalledWith(staging, newRoot)
    expect(fs.removeTree).toHaveBeenCalledWith(oldRoot)
    expect(fs.files.has(join(newRoot, 'data', 'fengyu.mv.db'))).toBe(true)
    expect(fs.dirs.has(staging)).toBe(false) // no staging litter after a successful move
    expect(fs.exists(oldRoot)).toBe(false)
  })

  it('never treats a legacy tree as migrated when the new root is in the way (rename EPERM)', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const fs = legacyTree()
    fs.dirs.add(newRoot) // an (empty) root the pre-clearing cannot remove
    const realRemoveTree = fs.removeTree.getMockImplementation()
    fs.removeTree.mockImplementation((p: string) => {
      if (p === newRoot) throw new Error('EBUSY: the leftover root resists removal')
      realRemoveTree(p)
    })
    fs.rename.mockImplementation(() => {
      throw new Error('EPERM: antivirus holds a handle')
    })
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
    })
    // The old code returned 'moved' on ANY rename failure with exists(newRoot) — orphaning
    // the data. The failure must fall back to the userData anchor with the tree intact.
    expect(result.migrated).toBeUndefined()
    expect(result.directory).toBe(userDataPath)
    expect(fs.files.has(legacyDb)).toBe(true)
  })

  it('clears an EMPTY leftover root (crashed probe) and migrates anyway', () => {
    const fs = legacyTree()
    fs.dirs.add(newRoot) // exists but has no entries — never real data
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
    })
    expect(result.migrated).toEqual({ from: oldRoot, to: newRoot })
    expect(fs.files.has(join(newRoot, 'data', 'fengyu.mv.db'))).toBe(true)
  })

  it('prefers an existing (non-empty) executable-directory root and leaves the legacy tree untouched', () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const fs = legacyTree()
    fs.dirs.add(newRoot)
    fs.dirs.add(join(newRoot, 'logs'))
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
    })
    expect(result.directory).toBe(exeDir)
    expect(result.migrated).toBeUndefined()
    expect(fs.rename).not.toHaveBeenCalled()
    expect(fs.files.has(legacyDb)).toBe(true)
    expect(errSpy).toHaveBeenCalledWith(expect.stringContaining('leaving the legacy tree'))
  })

  it('a crash-safe cross-volume migration: a failed copy cleans ONLY the staging sibling', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const fs = legacyTree()
    fs.rename.mockImplementation(() => {
      throw new Error('EXDEV')
    })
    fs.copyTree.mockImplementation((from: string, to: string) => {
      if (to === staging) throw new Error('EIO: disk error mid-copy')
    })
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
    })
    // The intact legacy tree still lives under userData, so the anchor stays there — and a
    // crash/failure mid-copy must never leave a half-populated NEW root behind.
    expect(result).toEqual({ changed: true, directory: userDataPath, fallbackUsed: false })
    expect(result.migrated).toBeUndefined()
    expect(fs.removeTree).toHaveBeenCalledWith(staging)
    expect(fs.files.has(legacyDb)).toBe(true)
    expect(fs.exists(newRoot)).toBe(false)
  })

  it('retries the cross-volume migration on the next launch after an interrupted copy', () => {
    const fs = legacyTree()
    const realRename = fs.rename.getMockImplementation()
    fs.rename.mockImplementation((from: string, to: string) => {
      if (from === oldRoot) throw new Error('EXDEV: cross-device link not permitted')
      realRename(from, to)
    })
    let attempts = 0
    fs.copyTree.mockImplementation((from: string, to: string) => {
      attempts += 1
      if (attempts === 1) throw new Error('process killed mid-copy')
      fs.dirs.add(to)
    })
    const first = bootstrapWorkingDirectory({
      isPackaged: true, platform: 'win32', exePath, userDataPath,
      cwd: () => '/Windows/System32', chdir, ...fs,
    })
    expect(first.migrated).toBeUndefined()
    expect(first.directory).toBe(userDataPath)
    const second = bootstrapWorkingDirectory({
      isPackaged: true, platform: 'win32', exePath, userDataPath,
      cwd: () => '/Windows/System32', chdir, ...fs,
    })
    expect(second.migrated).toEqual({ from: oldRoot, to: newRoot })
    expect(attempts).toBe(2)
    expect(fs.exists(oldRoot)).toBe(false)
  })

  it('keeps the userData anchor (and never migrates) when the exe directory is unwritable', () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const fs = legacyTree()
    fs.mkdir.mockImplementation((dir: string) => {
      if (dir === newRoot) throw new Error('EPERM')
      fs.dirs.add(dir)
    })
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
    })
    expect(result).toEqual({ changed: true, directory: userDataPath, fallbackUsed: false })
    expect(fs.rename).not.toHaveBeenCalled()
    expect(fs.files.has(legacyDb)).toBe(true)
    expect(errSpy).toHaveBeenCalledWith(expect.stringContaining('is not writable'))
  })

  it('does not anchor to the exe dir when the probe write is denied (empty probe root cleaned up)', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const fs = legacyTree()
    fs.writeFile.mockImplementation(() => {
      throw new Error('EACCES')
    })
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
    })
    expect(result.directory).toBe(userDataPath)
    // The probe-created empty root must not linger and block a future migration.
    expect(fs.dirs.has(newRoot)).toBe(false)
    expect(fs.files.has(legacyDb)).toBe(true)
  })

  it('skips the Windows anchor entirely for a secondary instance (no migration race)', () => {
    const fs = legacyTree()
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      migrationEnabled: false,
      cwd: () => '/Windows/System32',
      chdir,
      ...fs,
    })
    expect(result).toEqual({ changed: true, directory: userDataPath, fallbackUsed: false })
    expect(fs.mkdir).not.toHaveBeenCalledWith(newRoot)
    expect(fs.rename).not.toHaveBeenCalled()
    expect(fs.files.has(legacyDb)).toBe(true)
  })

  it('re-launch with cwd already at the exe dir reports unchanged (no re-chdir)', () => {
    const fs = windowsFs()
    const result = bootstrapWorkingDirectory({
      isPackaged: true,
      platform: 'win32',
      exePath,
      userDataPath,
      cwd: () => exeDir,
      chdir,
      ...fs,
    })
    expect(result).toEqual({ changed: false, directory: exeDir, fallbackUsed: false })
    expect(chdir).not.toHaveBeenCalled()
  })
})
