import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ThemeName } from '@/api/types'

const listeners = new Set<(t: ThemeName) => void>()

function applyThemeClass(theme: ThemeName) {
  const root = document.documentElement
  root.classList.remove('theme-dark', 'theme-light')
  root.classList.add(theme === 'light' ? 'theme-light' : 'theme-dark')
}

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<ThemeName>('dark')

  function setTheme(next: ThemeName) {
    theme.value = next
    applyThemeClass(next)
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
