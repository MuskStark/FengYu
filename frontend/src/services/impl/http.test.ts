import { describe, expect, it } from 'vitest'
import { isHealthProbe } from './http'

describe('isHealthProbe (token-free surface is an exact path match)', () => {
  it('matches the bare probe and query/hash variants', () => {
    expect(isHealthProbe('/api/health')).toBe(true)
    expect(isHealthProbe('/api/health?probe=1')).toBe(true)
    expect(isHealthProbe('/api/health#x')).toBe(true)
    // A baseURL-prefixed absolute form still names the same endpoint path.
    expect(isHealthProbe('http://127.0.0.1:24056/api/health')).toBe(true)
  })

  it('does NOT strip the token from paths that merely CONTAIN the substring', () => {
    expect(isHealthProbe('/api/health-history')).toBe(false)
    expect(isHealthProbe('/api/health/status')).toBe(false)
    expect(isHealthProbe('/api/ai/chat')).toBe(false)
    expect(isHealthProbe('/api/setup/status')).toBe(false)
  })
})
