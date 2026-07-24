import { describe, it, expect } from 'vitest'
import { genToken } from '../src/util/token'

describe('genToken', () => {
  it('matches the zf-{hex}-{hex} format', () => {
    expect(genToken()).toMatch(/^zf-[0-9a-f]+-[0-9a-f]+$/)
  })

  it('changes across calls (per-launch variance)', () => {
    expect(genToken()).not.toBe(genToken())
  })
})
