import type { DefaultsInstance } from 'vuetify'

/**
 * FengYu Codex global Vuetify defaults.
 *
 * A compact, low-elevation, softly-rounded baseline that matches the Codex
 * desktop look: comfortable-density inputs/buttons, flat cards, dialogs/menus
 * that float on the smallest elevation needed to read, and `lg`/`xl` rounding.
 * These are layered on top of the md3 blueprint defaults.
 */
export const fengyuDefaults: DefaultsInstance = {
  global: {
    rounded: 'lg',
  },
  VBtn: {
    density: 'comfortable',
    rounded: 'xl',
  },
  VTextField: {
    density: 'comfortable',
    variant: 'outlined',
  },
  VTextarea: {
    density: 'comfortable',
    variant: 'outlined',
  },
  VSelect: {
    density: 'comfortable',
    variant: 'outlined',
  },
  VAutocomplete: {
    density: 'comfortable',
    variant: 'outlined',
  },
  VCombobox: {
    density: 'comfortable',
    variant: 'outlined',
  },
  VCard: {
    elevation: 0,
    rounded: 'lg',
  },
  VSheet: {
    elevation: 0,
  },
  VList: {
    density: 'comfortable',
  },
  VDialog: {
    elevation: 24,
  },
  VMenu: {
    elevation: 8,
  },
  VTooltip: {
    elevation: 4,
  },
  VSnackbar: {
    elevation: 8,
  },
  VNavigationDrawer: {
    elevation: 0,
  },
}
