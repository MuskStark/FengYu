import { describe, expect, it } from 'vitest'
import { formatDateTime } from './utils'

describe('formatDateTime (locale-aware)', () => {
  const iso = '2026-10-08T09:05:00Z'

  it('renders differently for en and zh locales (no bare host default)', () => {
    const en = formatDateTime(iso, 'en')
    const zh = formatDateTime(iso, 'zh')
    expect(en).toBeTruthy()
    expect(zh).toBeTruthy()
    expect(en).not.toBe(zh)
  })

  it('empty language falls back to the host default; invalid dates render empty', () => {
    expect(formatDateTime(iso, '')).toBeTruthy()
    expect(formatDateTime('not-a-date', 'en')).toBe('')
    expect(formatDateTime(new Date(0), 'en')).toBeTruthy()
  })
})
