import { appendFileSync, mkdirSync } from 'node:fs'
import { win32 as windowsPath } from 'node:path'
import { runtimeRoot } from '../desktop/runtime-paths'

/**
 * Dedicated log for the update pipeline. Every step appends here — the main-process stages
 * (check, consent, download, extract, apply, quit) and, after the shell exits, the portable
 * replace script — so a single file reconstructs the whole update for field diagnosis.
 *
 * Location: the app's log folder (`<cwd>/.fengyu/logs/update.log`, beside desktop.log), NOT
 * %TEMP% — temp is cleaned by policy on intranet machines and is exactly where users can't
 * find (or attach) the trace when an update misbehaves.
 *
 * win32 joins on purpose: the path is also baked into the Windows replace script, which must
 * receive backslash separators regardless of the platform generating it.
 */
export function updateLogPath(): string {
  return windowsPath.join(runtimeRoot(), 'logs', 'update.log')
}

/** Append one timestamped line. Best-effort by design: logging must never abort an update. */
export function logUpdate(message: string): void {
  try {
    mkdirSync(windowsPath.dirname(updateLogPath()), { recursive: true })
    appendFileSync(updateLogPath(), `[${new Date().toISOString()}] ${message}\n`, 'utf8')
  } catch {
    // An unwritable log directory must not take the update (or the app) down.
  }
}
