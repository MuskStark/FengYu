import { appendFileSync, mkdirSync, renameSync, statSync } from 'node:fs'
import { win32 as windowsPath } from 'node:path'
import { runtimeRoot } from '../desktop/runtime-paths'

/**
 * Dedicated log for the update pipeline. Every step appends here — the main-process stages
 * (check, consent, download, extract, apply, quit), electron-updater's native output, and,
 * after the shell exits, the portable replace script — so a single file reconstructs the
 * whole update for field diagnosis.
 *
 * Location: the app's log folder (`<cwd>/.fengyu/logs/update.log`, beside desktop.log), NOT
 * %TEMP% — temp is cleaned by policy on intranet machines and is exactly where users can't
 * find (or attach) the trace when an update misbehaves.
 *
 * Rotation: append-only by nature (the Windows replace script keeps writing the same file
 * across process boundaries), so a cheap single-archive rollover at 2MB bounds it — a
 * rename failure (file held by the replace script) just keeps appending this once.
 *
 * win32 joins on purpose: the path is also baked into the Windows replace script, which must
 * receive backslash separators regardless of the platform generating it.
 */
export function updateLogPath(): string {
  return windowsPath.join(runtimeRoot(), 'logs', 'update.log')
}

const UPDATE_LOG_MAX_BYTES = 2 * 1024 * 1024

function rotateIfOversized(path: string): void {
  try {
    if (statSync(path).size > UPDATE_LOG_MAX_BYTES) {
      renameSync(path, path.replace(/\.log$/, '.old.log'))
    }
  } catch {
    // Missing file (first write) or rename raced the replace script — keep appending.
  }
}

/** Append one timestamped line. Best-effort by design: logging must never abort an update. */
export function logUpdate(message: string): void {
  try {
    const path = updateLogPath()
    mkdirSync(windowsPath.dirname(path), { recursive: true })
    rotateIfOversized(path)
    appendFileSync(path, `[${new Date().toISOString()}] ${message}\n`, 'utf8')
  } catch {
    // An unwritable log directory must not take the update (or the app) down.
  }
}
