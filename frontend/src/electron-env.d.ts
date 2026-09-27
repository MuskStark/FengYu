/** The shape exposed by the Electron preload via contextBridge. Undefined in web mode. */
import type { BootStateEvent } from '@/platform/types'

export interface FengyuBridge {
  apiBase(): string
  token(): string
  initialTheme(): 'dark' | 'light'
  setupMode(): boolean | null
  setTheme(theme: 'dark' | 'light'): void
  platform: NodeJS.Platform
  pickFile(filters?: { name: string; extensions: string[] }[]): Promise<string | null>
  pickDirectory(): Promise<string | null>
  /** Native confirm; `window.confirm` is silently dropped in sandboxed renderers. */
  confirm(message: string): Promise<boolean>
  /** Open a validated http(s) URL in the system browser. */
  openExternal(url: string): Promise<void>
  desktop: true
  // ── Update (renderer-driven; consent comes from the UI "update now" click) ──
  checkForUpdates(): Promise<{ updateAvailable: boolean; version: string | null; releaseUrl: string | null }>
  downloadAndInstall(): Promise<{ action: 'restarting' } | { action: 'manual'; releaseUrl: string }>
  onUpdateProgress(cb: (info: { percent: number; transferred: number; total: number; bytesPerSecond: number }) => void): () => void
  onUpdateState(cb: (state: { state: string; message?: string }) => void): () => void
  /** Push the update-channel proxy URL into the main process so the next check honors it. */
  setUpdateApiBase(url: string): Promise<void>
  // ── Unified host notifications (native OS toast when the window is not visible) ──
  showNotification(opts: { title: string; body?: string }): Promise<boolean>
  // ── Saved chat artifacts (path resolved main-side from the backend registry;
  //    older shells lack these — probe with typeof before calling) ──
  revealArtifact(artifactId: string): Promise<void>
  openArtifact(artifactId: string): Promise<void>
  /** One-way launch-perf report (renderer T4–T6); the main process merges its T0–T3
   *  into a single desktop.log line (ipc/perf.ts). Older shells lack it. */
  reportLaunchPerf(marks: { rendererStart: number; reactCommit: number; inputReady: number }): void
  reportLog(level: 'info' | 'warn' | 'error', message: string): void
  // ── Backend endpoint handoff (ipc/endpoint.ts). The window loads before the
  //    backend port is known, so the env snapshot can be empty on the first
  //    load — the platform layer prefers the live push. Older shells lack it. ──
  onEndpoint?(cb: (state: { apiBase: string; token: string }) => void): () => void
  getEndpoint?(): Promise<{ apiBase: string; token: string } | null>
  // ── Boot failure recovery (ipc/boot.ts). Older shells lack the whole group —
  //    platform capabilities probe `retryBoot`/`onBootState` with typeof. ──
  onBootState(cb: (state: BootStateEvent) => void): () => void
  getBootState(): Promise<BootStateEvent | null>
  ackBootFailure(): void
  retryBoot(): Promise<{ ok: boolean; error?: string }>
  openLogsFolder(): Promise<string | null>
  copyText(text: string): Promise<void>
  quitApp(): void
}

declare global {
  interface Window {
    fengyu?: FengyuBridge
  }
}
