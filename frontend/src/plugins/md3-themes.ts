import type { ThemeDefinition } from 'vuetify'

/**
 * Google default Material Design 3 baseline palette (purple-toned).
 * Source: M3 baseline (the set Material Theme Builder emits for a purple
 * seed). These are the single source of truth for color in the web
 * frontend (the legacy hand-written token system has been retired).
 */

export const md3Light: ThemeDefinition = {
  dark: false,
  colors: {
    background: '#FEF7FF',
    surface: '#FEF7FF',
    'surface-variant': '#E7E0EC',
    'on-surface': '#1D1B20',
    'surface-bright': '#FEF7FF',
    'surface-container': '#F3EDF7',
    'surface-container-high': '#ECE6F0',
    'surface-container-highest': '#E6E0E9',
    primary: '#6750A4',
    'on-primary': '#FFFFFF',
    'primary-container': '#EADDFF',
    'on-primary-container': '#21005D',
    secondary: '#625B71',
    'on-secondary': '#FFFFFF',
    'secondary-container': '#E8DEF8',
    tertiary: '#7D5260',
    'on-tertiary': '#FFFFFF',
    'tertiary-container': '#FFD8E4',
    error: '#B3261E',
    'on-error': '#FFFFFF',
    'error-container': '#F9DEDC',
    outline: '#79747E',
    'outline-variant': '#CAC4D0',
  },
  variables: {
    'border-color': '#79747E',
    'border-opacity': 1,
  },
}

export const md3Dark: ThemeDefinition = {
  dark: true,
  colors: {
    background: '#141218',
    surface: '#141218',
    'surface-variant': '#49454F',
    'on-surface': '#E6E0E9',
    'surface-bright': '#3B383E',
    'surface-container': '#211F26',
    'surface-container-high': '#2B2930',
    'surface-container-highest': '#36343B',
    primary: '#D0BCFF',
    'on-primary': '#381E72',
    'primary-container': '#4F378B',
    'on-primary-container': '#EADDFF',
    secondary: '#CCC2DC',
    'on-secondary': '#332D41',
    'secondary-container': '#4A4458',
    tertiary: '#EFB8C8',
    'on-tertiary': '#492532',
    'tertiary-container': '#633B48',
    error: '#F2B8B5',
    'on-error': '#601410',
    'error-container': '#8C1D18',
    outline: '#938F99',
    'outline-variant': '#49454F',
  },
  variables: {
    'border-color': '#938F99',
    'border-opacity': 1,
  },
}
