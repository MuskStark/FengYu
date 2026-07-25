import { describe, it, expect } from 'vitest'
import { isAppCrash, shouldRestartSetup, StartupAction, startupAction } from '../src/backend/supervisor'

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
  it('false for clean exit (0)', () => {
    expect(isAppCrash(0, false)).toBe(false)
  })
})
