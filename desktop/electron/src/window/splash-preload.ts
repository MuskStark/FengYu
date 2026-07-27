import { contextBridge, ipcRenderer } from 'electron'

// Minimal bridge: lets splash.html subscribe to main-process progress updates
// without exposing any privileged surface. The payload shape mirrors what
// create-splash.ts sends via webContents.send('splash:progress', ...).
contextBridge.exposeInMainWorld('splash', {
  onProgress: (cb: (p: { stage: string; ts: number }) => void): void => {
    ipcRenderer.on('splash:progress', (_event, payload) => cb(payload))
  },
})
