import { ipcMain, shell } from 'electron'

function httpUrl(value: unknown): string {
  if (typeof value !== 'string') throw new Error('External URL must be a string')
  let parsed: URL
  try {
    parsed = new URL(value)
  } catch {
    throw new Error('External URL is invalid')
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error(`External URL scheme is not allowed: ${parsed.protocol}`)
  }
  return parsed.toString()
}

/** Register the renderer-to-system-browser bridge for OAuth and other trusted links. */
export function registerExternalIpc(): void {
  ipcMain.handle('external:open', async (_event, value: unknown) => {
    await shell.openExternal(httpUrl(value))
  })
}
