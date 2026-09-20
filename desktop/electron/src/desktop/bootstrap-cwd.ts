import { app } from 'electron'
import { cpSync, existsSync, mkdirSync, renameSync, rmSync, unlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'

/**
 * Re-anchor the process working directory for PACKAGED builds before any module derives a
 * path from `process.cwd()` (P1-9).
 *
 * A packaged app launched from Finder/Dock (macOS .app) or a Linux menu/.desktop entry
 * starts with cwd `/` — the read-only root volume — so `runtimeRoot()`
 * (`<cwd>/.fengyu`: logs, config, backend cwd, update staging) is unwritable and the
 * logger's mkdir would crash the process during module initialization. Deviating cwd
 * launches (terminal, `yarn dev`) always have a writable cwd and never take this path.
 *
 * Anchor choice per packaged platform:
 *
 *   - Windows — the executable's directory: the install root (`...\Programs\Infinia` for the
 *     per-user NSIS install, the extract folder for the portable ZIP), so the whole runtime
 *     tree (`.fengyu`: config, embedded database, logs, plugins, skills, chat data) stays
 *     with the app like `distribution/web`'s `<extract>\data` instead of hiding in
 *     `%APPDATA%`. The directory is probed for writability first (a Program Files location
 *     picked in the assisted installer, or a locked-down extract folder, is not writable
 *     unelevated); on failure the anchor stays `userData` and a legacy tree is never moved.
 *     A legacy `<userData>/.fengyu` left by earlier desktop builds is MOVED to the new root
 *     on the first writable launch (rename, with a recursive copy fallback for cross-volume
 *     installs), so an upgrade never orphans the user's database.
 *   - macOS — `app.getPath('userData')`. The .app bundle must not host the runtime tree:
 *     the zip auto-update replaces the whole bundle (which would delete the database), and
 *     writing into a signed/notarized bundle breaks its seal.
 *   - Linux — `app.getPath('userData')`. An AppImage's `process.execPath` points into the
 *     read-only, ephemeral squashfs mount, and the deb installs under a root-owned prefix,
 *     so neither can host `.fengyu`.
 *
 * This generalizes the chdir half of `uos.ts` (which anchored the UOS build to the user's
 * home for exactly this failure mode). The UOS policy still runs afterwards and re-anchors
 * to `~` for that build, which is why this must be invoked BEFORE `applyUosLaunchPolicy()`
 * in main.ts.
 *
 * Ordering contract (see main.ts): this must run before `initLogger()` and anything else
 * that touches `runtimeRoot()`. The tsc build emits CommonJS, whose `require()` executes
 * imports in source order, so a top-level call placed above the `initLogger()` call in
 * main.ts is guaranteed to run first — the same guarantee `applyUosLaunchPolicy()` relies on.
 *
 * Never throws: if no anchor can be established, the original cwd is kept and the logger's
 * own tmpdir fallback takes over.
 */
export interface BootstrapCwdResult {
  /** True when the working directory was actually changed. */
  changed: boolean
  /** The directory the process now runs from (the original cwd when unchanged). */
  directory: string
  /** True when userData could not be anchored and the OS temp directory was used instead. */
  fallbackUsed: boolean
  /** Set when a legacy `<userData>/.fengyu` tree was moved to the executable-directory root. */
  migrated?: { from: string; to: string }
}

export interface BootstrapCwdDeps {
  isPackaged?: boolean
  platform?: NodeJS.Platform
  exePath?: string
  userDataPath?: string
  fallbackPath?: string
  cwd?: () => string
  chdir?: (dir: string) => void
  mkdir?: (dir: string) => void
  exists?: (path: string) => boolean
  writeFile?: (file: string) => void
  unlink?: (file: string) => void
  rename?: (from: string, to: string) => void
  copyTree?: (from: string, to: string) => void
  removeTree?: (path: string) => void
}

/** Injected filesystem operations (always concrete after the dependency destructuring). */
interface FsOps {
  mkdir: (dir: string) => void
  exists: (path: string) => boolean
  writeFile: (file: string) => void
  unlink: (file: string) => void
  rename: (from: string, to: string) => void
  copyTree: (from: string, to: string) => void
  removeTree: (path: string) => void
}

/** Probe whether `<dir>/.fengyu` can be created and written (mkdir alone can pass under ACLs that then deny file creation). */
function isWritableRuntimeRoot(root: string, fresh: boolean, ops: FsOps): boolean {
  try {
    ops.mkdir(root)
  } catch {
    return false
  }
  const probe = join(root, '.write-probe')
  try {
    ops.writeFile(probe)
    ops.unlink(probe)
    return true
  } catch {
    // Don't leave an empty probe-created root behind: it would look like real data on the
    // next launch and block the legacy-tree migration ("both roots exist").
    if (fresh) {
      try {
        ops.removeTree(root)
      } catch {
        // Best-effort cleanup on an unwritable directory.
      }
    }
    return false
  }
}

type MigrationOutcome = 'moved' | 'kept-existing' | 'nothing-to-move' | 'failed'

/** Move the legacy `<userData>/.fengyu` to the new executable-directory root (Windows upgrade path). */
function migrateRuntimeTree(oldRoot: string, newRoot: string, newRootExisted: boolean, ops: FsOps): MigrationOutcome {
  if (!ops.exists(oldRoot)) return 'nothing-to-move'
  if (newRootExisted) {
    console.error(
      `[desktop] runtime root already present at ${newRoot}; leaving the legacy tree at ${oldRoot} untouched`,
    )
    return 'kept-existing'
  }
  try {
    ops.rename(oldRoot, newRoot) // same volume: atomic directory rename
    return 'moved'
  } catch {
    if (ops.exists(newRoot)) return 'moved' // a concurrent instance won the race
    try {
      ops.copyTree(oldRoot, newRoot) // cross-volume (%APPDATA% on C:, install dir on D:)
      ops.removeTree(oldRoot)
      return 'moved'
    } catch (err) {
      // Remove the partial copy so the next launch retries from the intact legacy tree.
      try {
        ops.removeTree(newRoot)
      } catch {
        // Best-effort cleanup; the legacy tree stays authoritative via the userData anchor.
      }
      console.error(`[desktop] cannot migrate ${oldRoot} to ${newRoot}: ${String(err)}`)
      return 'failed'
    }
  }
}

export function bootstrapWorkingDirectory(deps: BootstrapCwdDeps = {}): BootstrapCwdResult {
  const {
    isPackaged = app.isPackaged,
    platform = process.platform,
    exePath = process.execPath,
    userDataPath = app.getPath('userData'),
    fallbackPath = tmpdir(),
    cwd = () => process.cwd(),
    chdir = (dir) => process.chdir(dir),
    mkdir = (dir) => mkdirSync(dir, { recursive: true }),
    exists = (path) => existsSync(path),
    writeFile = (file) => writeFileSync(file, ''),
    unlink = (file) => unlinkSync(file),
    rename = (from, to) => renameSync(from, to),
    copyTree = (from, to) => cpSync(from, to, { recursive: true }),
    removeTree = (path) => rmSync(path, { recursive: true, force: true }),
  } = deps
  const ops: FsOps = { mkdir, exists, writeFile, unlink, rename, copyTree, removeTree }
  const original = cwd()
  if (!isPackaged) return { changed: false, directory: original, fallbackUsed: false }

  const anchor = (target: string): boolean => {
    // process.chdir requires the directory to exist; Electron does not guarantee the
    // userData directory has been materialized before the main module runs.
    try {
      mkdir(target)
    } catch {
      return false
    }
    try {
      chdir(target)
      return true
    } catch {
      return false
    }
  }

  // Anchor candidates, most preferred first. Everything except Windows anchors to the
  // per-user, always-writable userData directory (see the platform rationale above).
  const chain: string[] = [userDataPath]
  let migrated: { from: string; to: string } | undefined

  if (platform === 'win32') {
    const exeDir = dirname(exePath)
    const newRoot = join(exeDir, '.fengyu')
    const oldRoot = join(userDataPath, '.fengyu')
    const newRootExisted = exists(newRoot)
    if (isWritableRuntimeRoot(newRoot, !newRootExisted, ops)) {
      const outcome = migrateRuntimeTree(oldRoot, newRoot, newRootExisted, ops)
      if (outcome === 'moved') migrated = { from: oldRoot, to: newRoot }
      // 'failed' falls back to the userData anchor, where the intact legacy tree still lives.
      if (outcome !== 'failed') chain.unshift(exeDir)
    } else {
      console.error(
        `[desktop] ${exeDir} is not writable; keeping the runtime tree anchored at ${userDataPath}`,
      )
    }
  }

  const withMeta = (result: Omit<BootstrapCwdResult, 'migrated'>): BootstrapCwdResult =>
    migrated ? { ...result, migrated } : result

  for (const target of chain) {
    if (resolve(original) === resolve(target)) {
      return withMeta({ changed: false, directory: original, fallbackUsed: false })
    }
  }
  for (const target of chain) {
    if (anchor(target)) {
      return withMeta({ changed: true, directory: target, fallbackUsed: false })
    }
    console.error(`[desktop] cannot anchor the working directory to ${target}`)
  }
  console.error(`[desktop] falling back to ${fallbackPath}`)
  if (anchor(fallbackPath)) {
    return withMeta({ changed: true, directory: fallbackPath, fallbackUsed: true })
  }
  console.error(
    `[desktop] cannot anchor the working directory at all; keeping ${original} (logs fall back to the temp directory)`,
  )
  return withMeta({ changed: false, directory: original, fallbackUsed: false })
}
