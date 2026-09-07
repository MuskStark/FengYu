import { app } from 'electron'
import { mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'

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
 * This generalizes the chdir half of `uos.ts` (which anchored the UOS build to the user's
 * home for exactly this failure mode) to every packaged platform, targeting
 * `app.getPath('userData')` — the per-user, always-writable application directory — so
 * the whole runtime tree lands in one place on macOS dmg, Linux deb and Windows installs
 * alike. The UOS policy still runs afterwards and re-anchors to `~` for that build, which
 * is why this must be invoked BEFORE `applyUosLaunchPolicy()` in main.ts.
 *
 * Ordering contract (see main.ts): this must run before `initLogger()` and anything else
 * that touches `runtimeRoot()`. The tsc build emits CommonJS, whose `require()` executes
 * imports in source order, so a top-level call placed above the `initLogger()` call in
 * main.ts is guaranteed to run first — the same guarantee `applyUosLaunchPolicy()` relies on.
 *
 * Never throws: if neither userData nor the OS temp directory can be anchored, the
 * original cwd is kept and the logger's own tmpdir fallback takes over.
 */
export interface BootstrapCwdResult {
  /** True when the working directory was actually changed. */
  changed: boolean
  /** The directory the process now runs from (the original cwd when unchanged). */
  directory: string
  /** True when userData could not be anchored and the OS temp directory was used instead. */
  fallbackUsed: boolean
}

export interface BootstrapCwdDeps {
  isPackaged?: boolean
  userDataPath?: string
  fallbackPath?: string
  cwd?: () => string
  chdir?: (dir: string) => void
  mkdir?: (dir: string) => void
}

export function bootstrapWorkingDirectory(deps: BootstrapCwdDeps = {}): BootstrapCwdResult {
  const {
    isPackaged = app.isPackaged,
    userDataPath = app.getPath('userData'),
    fallbackPath = tmpdir(),
    cwd = () => process.cwd(),
    chdir = (dir) => process.chdir(dir),
    mkdir = (dir) => mkdirSync(dir, { recursive: true }),
  } = deps
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

  if (resolve(original) === resolve(userDataPath)) {
    return { changed: false, directory: original, fallbackUsed: false }
  }
  if (anchor(userDataPath)) {
    return { changed: true, directory: userDataPath, fallbackUsed: false }
  }
  console.error(
    `[desktop] cannot anchor the working directory to ${userDataPath}; falling back to ${fallbackPath}`,
  )
  if (anchor(fallbackPath)) {
    return { changed: true, directory: fallbackPath, fallbackUsed: true }
  }
  console.error(
    `[desktop] cannot anchor the working directory at all; keeping ${original} (logs fall back to the temp directory)`,
  )
  return { changed: false, directory: original, fallbackUsed: false }
}
