import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * P1-8: the automation browser window's partition session must carry default-deny web
 * permission handlers. Electron auto-approves permission requests on sessions without
 * handlers (electron#12931), and these windows render arbitrary third-party pages that
 * browser_navigate can drive anywhere — without this registration a page could silently
 * switch on the camera or read geolocation.
 */

const captured = vi.hoisted(() => ({
  windowOptions: null as Record<string, unknown> | null,
  session: null as {
    setPermissionRequestHandler: ReturnType<typeof vi.fn>
    setPermissionCheckHandler: ReturnType<typeof vi.fn>
  } | null,
}))

vi.mock('electron', () => ({
  BrowserWindow: vi.fn().mockImplementation(function (options: Record<string, unknown>) {
    captured.windowOptions = options
    return {
      isDestroyed: vi.fn(() => false),
      destroy: vi.fn(),
      webContents: {
        on: vi.fn(),
        setWindowOpenHandler: vi.fn(),
        getURL: vi.fn(() => ''),
        getTitle: vi.fn(() => ''),
        debugger: { isAttached: vi.fn(() => false), attach: vi.fn(), detach: vi.fn() },
        session: captured.session,
      },
    }
  }),
  shell: { openExternal: vi.fn() },
}))

import { BrowserSession } from '../src/browser/session'

beforeEach(() => {
  captured.windowOptions = null
  captured.session = {
    setPermissionRequestHandler: vi.fn(),
    setPermissionCheckHandler: vi.fn(),
  }
})

describe('BrowserSession.ensureWindow permission posture', () => {
  it('creates the window on the persistent automation partition', () => {
    const s = new BrowserSession()
    s.ensureWindow()
    expect(captured.windowOptions).toMatchObject({
      webPreferences: { partition: 'persist:fengyu-browser', sandbox: true, contextIsolation: true },
    })
  })

  it('registers default-deny permission handlers on the partition session', () => {
    const s = new BrowserSession()
    s.ensureWindow()
    expect(captured.session?.setPermissionRequestHandler).toHaveBeenCalledTimes(1)
    expect(captured.session?.setPermissionCheckHandler).toHaveBeenCalledTimes(1)

    // The registered decision must be an unconditional deny: camera/microphone requests
    // from a navigated third-party page are refused, not auto-approved.
    const requestHandler = captured.session!.setPermissionRequestHandler.mock.calls[0][0] as (
      wc: unknown, permission: string, cb: (ok: boolean) => void
    ) => void
    const grants: boolean[] = []
    requestHandler(null, 'media', (ok) => grants.push(ok))
    requestHandler(null, 'geolocation', (ok) => grants.push(ok))
    expect(grants).toEqual([false, false])
  })

  it('re-registers the handlers when the window is re-created after a close', () => {
    const s = new BrowserSession()
    const first = s.ensureWindow()
    s.close()
    const second = s.ensureWindow()
    expect(second).not.toBe(first)
    // close() cleared the window, so ensureWindow built a fresh one and re-armed its
    // partition handlers — the deny posture cannot be lost across the session lifecycle.
    expect(captured.session?.setPermissionRequestHandler).toHaveBeenCalledTimes(2)
  })
})
