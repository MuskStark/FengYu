import { describe, it, expect } from 'vitest'
import { genToken } from '../src/util/token'

describe('genToken', () => {
  it('contains 256 bits of random token material', () => {
    expect(genToken()).toMatch(/^zf-[0-9a-f]{64}$/)
  })

  it('changes across calls (per-launch variance)', () => {
    expect(genToken()).not.toBe(genToken())
  })
})
