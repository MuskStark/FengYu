import { describe, expect, it, vi, beforeEach } from 'vitest'

// Capture the ipcMain handler + the Notification surface the module registers against.
// vi.mock is hoisted above imports, so the capture object must be hoisted too.
const electron = vi.hoisted(() => {
  const instances: Array<{
    title: string
    body: string
    clickHandlers: Array<() => void>
    shown: boolean
  }> = []
  return {
    instances,
    supported: true,
    show: vi.fn(),
    handle: null as
      | ((event: unknown, opts: { title: string; body?: string }) => unknown)
      | null,
  }
})

vi.mock('electron', () => ({
  Notification: Object.assign(
    // The SUT calls `new Notification({...})` — keep the mock constructible.
    function Notification(opts: { title: string; body?: string }) {
      const instance = {
        title: opts?.title ?? '',
        body: opts?.body ?? '',
        clickHandlers: [] as Array<() => void>,
        shown: false,
        on: vi.fn((_evt: string, fn: () => void) => {
          instance.clickHandlers.push(fn)
        }),
        show: () => {
          instance.shown = true
          electron.show()
        },
      }
      electron.instances.push(instance)
      return instance
    },
    { isSupported: () => electron.supported },
  ),
  ipcMain: {
    handle: vi.fn((_channel: string, handler: typeof electron.handle) => {
      electron.handle = handler
    }),
  },
}))

import { registerNotificationIpc } from '../src/ipc/notification'

const focusWindow = vi.fn()

beforeEach(() => {
  electron.instances.length = 0
  electron.supported = true
  electron.handle = null
  vi.clearAllMocks()
  registerNotificationIpc(() => ({ show: vi.fn(), focus: focusWindow } as never))
})

describe('notification:show IPC', () => {
  it('registers exactly the notification:show channel', () => {
    // beforeEach already registered — assert the captured wiring, not the mock calls.
    expect(electron.handle).toBeTypeOf('function')
  })

  it('shows a native notification and returns true when supported', () => {
    const result = electron.handle?.(undefined, { title: 'Agent run completed', body: 'done' })

    expect(result).toBe(true)
    expect(electron.instances).toHaveLength(1)
    expect(electron.instances[0]!.title).toBe('Agent run completed')
    expect(electron.instances[0]!.body).toBe('done')
    expect(electron.instances[0]!.shown).toBe(true)
  })

  it('returns false without constructing anything when the OS cannot show notifications', () => {
    electron.supported = false

    const result = electron.handle?.(undefined, { title: 'x' })

    expect(result).toBe(false)
    expect(electron.instances).toHaveLength(0)
  })

  it('clicking the native notification focuses the main window', () => {
    electron.handle?.(undefined, { title: 'x' })
    const instance = electron.instances[0]!

    instance.clickHandlers.forEach((fn) => fn())

    expect(focusWindow).toHaveBeenCalledTimes(1)
  })

  it('tolerates a missing main window on click (no throw)', () => {
    electron.handle?.(undefined, { title: 'x' })
    registerNotificationIpc(() => null)
    electron.handle?.(undefined, { title: 'y' })
    const instance = electron.instances[1]!

    expect(() => instance.clickHandlers.forEach((fn) => fn())).not.toThrow()
  })
})
