import { isAxiosError } from 'axios'

/**
 * How long the wizard waits for the restarted backend to prove it is in APP mode.
 * The desktop shell's own boot patience is 120 s for health plus up to ~20 s for the
 * setup probe (caa9e784: slow hardware can take the whole budget), so the wizard must
 * outlast that window — giving up earlier would declare a timeout while the supervisor
 * is still legitimately waiting for the same backend.
 */
export const APP_MODE_WAIT_MS = 150_000

/** Probes the wizard polls while the backend process restarts into APP mode. */
export interface AppModeProbeDeps {
  /** GET /api/health — answers 200 {status:'ok'} in both SETUP and APP modes. */
  health: () => Promise<{ status: string }>
  /** GET /api/setup/status — mapped only in SETUP mode; APP mode answers 404. */
  setupStatus: () => Promise<unknown>
  /** Test seam; defaults to a real 500 ms timer. */
  sleep?: (ms: number) => Promise<void>
  /** Test seam; defaults to Date.now. */
  now?: () => number
}

/**
 * Poll until the restarted backend is provably in APP mode.
 *
 * A 200 from /api/setup/status is NOT success: the still-exiting SETUP backend answers
 * 200 `initialized:true` for its ~1 s grace period because the config was just
 * persisted, and APP mode does not serve /api/setup/** (token-bypassed wizard surface —
 * the same contract the router guard relies on). Only the 404 confirms APP mode.
 *
 * @returns 'app' once health is ok and /api/setup/status 404s; 'timeout' if the
 *   deadline passes without that proof (the caller offers a manual reload).
 */
export async function waitForAppMode(
  deps: AppModeProbeDeps,
  deadlineMs: number = APP_MODE_WAIT_MS,
): Promise<'app' | 'timeout'> {
  const sleep = deps.sleep ?? ((ms: number) => new Promise((resolve) => setTimeout(resolve, ms)))
  const now = deps.now ?? Date.now
  const deadline = now() + deadlineMs
  while (now() < deadline) {
    await sleep(500)
    try {
      const h = await deps.health()
      if (h.status !== 'ok') continue
      await deps.setupStatus()
    } catch (err) {
      if (isAxiosError(err) && err.response?.status === 404) {
        return 'app'
      }
      // Backend still down (or non-404 failure) — keep polling.
    }
  }
  return 'timeout'
}
