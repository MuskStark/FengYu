import { describe, it, expect } from 'vitest'
import { shouldRestartSetup, StartupAction, startupAction } from '../src/backend/supervisor'

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
