import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { AppSettings, LanguageName, ThemeName } from '@/api/types'
import { useThemeStore } from './theme'

export const useSettingsStore = defineStore('settings', () => {
  const sidebarCollapsed = ref(false)
  const theme = ref<ThemeName>('dark')
  const language = ref<LanguageName>('en')
  const loaded = ref(false)

  function apply(s: AppSettings) {
    sidebarCollapsed.value = s.sidebarCollapsed
    theme.value = s.theme
    language.value = s.language
    useThemeStore().setTheme(s.theme)
  }

  async function load() {
    const s = await api.getSettings()
    apply(s)
    loaded.value = true
  }

  async function update(partial: Partial<AppSettings>) {
    const s = await api.putSettings(partial)
    apply(s)
  }

  async function setTheme(next: ThemeName) {
    // Reflect immediately, then persist.
    useThemeStore().setTheme(next)
    theme.value = next
    await update({ theme: next })
  }

  async function setLanguage(next: LanguageName) {
    await update({ language: next })
  }

  async function setSidebarCollapsed(collapsed: boolean) {
    sidebarCollapsed.value = collapsed
    await update({ sidebarCollapsed: collapsed })
  }

  return {
    sidebarCollapsed,
    theme,
    language,
    loaded,
    load,
    update,
    setTheme,
    setLanguage,
    setSidebarCollapsed,
  }
})
