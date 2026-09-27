import { ipcMain, type BrowserWindow } from 'electron'

/**
 * Backend endpoint handoff for the early-created main window.
 *
 * The main window is created BEFORE the backend spawn (its startup screen covers
 * the JVM cold start), so the preload's `process.env` snapshot of
 * FENGYU_API_BASE/FENGYU_TOKEN is still empty when the page loads. The main
 * process pushes the endpoint here the moment the spawn resolves the port; the
 * renderer's platform layer subscribes first and then pulls once — a push to an
 * unloaded page is dropped, not queued (same race pattern as ipc/boot.ts).
 *
 * Page reloads after boot re-run the preload with the env already set, so the
 * snapshot path keeps working; the push simply keeps one live code path for the
 * first load.
 */

export interface EndpointState {
  apiBase: string
  token: string
}

export const ENDPOINT_CHANNEL = 'endpoint:ready'

interface EndpointIpcOptions {
  getWindow: () => BrowserWindow | null
}

export interface EndpointIpc {
  pushEndpoint(state: EndpointState): void
}

export function registerEndpointIpc(opts: EndpointIpcOptions): EndpointIpc {
  let last: EndpointState | null = null

  const pushEndpoint = (state: EndpointState) => {
    last = state
    const win = opts.getWindow()
    if (win && !win.isDestroyed()) {
      win.webContents.send(ENDPOINT_CHANNEL, state)
    }
  }

  ipcMain.handle('endpoint:get', () => last)

  return { pushEndpoint }
}
