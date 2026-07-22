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
    background: '#fbfbfa',
    surface: '#ffffff',
    'surface-variant': '#f2f2f0',
    'on-surface': '#171716',
    'surface-bright': '#ffffff',
    'surface-container': '#f7f7f5',
    'surface-container-low': '#fafaf8',
    'surface-container-high': '#efefed',
    'surface-container-highest': '#e7e7e4',
    // Inverted primary — dark button / light text, Codex light-mode style.
    primary: '#1f1f1e',
    'on-primary': '#ffffff',
    'primary-container': '#e9e9e6',
    'on-primary-container': '#171716',
    secondary: '#686866',
    'on-secondary': '#ffffff',
    'secondary-container': '#ececea',
    tertiary: '#35745c',
    'on-tertiary': '#ffffff',
    'tertiary-container': '#d6ece3',
    error: '#c0392b',
    'on-error': '#ffffff',
    'error-container': '#fbe3e0',
    warning: '#9a6700',
    'on-warning': '#ffffff',
    'warning-container': '#fff4d6',
    'on-warning-container': '#5f4200',
    outline: '#d1d1ce',
    'outline-variant': '#e4e4e1',
  },
  variables: {
    'border-color': '#dededb',
    'border-opacity': 1,
  },
}

export const fengyuCodexDark: ThemeDefinition = {
  dark: true,
  colors: {
    background: '#111110',
    surface: '#151514',
    'surface-variant': '#232321',
    'on-surface': '#ececea',
    'surface-bright': '#2c2c2a',
    'surface-container': '#191918',
    'surface-container-low': '#141413',
    'surface-container-high': '#222220',
    'surface-container-highest': '#2a2a28',
    // Light "primary" — near-white button / dark text, Codex dark-mode style.
    primary: '#f1f1ef',
    'on-primary': '#171716',
    'primary-container': '#30302e',
    'on-primary-container': '#f1f1ef',
    secondary: '#a9a9a5',
    'on-secondary': '#0d0d0d',
    'secondary-container': '#292927',
    tertiary: '#8fd3b8',
    'on-tertiary': '#0d0d0d',
    'tertiary-container': '#1e332b',
    error: '#ef827a',
    'on-error': '#3b0f0b',
    'error-container': '#3b1512',
    warning: '#e4b65b',
    'on-warning': '#2b1d00',
    'warning-container': '#3b2d0d',
    'on-warning-container': '#f4d58b',
    outline: '#3d3d3a',
    'outline-variant': '#2c2c2a',
  },
  variables: {
    'border-color': '#30302e',
    'border-opacity': 1,
  },
}
