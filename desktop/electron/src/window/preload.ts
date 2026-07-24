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

contextBridge.exposeInMainWorld('fengyu', {
  apiBase: () => apiBase,
  token: () => token,
  desktop: true,
  pickFile: (filters?: { name: string; extensions: string[] }[]) =>
    ipcRenderer.invoke('dialog:open', { directory: false, filters }),
  pickDirectory: () => ipcRenderer.invoke('dialog:open', { directory: true }),
})
