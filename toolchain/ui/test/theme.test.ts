import { describe, expect, it } from 'vitest'
import { createFengYuVuetify, fengyuCodexDark, fengyuCodexLight } from '../src'
import type { ThemeDefinition } from 'vuetify'

/**
 * Reference Codex palettes — the single source of truth lives in the host web
 * shell at `frontend/src/plugins/md3-themes.ts` (`md3Light` / `md3Dark`). The UI
 * kit intentionally duplicates these values (see `src/theme.ts`) so it stays
 * standalone; this test guards the duplication against drift. When the palette
 * changes, update `frontend/src/plugins/md3-themes.ts` first, then mirror the
 * `colors` / `variables` objects here and in `src/theme.ts`.
 */
const expectedLight: ThemeDefinition = {
  dark: false,
  colors: {
    background: '#ffffff',
    surface: '#ffffff',
    'surface-variant': '#f0f0f0',
    'on-surface': '#0d0d0d',
    'surface-bright': '#ffffff',
    'surface-container': '#f7f7f7',
    'surface-container-low': '#fafaf8',
    'surface-container-high': '#f0f0f0',
    'surface-container-highest': '#e9e9e9',
    'surface-light': '#f5f5f5',
    'on-background': '#0d0d0d',
    'on-surface-variant': '#333333',
    'on-surface-bright': '#0d0d0d',
    'on-surface-light': '#0d0d0d',
    // Inverted primary — dark button / light text, Codex light-mode style.
    primary: '#0d0d0d',
    'on-primary': '#ffffff',
    'primary-container': '#ececec',
    'on-primary-container': '#0d0d0d',
    secondary: '#5a5a5a',
    'on-secondary': '#ffffff',
    'secondary-container': '#ececec',
    tertiary: '#3d6b5a',
    'on-tertiary': '#ffffff',
    'tertiary-container': '#d6ece3',
    error: '#c0392b',
    'on-error': '#ffffff',
    'error-container': '#fbe3e0',
    warning: '#9a6700',
    'on-warning': '#ffffff',
    'warning-container': '#fff4d6',
    'on-warning-container': '#5f4200',
    outline: '#d5d5d5',
    'outline-variant': '#e7e7e7',
  },
  variables: {
    'border-color': '#e5e5e5',
    'border-opacity': 1,
  },
}

const expectedDark: ThemeDefinition = {
  dark: true,
  colors: {
    background: '#0d0d0d',
    surface: '#0d0d0d',
    'surface-variant': '#1f1f1f',
    'on-surface': '#ededed',
    'surface-bright': '#2a2a2a',
    'surface-container': '#161616',
    'surface-container-low': '#141413',
    'surface-container-high': '#1c1c1c',
    'surface-container-highest': '#232323',
    'surface-light': '#1c1c1c',
    'on-background': '#ededed',
    'on-surface-variant': '#cccccc',
    'on-surface-bright': '#ededed',
    'on-surface-light': '#ededed',
    // Light "primary" — near-white button / dark text, Codex dark-mode style.
    primary: '#ededed',
    'on-primary': '#0d0d0d',
    'primary-container': '#2a2a2a',
    'on-primary-container': '#ededed',
    secondary: '#a0a0a0',
    'on-secondary': '#0d0d0d',
    'secondary-container': '#232323',
    tertiary: '#8fd6bd',
    'on-tertiary': '#0d0d0d',
    'tertiary-container': '#1e332b',
    error: '#f2827a',
    'on-error': '#3b0f0b',
    'error-container': '#3b1512',
    warning: '#e4b65b',
    'on-warning': '#2b1d00',
    'warning-container': '#3b2d0d',
    'on-warning-container': '#f4d58b',
    outline: '#333333',
    'outline-variant': '#262626',
  },
  variables: {
    'border-color': '#2a2a2a',
    'border-opacity': 1,
  },
}

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

  it('keeps plugin colors and variables identical to the host themes', () => {
    expect(fengyuCodexLight.colors).toEqual(expectedLight.colors)
    expect(fengyuCodexLight.variables).toEqual(expectedLight.variables)
    expect(fengyuCodexDark.colors).toEqual(expectedDark.colors)
    expect(fengyuCodexDark.variables).toEqual(expectedDark.variables)
  })
})
