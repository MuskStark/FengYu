/** The shape exposed by the Electron preload via contextBridge. Undefined in web mode. */
export interface FengyuBridge {
  apiBase(): string
  token(): string
  initialTheme(): 'dark' | 'light'
  setupMode(): boolean | null
  setTheme(theme: 'dark' | 'light'): void
  platform: NodeJS.Platform
  pickFile(filters?: { name: string; extensions: string[] }[]): Promise<string | null>
  pickDirectory(): Promise<string | null>
  desktop: true
  // ── Update (renderer-driven; consent comes from the UI "update now" click) ──
  checkForUpdates(): Promise<{ updateAvailable: boolean; version: string | null; releaseUrl: string | null }>
  downloadAndInstall(): Promise<{ action: 'restarting' } | { action: 'manual'; releaseUrl: string }>
  onUpdateProgress(cb: (info: { percent: number; transferred: number; total: number; bytesPerSecond: number }) => void): () => void
  onUpdateState(cb: (state: { state: string; message?: string }) => void): () => void
  /** Push the update-channel proxy URL into the main process so the next check honors it. */
  setUpdateApiBase(url: string): Promise<void>
}

declare global {
  interface Window {
    fengyu?: FengyuBridge
  }
}
