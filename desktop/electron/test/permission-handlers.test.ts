import { describe, it, expect, vi } from 'vitest'

// vi.mock is hoisted above imports; the capture must be hoisted with it.
const captured = vi.hoisted(() => ({
  requestHandler: null as ((wc: unknown, permission: string,
    cb: (ok: boolean) => void, details: { mediaTypes?: string[] }) => void) | null,
  checkHandler: null as ((wc: unknown, permission: string, origin: string,
    details: { mediaType?: string }) => boolean) | null,
}))

vi.mock('electron', () => ({
  session: {
    defaultSession: {
      setPermissionRequestHandler: vi.fn((fn: typeof captured.requestHandler) => {
        captured.requestHandler = fn
      }),
      setPermissionCheckHandler: vi.fn((fn: typeof captured.checkHandler) => {
        captured.checkHandler = fn
      }),
    },
  },
}))

import {
  permissionCheckDecision,
  permissionDecision,
  permissionRequestDecision,
  registerBrowserAutomationPermissionHandlers,
  registerPermissionHandlers,
} from '../src/window/permission-handlers'

describe('web permission requests (M-7 default-deny)', () => {
  it('registers check and request handlers on the default session', () => {
    registerPermissionHandlers()
    expect(captured.checkHandler).toBeTypeOf('function')
    expect(captured.requestHandler).toBeTypeOf('function')
  })

  it('denies sensitive permissions while allowing clipboard writes and display capture', () => {
    for (const denied of ['media', 'notifications', 'geolocation', 'midi', 'pointerLock']) {
      expect(permissionDecision(denied)).toBe(false)
    }
    // Clipboard writes support shell copy buttons; display capture is constrained separately
    // by setDisplayMediaRequestHandler to the primary screen.
    expect(permissionDecision('clipboard-sanitized-write')).toBe(true)
    expect(permissionDecision('display-capture')).toBe(true)
    expect(permissionDecision('clipboard-read')).toBe(false)
  })

  it('allows only the video media preflight required before display capture', () => {
    expect(permissionCheckDecision('media', 'video')).toBe(true)
    expect(permissionCheckDecision('media', 'audio')).toBe(false)
    expect(permissionCheckDecision('media', 'unknown')).toBe(false)
  })

  it('allows Electron 43 display-media requests without granting camera or microphone', () => {
    expect(permissionRequestDecision('media', [])).toBe(true)
    expect(permissionRequestDecision('media', ['video'])).toBe(false)
    expect(permissionRequestDecision('media', ['audio'])).toBe(false)
    expect(permissionRequestDecision('media', ['video', 'audio'])).toBe(false)
  })

  it('the registered handlers forward the same decision', () => {
    registerPermissionHandlers()
    const grants: boolean[] = []
    grants.push(captured.checkHandler?.(null as never, 'media', '', { mediaType: 'audio' }) ?? true)
    grants.push(captured.checkHandler?.(null as never, 'media', '', { mediaType: 'video' }) ?? false)
    captured.requestHandler?.(null as never, 'media', (ok) => grants.push(ok), { mediaTypes: ['video'] })
    captured.requestHandler?.(null as never, 'media', (ok) => grants.push(ok), { mediaTypes: [] })
    captured.requestHandler?.(null as never, 'display-capture', (ok) => grants.push(ok), {})
    expect(grants).toEqual([false, true, false, true, true])
  })
})

describe('browser-automation partition sessions (P1-8 default-deny)', () => {
  type RequestHandler = (wc: unknown, permission: string, cb: (ok: boolean) => void) => void
  type CheckHandler = (wc: unknown, permission: string, details?: { mediaType?: string }) => boolean

  const makeSession = () => {
    const s: {
      requestHandler: RequestHandler | null
      checkHandler: CheckHandler | null
      setPermissionRequestHandler: ReturnType<typeof vi.fn>
      setPermissionCheckHandler: ReturnType<typeof vi.fn>
    } = {
      requestHandler: null,
      checkHandler: null,
      setPermissionRequestHandler: vi.fn((fn: RequestHandler) => {
        s.requestHandler = fn
      }),
      setPermissionCheckHandler: vi.fn((fn: CheckHandler) => {
        s.checkHandler = fn
      }),
    }
    return s
  }

  it('registers BOTH handlers on the given partition session', () => {
    const target = makeSession()
    registerBrowserAutomationPermissionHandlers(target)
    expect(target.setPermissionRequestHandler).toHaveBeenCalledTimes(1)
    expect(target.setPermissionCheckHandler).toHaveBeenCalledTimes(1)
    expect(target.requestHandler).toBeTypeOf('function')
    expect(target.checkHandler).toBeTypeOf('function')
  })

  it('the registered handlers deny every permission unconditionally', () => {
    const target = makeSession()
    registerBrowserAutomationPermissionHandlers(target)
    // The automation windows render arbitrary third-party pages: camera, microphone,
    // geolocation, notifications — and even the shell's clipboard/display-capture grants,
    // which are shell-UI features these windows do not have.
    for (const permission of [
      'media', 'geolocation', 'notifications', 'clipboard-sanitized-write', 'clipboard-read',
      'display-capture', 'midi', 'pointerLock', 'persistent-storage',
    ]) {
      expect(target.checkHandler?.(null as never, permission)).toBe(false)
      const grants: boolean[] = []
      target.requestHandler?.(null as never, permission, (ok) => grants.push(ok))
      expect(grants).toEqual([false])
    }
    // The media preflight exceptions the shell session makes (display capture) are NOT made here.
    expect(target.checkHandler?.(null as never, 'media', { mediaType: 'video' } as never)).toBe(false)
  })
})
