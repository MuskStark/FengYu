import { describe, it, expect } from 'vitest'
import { pickLocale } from '../src/window/splash-i18n'

describe('pickLocale', () => {
  it('returns zh for BCP-47 zh variants', () => {
    expect(pickLocale('zh-CN')).toBe('zh')
    expect(pickLocale('zh-TW')).toBe('zh')
    expect(pickLocale('zh')).toBe('zh')
  })

  it('returns en for English locales', () => {
    expect(pickLocale('en-US')).toBe('en')
    expect(pickLocale('en-GB')).toBe('en')
    expect(pickLocale('en')).toBe('en')
  })

  it('falls back to en for any non-zh locale', () => {
    expect(pickLocale('ja-JP')).toBe('en')
    expect(pickLocale('de-DE')).toBe('en')
    expect(pickLocale('fr')).toBe('en')
  })

  it('falls back to en for empty or malformed input', () => {
    expect(pickLocale('')).toBe('en')
    expect(pickLocale('   ')).toBe('en')
  })
})
