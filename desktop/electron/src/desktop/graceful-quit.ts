import { app } from 'electron'
import type { ChildProcess } from 'node:child_process'
import type { BackendChild } from '../backend/supervisor'

/**
 * Graceful-quit orchestration for the spawned backend, used by main.ts's before-quit handler.
 *
 * The plain flow (SIGTERM in before-quit, SIGKILL in will-quit) loses the race: tree-kill's
 * SIGTERM needs async pgrep/ps enumeration while will-quit's sync SIGKILL goes out first, so a
 * normal quit almost always killed the backend JVM before Spring Boot's graceful shutdown
 * (flush data, stop plugin workers) could run. This module implements the standard
 * preventDefault-and-wait pattern instead:
 *
 *   first before-quit  → preventDefault → SIGTERM the whole tree → wait for the child's exit
 *                        event (capped at graceMs) → forceKill() backstop → app.quit() again
 *   second before-quit → passes straight through (re-entrancy flag); will-quit's forceKill()
 *                        stays as the final backstop on every exit path.
 *
 * Paths that must NOT wait: when no backend was spawned (dev mode connecting to an IDE-started
 * backend) or it already exited, the quit proceeds immediately; an update install-restart
 * (portable applyPortableUpdate or autoUpdater.quitAndInstall, both marked via
 * markUpdateInstallRestart) skips the grace window so the installer/relaunch never sits behind
 * a backend shutdown wait.
 */

let updateInstallRestartPending = false

/**
 * Mark that the app is quitting only to hand over to an update installer (the portable
 * robocopy/relaunch bat, or electron-updater's quitAndInstall). Call right before quitting;
 * the graceful-quit handler then force-kills the backend tree instead of waiting out the
 * grace window. One-way on purpose — quitting is never un-marked.
 */
export function markUpdateInstallRestart(): void {
  updateInstallRestartPending = true
}

export interface GracefulQuitOptions {
  /** Returns the current backend child (null in dev mode connecting to an external backend). */
  getChild: () => BackendChild | null
  /** Synchronous teardown for the first quit attempt (stops the supervisor, bridge, dev Vite). */
  onTeardown: () => void
  /** Cap on the graceful SIGTERM wait before the SIGKILL backstop (default 2500ms). */
  graceMs?: number
  /** Log sink (optional). */
  log?: (msg: string) => void
  /** Quit function re-issued after the grace window (defaults to app.quit(); test seam). */
  quit?: () => void
}

/** Minimal Electron event surface the handler needs (keeps it unit-testable). */
interface PreventableEvent {
  preventDefault(): void
}

function isAlive(proc: ChildProcess): boolean {
  return proc.pid !== undefined && proc.exitCode === null && proc.signalCode === null
}

/**
 * Build the before-quit handler. The returned handler is safe to call repeatedly: only the
 * first call runs the graceful sequence; later calls (the re-issued app.quit()) pass through.
 */
export function createGracefulQuitHandler(opts: GracefulQuitOptions): (e: PreventableEvent) => void {
  const graceMs = opts.graceMs ?? 2_500
  const log = opts.log ?? (() => {})
  const quit = opts.quit ?? ((): void => app.quit())
  let quitInitiated = false

  return (e) => {
    // The re-issued quit after the grace window: let it through. will-quit's forceKill remains
    // the final backstop (and no-ops once the child has exited).
    if (quitInitiated) return
    quitInitiated = true

    opts.onTeardown()

    const child = opts.getChild()
    const proc = child?.process
    if (!child || !proc || !isAlive(proc)) {
      // Dev connect mode (no spawned backend) or the backend already exited — allow this quit
      // to proceed immediately.
      return
    }
    if (updateInstallRestartPending) {
      // Update install-restart: the installer or the portable replace bat is waiting on this
      // process — do not sit out a shutdown grace window. Same synchronous SIGKILL as the
      // will-quit backstop, just earlier so file locks release sooner.
      child.forceKill()
      return
    }

    e.preventDefault()
    // Graceful path: SIGTERM the whole backend tree (backend JVM + worker grandchildren) so
    // Spring Boot can flush and stop plugin workers, then hold the quit until it really exits.
    child.kill()
    log(`[desktop] quit: waiting up to ${graceMs} ms for the backend to shut down gracefully`)
    void waitForExit(proc, graceMs).then(() => {
      child.forceKill() // no-op when the tree already exited; SIGKILL backstop otherwise
      quit()
    })
  }
}

/** Resolve when `proc` exits or after `graceMs`, whichever comes first. */
function waitForExit(proc: ChildProcess, graceMs: number): Promise<void> {
  if (!isAlive(proc)) return Promise.resolve()
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer)
      proc.off('exit', done)
      resolve()
    }
    const timer = setTimeout(done, graceMs)
    proc.once('exit', done)
  })
}
