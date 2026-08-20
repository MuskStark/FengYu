import { describe, expect, it, vi, beforeEach } from 'vitest'

// Capture the ipcMain handler and the dialog surface the module registers against.
// vi.mock is hoisted above imports, so the capture object must be hoisted too.
const electron = vi.hoisted(() => {
  return {
    showMessageBox: vi.fn(),
    handle: null as
      | ((event: unknown, opts: { message: string; title?: string }) => unknown)
      | null,
    channels: [] as string[],
  }
})

vi.mock('electron', () => ({
  dialog: {
    showMessageBox: (...args: unknown[]) => electron.showMessageBox(...args),
  },
  ipcMain: {
    handle: vi.fn((channel: string, handler: typeof electron.handle) => {
      electron.channels.push(channel)
      if (channel === 'dialog:confirm') electron.handle = handler
    }),
  },
  BrowserWindow: {
    fromWebContents: () => undefined,
  },
}))

import { registerDialogIpc } from '../src/ipc/dialog'

beforeEach(() => {
  electron.channels.length = 0
  electron.handle = null
  vi.clearAllMocks()
  registerDialogIpc()
})

describe('dialog:confirm', () => {
  it('registers alongside dialog:open and maps OK to true', async () => {
    expect(electron.channels).toContain('dialog:confirm')
    expect(electron.channels).toContain('dialog:open')

    electron.showMessageBox.mockResolvedValue({ response: 0 })
    const result = await electron.handle!({} as never, { message: 'Uninstall this plugin?' })
    expect(result).toBe(true)
    expect(electron.showMessageBox).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'Uninstall this plugin?', noLink: true }),
    )
  })

  it('maps Cancel (response 1) to false', async () => {
    electron.showMessageBox.mockResolvedValue({ response: 1 })
    const result = await electron.handle!({} as never, { message: 'Delete data too?' })
    expect(result).toBe(false)
  })

  it('defaults the focus to Cancel so Enter does not confirm a destructive action', async () => {
    electron.showMessageBox.mockResolvedValue({ response: 1 })
    await electron.handle!({} as never, { message: 'x' })
    const opts = electron.showMessageBox.mock.calls[0][0] as Electron.MessageBoxOptions
    expect(opts.defaultId).toBe(1)
    expect(opts.cancelId).toBe(1)
  })
})
