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
