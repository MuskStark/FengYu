import { beforeEach, describe, expect, it, vi } from 'vitest'

// Capture the ipcMain handlers the module registers against (boot-ipc.test.ts pattern).
const electron = vi.hoisted(() => ({
  send: vi.fn(),
  handlers: new Map<string, (event: unknown, ...args: unknown[]) => unknown>(),
}))

vi.mock('electron', () => ({
  ipcMain: {
    handle: vi.fn((channel: string, handler: (event: unknown, ...args: unknown[]) => unknown) => {
      electron.handlers.set(channel, handler)
    }),
  },
}))

import { registerEndpointIpc } from '../src/ipc/endpoint'

const fakeWindow = { isDestroyed: () => false, webContents: { send: electron.send } }

function endpoint(getWindow: () => unknown) {
  return registerEndpointIpc({ getWindow: getWindow as never })
}

function handle<T>(channel: string, ...args: unknown[]): T {
  const handler = electron.handlers.get(channel)
  if (!handler) throw new Error(`no handler registered for ${channel}`)
  return handler({} as never, ...args) as T
}

beforeEach(() => {
  electron.send.mockClear()
  electron.handlers.clear()
})

describe('registerEndpointIpc', () => {
  it('pushes the endpoint to the live window', () => {
    const ipc = endpoint(() => fakeWindow)
    ipc.pushEndpoint({ apiBase: 'http://127.0.0.1:24056', token: 'zf-test' })
    expect(electron.send).toHaveBeenCalledWith('endpoint:ready', {
      apiBase: 'http://127.0.0.1:24056',
      token: 'zf-test',
    })
  })

  it('drops the push (but remembers the state) when no window exists yet', () => {
    const ipc = endpoint(() => null)
    ipc.pushEndpoint({ apiBase: 'http://127.0.0.1:24057', token: 'zf-x' })
    expect(electron.send).not.toHaveBeenCalled()
    // The renderer mounts later and pulls the last state — the dropped push is recovered.
    expect(handle('endpoint:get')).toEqual({ apiBase: 'http://127.0.0.1:24057', token: 'zf-x' })
  })

  it('drops the push when the window is destroyed', () => {
    const destroyed = { isDestroyed: () => true, webContents: { send: electron.send } }
    const ipc = endpoint(() => destroyed)
    ipc.pushEndpoint({ apiBase: 'http://127.0.0.1:1', token: 't' })
    expect(electron.send).not.toHaveBeenCalled()
  })

  it('endpoint:get returns null before any push', () => {
    endpoint(() => fakeWindow)
    expect(handle('endpoint:get')).toBeNull()
  })

  it('endpoint:get returns the LATEST pushed state', () => {
    const ipc = endpoint(() => fakeWindow)
    ipc.pushEndpoint({ apiBase: 'http://127.0.0.1:1', token: 'first' })
    ipc.pushEndpoint({ apiBase: 'http://127.0.0.1:2', token: 'second' })
    expect(handle('endpoint:get')).toEqual({ apiBase: 'http://127.0.0.1:2', token: 'second' })
  })
})
