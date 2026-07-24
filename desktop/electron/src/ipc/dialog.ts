import { ipcMain, dialog, BrowserWindow } from 'electron'

/**
 * Register the `dialog:open` IPC handler. Returns the chosen path or null.
 * Filters shape matches the SDK's PluginContext.desktop.pickFile signature.
 */
export function registerDialogIpc(): void {
  ipcMain.handle(
    'dialog:open',
    async (
      event,
      opts: { directory: boolean; filters?: { name: string; extensions: string[] }[] },
    ) => {
      const win = BrowserWindow.fromWebContents(event.sender) ?? undefined
      const result = await dialog.showOpenDialog(win!, {
        properties: opts.directory ? ['openDirectory'] : ['openFile'],
        filters: opts.filters?.map((f) => ({ name: f.name, extensions: f.extensions })),
      })
      if (result.canceled || result.filePaths.length === 0) return null
      return result.filePaths[0]
    },
  )
}
