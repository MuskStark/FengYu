import { describe, expect, it } from 'vitest'
import { createFengYuVuetify, fengyuCodexDark, fengyuCodexLight } from '../src'

describe('Codex Vuetify theme', () => {
  it('registers dark and light themes with compact defaults', () => {
    const vuetify = createFengYuVuetify({ theme: 'light', locale: 'zh-CN' })
    // `defaults.value` is typed as `DefaultsInstance` (= `undefined | { [k]: undefined | Record }`);
    // the test exercises the configured defaults, so assert the concrete shape once.
    const defaults = vuetify.defaults.value as Record<string, Record<string, unknown>>
    expect(vuetify.theme.global.name.value).toBe('fengyuCodexLight')
    expect(fengyuCodexDark.dark).toBe(true)
    expect(fengyuCodexLight.dark).toBe(false)
    expect(defaults.VBtn.density).toBe('comfortable')
    expect(defaults.VCard.elevation).toBe(0)
  })
})
