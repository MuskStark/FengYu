import { app, clipboard, ipcMain, shell, type BrowserWindow } from 'electron'

/**
 * Boot progress + in-app boot-failure recovery — the desktop counterpart of the
 * SPA's boot gate (frontend/src/shell/BootGate.tsx + StartupScreen/StartupFailureScreen).
 *
 * The main window is created FIRST (before the backend spawn), so its in-app
 * startup screen owns the whole boot surface the retired splash window used to
 * cover. The shell pushes `boot:state` events to it:
 *
 * - `{phase:'booting', stage}` — fine-grained startup progress (BootStage below);
 *   the renderer's StartupScreen maps each stage to a localized label + progress
 *   bar. Pushes to an unloaded page are dropped, so the renderer re-pulls the
 *   current state on mount (subscribe first, then `boot:get-state`).
 * - `{phase:'failed', …}` — boot failures used to be a native `dialog.showErrorBox`
 *   + `app.quit()`; now the renderer swaps in a recoverable screen (retry / open
 *   logs / copy diagnostics / quit). It must ACK with `boot:failure-visible`
 *   within ACK_FALLBACK_MS; a renderer that never loads or never mounts the
 *   screen can never ACK, and the native dialog + quit fallback keeps exactly
 *   the old behavior for that path.
 */

/**
 * Startup stages, in order. Shared by the spawn path (port-ready), the health
 * poll (health-ready), and main.ts's own orchestration (spawning, loading-ui);
 * the renderer mirrors this union (frontend platform/types.ts BootStage).
 */
export type BootStage = 'spawning' | 'port-ready' | 'health-ready' | 'loading-ui'

export type BootFailureReason =
  | 'backend-exited' // the JVM exited during the boot wait
  | 'health-deadline' // the 120s health deadline expired with the JVM alive
  | 'setup-probe-failed' // healthy, but /api/setup/status could not be read
  | 'retry-spawn-failed' // a user-requested retry failed to respawn
  | 'port-changed' // a retry came back on a different port (renderer endpoint is fixed)

export interface BootState {
  phase: 'booting' | 'failed' | 'ready'
  /** Fine-grained stage while phase is 'booting' (the startup screen's label). */
  stage?: BootStage
  reason?: BootFailureReason
  exitCode?: number | null
  detail?: string
  attempt?: number
}

export const BOOT_STATE_CHANNEL = 'boot:state'

/** Wait this long for the renderer to ACK a failure before the native fallback. */
export const ACK_FALLBACK_MS = 15_000

interface BootIpcOptions {
  logger: { info: (message: string) => void }
  getWindow: () => BrowserWindow | null
  logsDir: () => string
  /** Re-runs the full spawn→health→setup sequence; throws on failure. */
  onRetry: () => Promise<void>
  /** Native fallback when the renderer never ACKs a failure (dialog + quit). */
  onAckTimeout: (state: BootState) => void
}

export interface BootIpc {
  pushBootState(state: BootState): void
}

/**
 * Tag an error with the boot-failure reason it should report (see
 * classifyBootFailure). First tag wins — a later, coarser tag (e.g. the retry
 * path's `retry-spawn-failed`) must not overwrite a precise one already set by
 * the failing stage (`backend-exited`, `port-changed`, …). Returns the same
 * error instance for `throw`.
 */
export function tagBootFailure<T>(err: T, reason: BootFailureReason): T {
  if (err instanceof Error && !(err as { bootReason?: unknown }).bootReason) {
    Object.assign(err, { bootReason: reason })
  }
  return err
}

/** Read back the reason attached by tagBootFailure (untagged → health-deadline). */
export function classifyBootFailure(err: unknown): {
  reason: BootFailureReason
  exitCode: number | null
} {
  const exitCode =
    err !== null && typeof err === 'object' && 'exitCode' in err
      ? (err as { exitCode?: unknown }).exitCode
      : undefined
  return {
    reason:
      err !== null &&
      typeof err === 'object' &&
      'bootReason' in err &&
      typeof (err as { bootReason?: unknown }).bootReason === 'string'
        ? ((err as { bootReason: BootFailureReason }).bootReason)
        : 'health-deadline',
    exitCode: typeof exitCode === 'number' ? exitCode : null,
  }
}

export function registerBootIpc(opts: BootIpcOptions): BootIpc {
  let ackTimer: NodeJS.Timeout | null = null
  let retryInFlight = false
  // The last pushed state. A failure can fire before the renderer has even loaded
  // (webContents.send to an unloaded page is dropped, not queued), so the renderer
  // re-pulls this on mount — subscribe first, then getBootState(), and a late
  // mounting failure screen still appears and ACKs within the fallback window.
  let lastState: BootState | null = null

  const clearAckTimer = () => {
    if (ackTimer) {
      clearTimeout(ackTimer)
      ackTimer = null
    }
  }

  const pushBootState = (state: BootState) => {
    lastState = state
    const win = opts.getWindow()
    if (win && !win.isDestroyed()) {
      win.webContents.send(BOOT_STATE_CHANNEL, state)
    }
    if (state.phase === 'failed') {
      // (Re)arm the native fallback; an ACK from the renderer disarms it.
      clearAckTimer()
      ackTimer = setTimeout(() => {
        ackTimer = null
        opts.logger.info('[desktop] boot failure not acknowledged by the renderer — native fallback')
        opts.onAckTimeout(state)
      }, ACK_FALLBACK_MS)
    } else {
      clearAckTimer()
    }
  }

  ipcMain.handle('boot:get-state', () => lastState)

  ipcMain.handle('boot:failure-visible', () => {
    if (ackTimer) {
      clearAckTimer()
      opts.logger.info('[desktop] boot failure acknowledged by the renderer (in-app failure screen visible)')
    }
    return true
  })

  ipcMain.handle('boot:retry', async () => {
    if (retryInFlight) return { ok: false, error: 'retry-already-running' }
    retryInFlight = true
    try {
      await opts.onRetry()
      return { ok: true as const }
    } catch (err) {
      return { ok: false as const, error: err instanceof Error ? err.message : String(err) }
    } finally {
      retryInFlight = false
    }
  })

  ipcMain.handle('boot:open-logs', async () => {
    const dir = opts.logsDir()
    const errorMessage = await shell.openPath(dir)
    if (errorMessage) {
      opts.logger.info(`[desktop] open logs failed: ${errorMessage} (${dir})`)
    }
    return errorMessage ? null : dir
  })

  ipcMain.handle('clipboard:write-text', (_event, text: unknown) => {
    clipboard.writeText(typeof text === 'string' ? text : String(text ?? ''))
    return true
  })

  ipcMain.on('boot:quit', () => {
    app.quit()
  })

  return { pushBootState }
}
