import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ThemeName } from '@/api/types'
import { vuetify } from '@/plugins/vuetify'

const listeners = new Set<(t: ThemeName) => void>()

/**
 * Drive Vuetify's global theme singleton directly. We do NOT use the
 * useTheme() composable here because this store action runs in main.ts
 * (outside any component/setup context); useTheme() relies on inject().
 * The `vuetify` export is the same singleton main.ts registered.
 */
function applyVuetifyTheme(theme: ThemeName) {
  vuetify.theme.global.name.value = theme
}

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<ThemeName>('dark')

  function setTheme(next: ThemeName) {
    theme.value = next
    applyVuetifyTheme(next)
    listeners.forEach((cb) => cb(next))
  }

  function toggle() {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  /** Subscribe to theme changes (used by the MF plugin host ctx). */
  function onChange(cb: (t: ThemeName) => void): () => void {
    listeners.add(cb)
    return () => listeners.delete(cb)
  }

  return { theme, setTheme, toggle, onChange }
})
