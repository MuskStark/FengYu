import { contextBridge, ipcRenderer } from 'electron'

/**
 * Standalone preload entry — referenced by `webPreferences.preload` as a single
 * compiled JS file (`dist/preload.js`).
 *
 * The renderer (Vue SPA) loads after this script, so the only channel that exists
 * in time to hand off `apiBase`/`token` is `process.env` — main.ts sets
 * `FENGYU_API_BASE`/`FENGYU_TOKEN` before creating the window.
 *
 * `apiBase`/`token` are read-only snapshots captured at startup — the SPA fetches
 * the backend directly over loopback (SSE, uploads, plugin host all need native
 * fetch/EventSource/FormData, which IPC can't carry). The token is per-launch and
 * loopback-only, so exposing it as a snapshot is low-risk.
 *
 * `pickFile`/`pickDirectory` go through IPC to use Electron's native dialog.
 */
const apiBase = process.env.FENGYU_API_BASE ?? ''
const token = process.env.FENGYU_TOKEN ?? ''
const initialTheme = process.env.FENGYU_THEME === 'light' ? 'light' : 'dark'
const setupMode = process.env.FENGYU_SETUP_MODE === 'true'
  ? true
  : process.env.FENGYU_SETUP_MODE === 'false'
    ? false
    : null

contextBridge.exposeInMainWorld('fengyu', {
  apiBase: () => apiBase,
  token: () => token,
  initialTheme: () => initialTheme,
  setupMode: () => setupMode,
  setTheme: (theme: 'dark' | 'light') => ipcRenderer.send('appearance:set-theme', theme),
  desktop: true,
  pickFile: (filters?: { name: string; extensions: string[] }[]) =>
    ipcRenderer.invoke('dialog:open', { directory: false, filters }),
  pickDirectory: () => ipcRenderer.invoke('dialog:open', { directory: true }),
  // ── Update (renderer-driven; consent comes from the UI "update now" click) ──
  checkForUpdates: () =>
    ipcRenderer.invoke('update:check'),
  downloadAndInstall: () =>
    ipcRenderer.invoke('update:download-install'),
  // Push the persisted update-proxy URL into the main process (see ipc/update.ts).
  setUpdateApiBase: (url: string) =>
    ipcRenderer.invoke('update:set-api-base', url),
  onUpdateProgress: (cb: (info: UpdateProgressInfo) => void) => {
    const handler = (_e: unknown, p: UpdateProgressInfo) => cb(p)
    ipcRenderer.on('update:progress', handler)
    return () => ipcRenderer.removeListener('update:progress', handler)
  },
  onUpdateState: (cb: (state: UpdateStateEvent) => void) => {
    const handler = (_e: unknown, s: UpdateStateEvent) => cb(s)
    ipcRenderer.on('update:state', handler)
    return () => ipcRenderer.removeListener('update:state', handler)
  },
})

interface UpdateProgressInfo {
  percent: number
  transferred: number
  total: number
  bytesPerSecond: number
}

interface UpdateStateEvent {
  state: string
  message?: string
}
