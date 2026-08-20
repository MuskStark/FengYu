import { ipcMain, dialog, BrowserWindow } from 'electron'

/**
 * Register the `dialog:open` IPC handler. Returns the chosen path or null.
 * Filters shape matches the SDK's PluginContext.desktop.pickFile signature.
 *
 * `dialog:confirm` backs the renderer's `window.confirm` replacement: sandboxed
 * renderers silently drop the synchronous JS dialogs (electron#7472), so the SPA
 * routes confirmations through this native message box instead.
 */
export function registerDialogIpc(): void {
  ipcMain.handle(
    'dialog:confirm',
    async (event, opts: { message: string; title?: string }) => {
      const win = BrowserWindow.fromWebContents(event.sender) ?? undefined
      const dialogOpts: Electron.MessageBoxOptions = {
        type: 'question',
        buttons: ['OK', 'Cancel'],
        defaultId: 1,
        cancelId: 1,
        title: opts.title,
        message: opts.message,
        noLink: true,
      }
      const result =
        win !== undefined
          ? await dialog.showMessageBox(win, dialogOpts)
          : await dialog.showMessageBox(dialogOpts)
      return result.response === 0
    },
  )

  ipcMain.handle(
    'dialog:open',
    async (
      event,
      opts: { directory: boolean; filters?: { name: string; extensions: string[] }[] },
    ) => {
      const win = BrowserWindow.fromWebContents(event.sender) ?? undefined
      const dialogOpts: Electron.OpenDialogOptions = {
        properties: opts.directory ? ['openDirectory'] : ['openFile'],
        filters: opts.filters?.map((f) => ({ name: f.name, extensions: f.extensions })),
      }
      // Attach to the parent window when available (modal); otherwise open a parentless
      // dialog. `showOpenDialog`'s window overload requires a non-null BaseWindow, so we
      // branch instead of using the prior unjustified `win!` assertion.
      const result =
        win !== undefined
          ? await dialog.showOpenDialog(win, dialogOpts)
          : await dialog.showOpenDialog(dialogOpts)
      if (result.canceled || result.filePaths.length === 0) return null
      return result.filePaths[0]
    },
  )
}
