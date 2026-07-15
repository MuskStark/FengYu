import type { ThemeDefinition } from 'vuetify'

/**
 * Codex-desktop monochrome palettes — the single source of truth for color in
 * generated FengYu plugin UIs. These are the same near-neutral values used by
 * the host web shell (`frontend/src/plugins/md3-themes.ts`), renamed to make
 * the FengYu theme names explicit. This package is standalone and does NOT
 * import from `frontend/`; the values are duplicated intentionally.
 *
 * The palette models the Codex desktop app: near-black canvas, layered dark
 * greys, off-white text, hairline borders, and an inverted "primary" so that
 * send/confirm buttons render as Codex's signature inverted chips.
 */

export const fengyuCodexLight: ThemeDefinition = {
  dark: false,
  colors: {
    background: '#ffffff',
    surface: '#ffffff',
    'surface-variant': '#f0f0f0',
    'on-surface': '#0d0d0d',
    'surface-bright': '#ffffff',
    'surface-container': '#f7f7f7',
    'surface-container-high': '#f0f0f0',
    'surface-container-highest': '#e9e9e9',
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
    outline: '#d5d5d5',
    'outline-variant': '#e7e7e7',
  },
  variables: {
    'border-color': '#e5e5e5',
    'border-opacity': 1,
  },
}

export const fengyuCodexDark: ThemeDefinition = {
  dark: true,
  colors: {
    background: '#0d0d0d',
    surface: '#0d0d0d',
    'surface-variant': '#1f1f1f',
    'on-surface': '#ededed',
    'surface-bright': '#2a2a2a',
    'surface-container': '#161616',
    'surface-container-high': '#1c1c1c',
    'surface-container-highest': '#232323',
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
    outline: '#333333',
    'outline-variant': '#262626',
  },
  variables: {
    'border-color': '#2a2a2a',
    'border-opacity': 1,
  },
}
