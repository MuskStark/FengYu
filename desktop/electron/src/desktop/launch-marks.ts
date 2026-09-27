/**
 * Main-process launch marks (T0–T3) for the desktop launch-perf line, logged by
 * ipc/perf.ts once the renderer reports its T4–T6 (frontend/src/shell/launch-perf.ts).
 *
 *   T0  process creation (Electron's process.getCreationTime; Node-only test
 *       environments lack it — guarded)
 *   T1  main bundle evaluation (this module's import time)
 *   T2  app.whenReady resolved
 *   T3  main-window loadURL begins (marked right before each createMainWindow call)
 *
 * Deliberately side-effect-free so the module import itself can never skew T1
 * (ZCode isolates its desktopLaunchMarks the same way — bootstrap imports must
 * not pollute the marks they produce).
 */
const t0ProcessCreate =
  (typeof process.getCreationTime === 'function' ? process.getCreationTime() : null) ?? Date.now()
const t1MainStart = Date.now()
let t2WhenReady = 0
let t3MainWindowLoad = 0

export function markMainLaunchWhenReady(): void {
  t2WhenReady ||= Date.now()
}

export function markMainWindowLoad(): void {
  t3MainWindowLoad ||= Date.now()
}

export function getMainLaunchMarks(): {
  t0ProcessCreate: number
  t1MainStart: number
  t2WhenReady: number
  t3MainWindowLoad: number
} {
  return { t0ProcessCreate, t1MainStart, t2WhenReady, t3MainWindowLoad }
}
