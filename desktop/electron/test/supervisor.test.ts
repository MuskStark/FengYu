import { EventEmitter } from 'node:events'
import type { ChildProcess } from 'node:child_process'
import { describe, it, expect, vi } from 'vitest'
import {
  isAppCrash,
  shouldRestartSetup,
  StartupAction,
  startupAction,
  superviseSetupRestart,
  type BackendChild,
} from '../src/backend/supervisor'

describe('shouldRestartSetup', () => {
  it('shutdown prevents a setup restart', () => {
    expect(shouldRestartSetup(true, 0)).toBe(false)
  })

  it('restarts only on exit code 0 while running', () => {
    expect(shouldRestartSetup(false, 0)).toBe(true)
    expect(shouldRestartSetup(false, 1)).toBe(false)
    expect(shouldRestartSetup(false, null)).toBe(false)
  })
})

describe('startupAction', () => {
  it('APP mode shows the window without supervision', () => {
    expect(startupAction(false, 24056)).toBe(StartupAction.ShowWindow)
  })

  it('SETUP mode shows the window and supervises the same port', () => {
    expect(startupAction(true, 43123)).toEqual(StartupAction.ShowWindowAndSupervise)
  })
})

describe('isAppCrash', () => {
  it('true for non-zero exit while running', () => {
    expect(isAppCrash(1, false)).toBe(true)
  })
  it('false during shutdown', () => {
    expect(isAppCrash(1, true)).toBe(false)
  })
  it('true for a clean exit while the shell is still running', () => {
    expect(isAppCrash(0, false)).toBe(true)
  })
  it('true for a signal exit while the shell is still running', () => {
    expect(isAppCrash(null, false)).toBe(true)
  })
})

function child(): BackendChild {
  return {
    process: new EventEmitter() as ChildProcess,
    kill: vi.fn(),
    forceKill: vi.fn(),
  }
}

describe('superviseSetupRestart', () => {
  it('treats an exit after the SETUP→APP restart as fatal', async () => {
    const setup = child()
    const app = child()
    let current: BackendChild | null = setup
    const onFatal = vi.fn()

    superviseSetupRestart({
      getChild: () => current,
      setChild: (next) => { current = next },
      restart: async () => ({ child: app, port: 24056, setupMode: false }),
      expectedPort: 24056,
      isShuttingDown: () => false,
      onFatal,
    })

    setup.process.emit('exit', 0, null)
    await new Promise((resolve) => setImmediate(resolve))
    expect(current).toBe(app)

    app.process.emit('exit', 0, null)
    expect(onFatal).toHaveBeenCalledWith(expect.stringMatching(/APP backend exited/))
  })
})
