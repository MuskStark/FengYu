import log from 'electron-log'
import { appendFileSync } from 'node:fs'
import { join } from 'node:path'
import { homedir } from 'node:os'

/**
 * Configure electron-log to write alongside the backend logs (~/.fengyu/logs).
 *
 * NOTE: deviates from the task brief. The brief teed backend lines via
 * `log.transports.file.getFile().write(...)`, but the electron-log typings
 * (LogFile interface) expose no `write()` method on the file handle (and the
 * private runtime class only has `writeLine`). That call would fail `tsc` under
 * strict mode. We instead append backend lines directly with the same file path
 * the file transport resolves to, so backend output lands in the same desktop.log.
 */
export function initLogger() {
  const logDir = join(homedir(), '.fengyu', 'logs')
  const desktopLogPath = join(logDir, 'desktop.log')
  log.transports.file.resolvePathFn = () => desktopLogPath
  log.transports.file.maxSize = 5 * 1024 * 1024 // 5 MB rotation
  log.transports.console.level = 'info'
  log.transports.file.level = 'info'
  log.info('[desktop] logger initialized')

  const backendLine = (line: string) => {
    // Tee backend stdout into the same desktop.log (mirrors the backend's own
    // "[backend] ..." lines). Append-only is safe and rotation-independent.
    try {
      appendFileSync(desktopLogPath, `[backend] ${line}\n`)
    } catch {
      // Logging must never throw into the backend stdout handler.
    }
  }
  return { info: log.info, error: log.error, warn: log.warn, backendLine }
}

export type DesktopLogger = ReturnType<typeof initLogger>
