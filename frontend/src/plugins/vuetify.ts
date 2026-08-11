import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import { md3 } from 'vuetify/blueprints'
import { aliases, mdi } from 'vuetify/iconsets/mdi'
import '@mdi/font/css/materialdesignicons.css'
import 'vuetify/styles'
import { md3Dark, md3Light } from './md3-themes'

/**
 * The single shared Vuetify app-plugin instance for the whole web shell.
 * - MD3 blueprint (Material Design 3 component defaults).
 * - Google-default MD3 baseline palette (purple primary).
 * - Dual theme (dark default); flipped via `vuetify.theme.global.name.value`
 *   from stores/theme.ts (NOT useTheme(), which needs a component context).
 *
 * Sandboxed plugin iframes use their own `@infinia/plugin-ui` instance and
 * receive theme changes through the versioned SDK environment bridge.
 */
export const vuetify = createVuetify({
  blueprint: md3,
  components,
  directives,
  icons: {
    defaultSet: 'mdi',
    aliases,
    sets: { mdi },
  },
  theme: {
    defaultTheme: 'dark',
    themes: {
      dark: { ...md3Dark },
      light: { ...md3Light },
    },
  },
})
