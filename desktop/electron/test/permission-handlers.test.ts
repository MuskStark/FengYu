import { describe, it, expect, vi } from 'vitest'

// vi.mock is hoisted above imports; the capture must be hoisted with it.
const captured = vi.hoisted(() => ({
  handler: null as ((wc: unknown, permission: string, cb: (ok: boolean) => void) => void) | null,
}))

vi.mock('electron', () => ({
  session: {
    defaultSession: {
      setPermissionRequestHandler: vi.fn((fn: typeof captured.handler) => {
        captured.handler = fn
      }),
    },
  },
}))

import { permissionDecision, registerPermissionHandlers } from '../src/window/permission-handlers'

describe('web permission requests (M-7 default-deny)', () => {
  it('registers a handler on the default session', () => {
    registerPermissionHandlers()
    expect(captured.handler).toBeTypeOf('function')
  })

  it('denies media, notifications, geolocation — and allows only sanitized clipboard writes', () => {
    for (const denied of ['media', 'notifications', 'geolocation', 'midi', 'pointerLock']) {
      expect(permissionDecision(denied)).toBe(false)
    }
    // The shell's copy-to-clipboard buttons ride the sanitized write path.
    expect(permissionDecision('clipboard-sanitized-write')).toBe(true)
    expect(permissionDecision('clipboard-read')).toBe(false)
  })

  it('the registered handler forwards the decision', () => {
    registerPermissionHandlers()
    const grants: boolean[] = []
    captured.handler?.(null as never, 'media', (ok) => grants.push(ok))
    captured.handler?.(null as never, 'clipboard-sanitized-write', (ok) => grants.push(ok))
    expect(grants).toEqual([false, true])
  })
})
