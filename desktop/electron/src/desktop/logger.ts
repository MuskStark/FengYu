import log from 'electron-log'
import { appendFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { runtimeRoot } from './runtime-paths'

/**
 * Configure electron-log to write the desktop log alongside the backend logs
 * (<program-working-directory>/logs). Backend stdout is teed to a SEPARATE file
 * (backend-stdout.log), distinct from desktop.log, matching the original Rust
 * behavior of keeping backend output separate.
 *
 * NOTE: deviates from the task brief. The brief teed backend lines via
 * `log.transports.file.getFile().write(...)`, but the electron-log typings
 * (LogFile interface) expose no `write()` method on the file handle (and the
 * private runtime class only has `writeLine`). That call would fail `tsc` under
 * strict mode. We instead append backend lines directly via appendFileSync to
 * the dedicated backend-stdout.log path.
 */

/**
 * Resolve (and create) the log directory, degrading to <tmpdir>/fengyu-logs when the
 * runtime root is unwritable (P1-9: a cwd-anchored runtime root on a read-only volume —
 * e.g. a macOS .app launched from Finder with cwd=/ — must never crash startup). Never
 * throws; a total failure keeps the primary path so electron-log's own internal error
 * handling (console fallback) takes over.
 */
export function resolveLogDir(): string {
  const primary = join(runtimeRoot(), 'logs')
  try {
    mkdirSync(primary, { recursive: true })
    return primary
  } catch (err) {
    const fallback = join(tmpdir(), 'fengyu-logs')
    console.error(
      `[desktop] cannot create log directory ${primary} ` +
        `(${err instanceof Error ? err.message : String(err)}); logs fall back to ${fallback}`,
    )
    try {
      mkdirSync(fallback, { recursive: true })
      return fallback
    } catch {
      // Both locations unwritable: keep the primary path; electron-log reports transport
      // failures to the console instead of throwing, so the shell still boots.
      return primary
    }
  }
}

export function initLogger() {
  const logDir = resolveLogDir()
  const desktopLogPath = join(logDir, 'desktop.log')
  const backendStdoutPath = join(logDir, 'backend-stdout.log')
  log.transports.file.resolvePathFn = () => desktopLogPath
  log.transports.file.maxSize = 5 * 1024 * 1024 // 5 MB rotation
  log.transports.console.level = 'info'
  log.transports.file.level = 'info'
  log.info('[desktop] logger initialized')

  const backendLine = (line: string) => {
    // Tee backend stdout to a SEPARATE file (distinct from desktop.log),
    // matching the original Rust behavior of keeping backend output separate.
    try {
      appendFileSync(backendStdoutPath, `[backend] ${line}\n`)
    } catch {
      // Logging must never throw into the backend stdout handler.
    }
  }
  return { info: log.info, error: log.error, warn: log.warn, backendLine }
}

export type DesktopLogger = ReturnType<typeof initLogger>
