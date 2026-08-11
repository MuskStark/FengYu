import { describe, expect, it } from 'vitest'
import { createFengYuI18n, normalizeFengYuLocale } from '../src'

describe('createFengYuI18n', () => {
  it('tracks host locale, interpolates values, and falls back safely', () => {
    const i18n = createFengYuI18n({
      en: { greeting: 'Hello {0}', fallback: 'English only' },
      zh: { greeting: '你好 {0}' },
    })
    i18n.applyEnvironment({ locale: 'zh-TW' })
    expect(i18n.locale.value).toBe('zh')
    expect(i18n.t('greeting', 'Ada')).toBe('你好 Ada')
    expect(i18n.t('fallback')).toBe('English only')
    expect(i18n.t('missing')).toBe('missing')
  })

  it('normalizes unsupported and blank locales to English', () => {
    expect(normalizeFengYuLocale('zh-Hans')).toBe('zh')
    expect(normalizeFengYuLocale('fr-FR')).toBe('en')
    expect(normalizeFengYuLocale()).toBe('en')
  })
})
