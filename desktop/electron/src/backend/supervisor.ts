import { type ChildProcess } from 'node:child_process'

/** What the orchestrator does after the backend first boots. */
export enum StartupAction {
  ShowWindow = 'ShowWindow',
  ShowWindowAndSupervise = 'ShowWindowAndSupervise',
}

/**
 * Decide whether to show the window, and whether to supervise a SETUP→APP restart.
 * Mirrors Rust `startup_action`.
 */
export function startupAction(setupMode: boolean, _port: number): StartupAction {
  return setupMode ? StartupAction.ShowWindowAndSupervise : StartupAction.ShowWindow
}

/**
 * Should the supervisor respawn the backend after it exits during SETUP?
 * Only when not shutting down AND the exit code is exactly 0 (Java SETUP_DONE signal).
 * Mirrors Rust `should_restart_setup`.
 */
export function shouldRestartSetup(shuttingDown: boolean, exitCode: number | null): boolean {
  return !shuttingDown && exitCode === 0
}

/** A handle to a spawned backend: the child + how to kill it. */
export interface BackendChild {
  process: ChildProcess
  kill(): void
}

/**
 * Watch a SETUP-mode backend child; on exit code 0, respawn it into APP mode.
 *
 * @param getChild  returns the current BackendChild (the supervisor swaps it after respawn)
 * @param setChild  installs a new BackendChild (called after a successful restart)
 * @param restart   re-runs startBackend (spawn→health→setup) into APP mode
 * @param isShuttingDown returns true once the app is quitting
 * @returns a `stop()` function that detaches the watcher (idempotent)
 */
export interface SupervisorConfig {
  getChild: () => BackendChild | null
  setChild: (child: BackendChild | null) => void
  restart: () => Promise<{ child: BackendChild; port: number; setupMode: boolean }>
  expectedPort: number
  isShuttingDown: () => boolean
  onFatal: (message: string) => void
}

export function superviseSetupRestart(cfg: SupervisorConfig): () => void {
  let stopped = false

  const watch = () => {
    const current = cfg.getChild()
    if (!current || stopped) return
    const proc = current.process
    proc.once('exit', (code, _signal) => {
      if (stopped) return
      if (!shouldRestartSetup(cfg.isShuttingDown(), code)) {
        if (!cfg.isShuttingDown()) {
          cfg.onFatal(`SETUP backend exited with code ${code}; not restarting`)
        }
        return
      }
      console.log('[desktop] setup complete; restarting backend into APP mode')
      cfg.restart()
        .then((restarted) => {
          if (restarted.port !== cfg.expectedPort) {
            restarted.child.kill()
            cfg.onFatal(
              `restarted backend moved from port ${cfg.expectedPort} to ${restarted.port}; ` +
                'the webview endpoint cannot change',
            )
            return
          }
          if (restarted.setupMode) {
            restarted.child.kill()
            cfg.onFatal('backend remained in SETUP mode after successful initialization')
            return
          }
          if (cfg.isShuttingDown()) {
            restarted.child.kill()
          } else {
            cfg.setChild(restarted.child)
            console.log(`[desktop] backend restarted in APP mode on port ${cfg.expectedPort}`)
            watch() // re-arm for any future exits (defensive; APP mode shouldn't exit)
          }
        })
        .catch((err) => cfg.onFatal(`failed to restart backend after setup: ${err}`))
    })
  }

  watch()
  return () => {
    stopped = true
  }
}
