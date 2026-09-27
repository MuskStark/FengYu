import log from 'electron-log'
import { mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { runtimeRoot } from './runtime-paths'

/**
 * Configure electron-log to write the desktop log alongside the backend logs
 * (<program-working-directory>/logs). Backend startup stdout (up to the port
 * handshake) and backend stderr (for the child's whole lifetime) flow into
 * desktop.log too, prefixed — one file per surface, each with its own rotation,
 * and no unbounded side files (the old backend-stdout.log tee had none).
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
  log.transports.file.resolvePathFn = () => join(logDir, 'desktop.log')
  log.transports.file.maxSize = 5 * 1024 * 1024 // 5 MB rotation
  log.transports.console.level = 'info'
  log.transports.file.level = 'info'
  log.info('[desktop] logger initialized')

  // Startup-window backend stdout: the only place JVM-boot failures are visible before
  // logback is up. Logged at info through electron-log so the 5MB rotation bounds it;
  // after the port handshake the listener detaches — steady-state backend logging lives
  // in fengyu.log.
  const backendLine = (line: string) => {
    log.info(`[backend] ${line}`)
  }
  // Backend stderr runs for the child's WHOLE lifetime: JVM warnings and crash output
  // have no other on-disk home under the desktop shell.
  const backendErrLine = (line: string) => {
    log.warn(`[backend-err] ${line}`)
  }
  return { info: log.info, error: log.error, warn: log.warn, backendLine, backendErrLine }
}

export type DesktopLogger = ReturnType<typeof initLogger>
