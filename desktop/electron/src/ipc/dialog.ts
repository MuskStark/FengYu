import { ipcMain, dialog, BrowserWindow } from 'electron'
import { dirname } from 'node:path'

/**
 * Register the `dialog:open` IPC handler. Returns the chosen path or null; with
 * `multiple: true` it returns every chosen path (still null when cancelled).
 * Filters shape matches the SDK's PluginContext.desktop.pickFile signature.
 *
 * Every dialog reopens at the directory of the previous successful pick (kept for
 * the lifetime of this process), so "add another file" starts where the user
 * just was instead of at the OS default location.
 *
 * `dialog:confirm` backs the renderer's `window.confirm` replacement: sandboxed
 * renderers silently drop the synchronous JS dialogs (electron#7472), so the SPA
 * routes confirmations through this native message box instead.
 */
let lastDialogDirectory: string | null = null

export function registerDialogIpc(): void {
  ipcMain.handle(
    'dialog:confirm',
    async (event, opts: { message: string; title?: string }) => {
      // A malformed invoke (missing/blank message, non-string fields) answers false
      // instead of throwing into the renderer's click path.
      if (typeof opts?.message !== 'string' || opts.message.trim() === '') return false
      const title = typeof opts.title === 'string' ? opts.title : undefined
      const win = BrowserWindow.fromWebContents(event.sender) ?? undefined
      const dialogOpts: Electron.MessageBoxOptions = {
        type: 'question',
        buttons: ['OK', 'Cancel'],
        defaultId: 1,
        cancelId: 1,
        title,
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
      opts: { directory: boolean; multiple?: boolean; filters?: { name: string; extensions: string[] }[] },
    ) => {
      if (!opts || typeof opts !== 'object' || typeof opts.directory !== 'boolean') {
        throw new Error('Malformed file dialog request')
      }
      const win = BrowserWindow.fromWebContents(event.sender) ?? undefined
      const properties: Electron.OpenDialogOptions['properties'] = opts.directory
        ? ['openDirectory']
        : opts.multiple === true ? ['openFile', 'multiSelections'] : ['openFile']
      const dialogOpts: Electron.OpenDialogOptions = {
        properties,
        filters: sanitizeFileFilters(opts?.filters),
      }
      if (lastDialogDirectory !== null) dialogOpts.defaultPath = lastDialogDirectory
      // Attach to the parent window when available (modal); otherwise open a parentless
      // dialog. `showOpenDialog`'s window overload requires a non-null BaseWindow, so we
      // branch instead of using the prior unjustified `win!` assertion.
      const result =
        win !== undefined
          ? await dialog.showOpenDialog(win, dialogOpts)
          : await dialog.showOpenDialog(dialogOpts)
      if (result.canceled || result.filePaths.length === 0) return null
      // Remember where the user picked so the next dialog reopens there.
      lastDialogDirectory = opts.directory ? result.filePaths[0] : dirname(result.filePaths[0])
      return opts.multiple === true && !opts.directory ? result.filePaths : result.filePaths[0]
    },
  )
}

/**
 * Plugin UIs supply filter metadata through the renderer. Treat it as untrusted at the native
 * boundary: bounded strings and extension tokens only, so malformed postMessage data cannot reach
 * Electron's native dialog API.
 */
function sanitizeFileFilters(value: unknown): Electron.FileFilter[] | undefined {
  if (value == null) return undefined
  if (!Array.isArray(value) || value.length > 32) {
    throw new Error('File filters must be an array of at most 32 entries')
  }
  return value.map((raw): Electron.FileFilter => {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
      throw new Error('Each file filter must be an object')
    }
    const filter = raw as { name?: unknown; extensions?: unknown }
    if (typeof filter.name !== 'string' || filter.name.length > 120) {
      throw new Error('File filter names must be strings of at most 120 characters')
    }
    if (!Array.isArray(filter.extensions) || filter.extensions.length > 32) {
      throw new Error('Each file filter accepts at most 32 extensions')
    }
    const extensions = filter.extensions.map((extension): string => {
      if (typeof extension !== 'string'
          || extension.length === 0 || extension.length > 24
          || !/^[A-Za-z0-9_*+-]+$/.test(extension)) {
        throw new Error('File filter extensions contain an unsupported token')
      }
      return extension
    })
    return { name: filter.name, extensions }
  })
}
