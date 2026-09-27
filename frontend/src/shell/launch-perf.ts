/**
 * Renderer-side launch marks (T4–T6), mirroring ZCode's launch-marks design:
 *
 *   T4  renderer bundle starts evaluating (main.tsx module top)
 *   T5  first React commit (StartupReady effect in main.tsx)
 *   T6  app shell mounting (App.tsx entering 'app')
 *
 * The desktop main process owns T0–T3 and logs the merged line when T6 arrives
 * (desktop/electron/src/ipc/perf.ts); in browser mode the platform layer's
 * reportLaunchPerf is a no-op, so the marks stay console-only. Idempotent by
 * design — StrictMode's double-invoked effects and HMR re-evaluations must never
 * re-mark or re-report.
 */
import { getPlatform } from '@/platform'

let rendererStart = 0
let reactCommit = 0
let reported = false

export function markRendererStart(): void {
  rendererStart ||= Date.now()
}

export function markReactCommit(): void {
  reactCommit ||= Date.now()
}

export function markBootInputReady(): void {
  if (reported || !rendererStart || !reactCommit) return
  reported = true
  const inputReady = Date.now()
  console.info(
    `[perf] launch rendererStart=${rendererStart} reactCommit=${reactCommit} inputReady=${inputReady}` +
      ` (bundle→commit=${reactCommit - rendererStart}ms, commit→ready=${inputReady - reactCommit}ms)`,
  )
  getPlatform().reportLaunchPerf({ rendererStart, reactCommit, inputReady })
}
