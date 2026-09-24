import { describe, expect, it } from 'vitest'
import { BEE_LEVELS, BEE_TONE_CHIP, beeMark } from './beeLevels'

describe('beeLevels (Infinia Level ladder)', () => {
  it('keeps the five hive levels in ladder order', () => {
    expect([...BEE_LEVELS]).toEqual([0, 1, 2, 3, 4])
  })

  it('each level carries its own emblem and tone', () => {
    expect(beeMark(0)).toMatchObject({ emblem: '🥚', tone: 'muted' })
    expect(beeMark(1)).toMatchObject({ emblem: '🐝', tone: 'accent' })
    expect(beeMark(2)).toMatchObject({ emblem: '🍯', tone: 'success' })
    expect(beeMark(3)).toMatchObject({ emblem: '🛡️', tone: 'danger' })
    expect(beeMark(4)).toMatchObject({ emblem: '👑', tone: 'gold' })
  })

  it('clamps out-of-range levels onto the ladder', () => {
    expect(beeMark(-3).level).toBe(0)
    expect(beeMark(9).level).toBe(4)
    expect(beeMark(2.7).level).toBe(2)
    expect(beeMark(Number.NaN).level).toBe(0)
  })

  it('maps every tone onto a chip variant', () => {
    for (const level of BEE_LEVELS) {
      expect(BEE_TONE_CHIP[beeMark(level).tone]).toBeDefined()
    }
    expect(BEE_TONE_CHIP.gold).toBe('cx-chip--gold')
  })
})
