import { contextBridge, ipcRenderer } from 'electron'

/**
 * Standalone preload entry — referenced by `webPreferences.preload` as a single
 * compiled JS file (`dist/preload.js`).
 *
 * The renderer (SPA) loads after this script, so the only channel that exists
 * in time to hand off `apiBase`/`token` is `process.env` — main.ts sets
 * `FENGYU_API_BASE`/`FENGYU_TOKEN` before the page loads. EXCEPTION: the main
 * window is created before the backend spawn (its startup screen covers the JVM
 * cold start), so on the FIRST load the env snapshot is still empty — the
 * endpoint arrives later through the `endpoint:ready` push (ipc/endpoint.ts),
 * and the SPA's platform layer prefers the live push over the snapshot.
 *
 * `apiBase`/`token` are read-only to the renderer — the SPA fetches the backend
 * directly over loopback (SSE, uploads, plugin host all need native
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
  platform: process.platform,
  desktop: true,
  pickFile: (filters?: { name: string; extensions: string[] }[]) =>
    ipcRenderer.invoke('dialog:open', { directory: false, filters }),
  pickDirectory: () => ipcRenderer.invoke('dialog:open', { directory: true }),
  // `window.confirm` is silently dropped in sandboxed renderers (electron#7472), so
  // the SPA confirms destructive actions through this native message box instead.
  confirm: (message: string) =>
    ipcRenderer.invoke('dialog:confirm', { message }) as Promise<boolean>,
  openExternal: (url: string) =>
    ipcRenderer.invoke('external:open', url) as Promise<void>,
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
  // ── Unified host notifications (renderer asks for a native OS toast when
  //    its window is not visible; clicking it focuses the window) ──
  showNotification: (opts: { title: string; body?: string }) =>
    ipcRenderer.invoke('notification:show', opts),
  // ── Saved chat artifacts: the shell resolves the path from the backend registry,
  //    so the renderer only ever passes the opaque artifact id (7.4). ──
  revealArtifact: (artifactId: string) =>
    ipcRenderer.invoke('artifact:reveal', artifactId) as Promise<void>,
  openArtifact: (artifactId: string) =>
    ipcRenderer.invoke('artifact:open', artifactId) as Promise<void>,
  // ── Launch perf: one-way T4–T6 report from the renderer's boot gate; the main
  //    process owns T0–T3 and logs the merged line (ipc/perf.ts). ──
  reportLaunchPerf: (marks: { rendererStart: number; reactCommit: number; inputReady: number }) =>
    ipcRenderer.send('perf:launch-report', marks),
  // ── Renderer log forwarding (ipc/log.ts): the SPA's error surface lands in
  //    desktop.log — the renderer has no filesystem access of its own. ──
  reportLog: (level: 'info' | 'warn' | 'error', message: string) =>
    ipcRenderer.send('log:renderer', { level, message }),
  // ── Backend endpoint handoff (ipc/endpoint.ts): the window loads before the
  //    backend port is known, so the env snapshot above can be empty on the
  //    first load — the SPA subscribes here and updates its apiBase/token the
  //    moment the spawn resolves the port. ──
  onEndpoint: (cb: (state: { apiBase: string; token: string }) => void) => {
    const handler = (_e: unknown, state: { apiBase: string; token: string }) => cb(state)
    ipcRenderer.on('endpoint:ready', handler)
    return () => ipcRenderer.removeListener('endpoint:ready', handler)
  },
  getEndpoint: () =>
    ipcRenderer.invoke('endpoint:get') as Promise<{ apiBase: string; token: string } | null>,
  // ── Boot failure recovery (ipc/boot.ts): the SPA's boot gate renders the failure
  //    screen, ACKs visibility (disarming the native-dialog fallback), and drives
  //    retry / open-logs / copy / quit through these channels. ──
  onBootState: (cb: (state: BootStatePayload) => void) => {
    const handler = (_e: unknown, state: BootStatePayload) => cb(state)
    ipcRenderer.on('boot:state', handler)
    return () => ipcRenderer.removeListener('boot:state', handler)
  },
  getBootState: () => ipcRenderer.invoke('boot:get-state') as Promise<BootStatePayload | null>,
  ackBootFailure: () => ipcRenderer.invoke('boot:failure-visible'),
  retryBoot: () =>
    ipcRenderer.invoke('boot:retry') as Promise<{ ok: boolean; error?: string }>,
  openLogsFolder: () => ipcRenderer.invoke('boot:open-logs') as Promise<string | null>,
  copyText: (text: string) => ipcRenderer.invoke('clipboard:write-text', text) as Promise<void>,
  quitApp: () => ipcRenderer.send('boot:quit'),
})

interface BootStatePayload {
  phase: 'booting' | 'failed' | 'ready'
  reason?: 'backend-exited' | 'health-deadline' | 'setup-probe-failed' | 'retry-spawn-failed' | 'port-changed'
  exitCode?: number | null
  detail?: string
  attempt?: number
}

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
