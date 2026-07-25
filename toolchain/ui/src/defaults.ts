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
    rounded: 'lg',
    elevation: 0,
    height: 34,
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
    density: 'compact',
  },
  VDialog: {
    elevation: 8,
  },
  VMenu: {
    elevation: 4,
  },
  VTooltip: {
    elevation: 4,
  },
  VSnackbar: {
    elevation: 4,
  },
  VNavigationDrawer: {
    elevation: 0,
  },
}
