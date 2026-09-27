import { ipcMain } from 'electron'
import { getMainLaunchMarks } from '../desktop/launch-marks'

interface Logger {
  info: (message: string) => void
}

/**
 * Register the `perf:launch-report` listener — the renderer's boot gate sends its
 * T4–T6 marks here (frontend/src/shell/launch-perf.ts, fired once when the health
 * poll first succeeds) and the main process merges them with its own T0–T3 into a
 * single desktop.log line next to the other startup timing logs.
 *
 * One-way (`ipcRenderer.send`, not invoke): the renderer never awaits a reply, so
 * a missing/stale handler can never block or break the boot gate. Payload fields
 * are re-validated as finite numbers before logging.
 */
export function registerPerfIpc(logger: Logger): void {
  ipcMain.on('perf:launch-report', (_event, raw: unknown) => {
    const marks = (raw ?? {}) as Record<string, unknown>
    const num = (value: unknown): number | null =>
      typeof value === 'number' && Number.isFinite(value) ? value : null
    const rendererStart = num(marks['rendererStart'])
    const reactCommit = num(marks['reactCommit'])
    const inputReady = num(marks['inputReady'])
    if (!rendererStart || !reactCommit || !inputReady) return

    const { t0ProcessCreate, t1MainStart, t2WhenReady, t3MainWindowLoad } = getMainLaunchMarks()
    const phase = (from: number, to: number): string => (to && from ? `${to - from}` : '?')
    logger.info(
      `[perf] launch t0=${t0ProcessCreate} t1=${t1MainStart} t2=${t2WhenReady} t3=${t3MainWindowLoad} ` +
        `t4=${rendererStart} t5=${reactCommit} t6=${inputReady} ` +
        `(mainInit=${phase(t1MainStart, t2WhenReady)}ms rendererLoad=${phase(t3MainWindowLoad, rendererStart)}ms ` +
        `reactCommit=${phase(rendererStart, reactCommit)}ms bootGate=${phase(reactCommit, inputReady)}ms ` +
        `total=${inputReady - t0ProcessCreate}ms)`,
    )
  })
}
