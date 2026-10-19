import { beforeEach, describe, expect, it, vi } from 'vitest'

const electron = vi.hoisted(() => ({
  ipcMain: {
    handle: vi.fn((channel: string, handler: (...args: unknown[]) => unknown) => {
      ;(electron as unknown as Record<string, unknown>)[channel] = handler
    }),
  },
  dialog: {
    showOpenDialog: vi.fn(async () => ({ canceled: true, filePaths: [] })),
  },
  BrowserWindow: {
    fromWebContents: vi.fn(() => undefined),
  },
}))

vi.mock('electron', () => electron)

import { registerDialogIpc } from '../src/ipc/dialog'

function invoke(request: unknown): Promise<unknown> {
  const handler = (electron as unknown as Record<string, unknown>)['dialog:open'] as
    ((event: unknown, request: unknown) => Promise<unknown>)
  if (typeof handler !== 'function') throw new Error('dialog:open handler was not registered')
  return handler({}, request)
}

describe('dialog:open filter boundary', () => {
  beforeEach(() => {
    electron.dialog.showOpenDialog.mockClear()
  })

  it('passes bounded plugin filters to the native dialog', async () => {
    registerDialogIpc()
    await invoke({ directory: false, filters: [{ name: 'Documents', extensions: ['pdf', 'txt'] }] })

    // No parent window resolves in this harness, so the parentless single-argument
    // overload runs — never a `showOpenDialog(undefined, …)` call.
    expect(electron.dialog.showOpenDialog).toHaveBeenCalledWith({
      properties: ['openFile'],
      filters: [{ name: 'Documents', extensions: ['pdf', 'txt'] }],
    })
  })

  it('rejects malformed plugin-controlled filter shapes before the native API', async () => {
    registerDialogIpc()
    await expect(invoke({
      directory: false,
      filters: [{ name: 'Bad', extensions: ['pdf; rm -rf /'] }],
    })).rejects.toThrow(/unsupported token/)
    await expect(invoke({
      directory: false,
      filters: [{ name: 'Bad', extensions: 'pdf' }],
    })).rejects.toThrow(/at most 32 extensions/)
    expect(electron.dialog.showOpenDialog).not.toHaveBeenCalled()
  })
})

describe('dialog:open multi-select and last-directory memory', () => {
  beforeEach(() => {
    electron.dialog.showOpenDialog.mockClear()
    electron.dialog.showOpenDialog.mockImplementation(async () => ({ canceled: true, filePaths: [] }))
  })

  it('returns every path when multiple is requested', async () => {
    registerDialogIpc()
    electron.dialog.showOpenDialog.mockResolvedValue({
      canceled: false, filePaths: ['/tmp/docs/a.pdf', '/tmp/docs/b.pdf'],
    })
    const picked = await invoke({ directory: false, multiple: true })
    expect(picked).toEqual(['/tmp/docs/a.pdf', '/tmp/docs/b.pdf'])
    expect(electron.dialog.showOpenDialog).toHaveBeenCalledWith(expect.objectContaining({
      properties: ['openFile', 'multiSelections'],
    }))
  })

  it('reopens the next dialog at the previous pick directory', async () => {
    registerDialogIpc()
    electron.dialog.showOpenDialog.mockResolvedValueOnce({
      canceled: false, filePaths: ['/home/user/attachments/report.pdf'],
    })
    await invoke({ directory: false })
    // A cancelled follow-up must not change the remembered directory.
    electron.dialog.showOpenDialog.mockResolvedValueOnce({ canceled: true, filePaths: [] })
    await invoke({ directory: false })
    electron.dialog.showOpenDialog.mockResolvedValueOnce({ canceled: true, filePaths: [] })
    await invoke({ directory: true })

    expect(electron.dialog.showOpenDialog).toHaveBeenNthCalledWith(2, expect.objectContaining({
      defaultPath: '/home/user/attachments',
    }))
    // Directory picks remember the directory itself.
    expect(electron.dialog.showOpenDialog).toHaveBeenNthCalledWith(3, expect.objectContaining({
      defaultPath: '/home/user/attachments',
    }))
  })
})
