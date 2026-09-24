import { create } from 'zustand'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import { i18n } from '@/i18n'
import type { AppSettings, ThemeName } from '@/services/types'

/**
 * Host settings mirror (backend-driven, like the Vue shell's Pinia store): theme, language,
 * sidebar shape, and the AI provider configuration. The theme applies by toggling the
 * v-theme--* class on the document root — the same class tokens.css/md3.css key off.
 */
interface SettingsState {
  sidebarCollapsed: boolean
  theme: ThemeName
  language: string
  loaded: boolean
  aiSettings: import('@/services/types').AiSettings | null
  load: () => Promise<void>
  loadAi: () => Promise<void>
  apply: (settings: AppSettings) => void
  setTheme: (next: ThemeName) => void
  updateAi: (patch: import('@/services/types').PartialAiSettings) => Promise<void>
  setSidebarCollapsed: (collapsed: boolean) => Promise<void>
}

function applyThemeClass(theme: ThemeName) {
  const root = document.documentElement
  const dark = theme !== 'light'
  root.classList.remove(
    'v-theme--dark',
    'v-theme--light',
    'dark',
    'theme-zai-dark',
    'theme-zai-light',
  )
  // The Zai theme class carries the design-token palette (zai.css); `dark` drives the
  // Tailwind dark: variant, and the legacy v-theme--* class keeps older sheets working.
  root.classList.add(dark ? 'v-theme--dark' : 'v-theme--light')
  root.classList.add(dark ? 'theme-zai-dark' : 'theme-zai-light')
  root.classList.toggle('dark', dark)
  root.style.colorScheme = dark ? 'dark' : 'light'
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', dark ? '#161616' : '#f8f8f8')
  // Write-through so the index.html anti-flash script starts from the right theme on the
  // next launch (it reads this key before the backend settings can answer).
  try { localStorage.setItem('fengyu-theme', JSON.stringify(theme)) } catch { /* ignore */ }
  getPlatform().setTheme(theme)
}

export const useSettingsStore = create<SettingsState>((set, get) => ({
  sidebarCollapsed: false,
  theme: 'dark',
  language: 'en',
  loaded: false,
  aiSettings: null,

  load: async () => {
    const settings = await services.settings.get()
    get().apply(settings)
    set({ loaded: true })
  },

  loadAi: async () => {
    const ai = await services.aiConfig.get()
    set({ aiSettings: ai })
  },

  apply: (settings) => {
    set({
      sidebarCollapsed: settings.sidebarCollapsed,
      theme: settings.theme,
      language: settings.language,
    })
    applyThemeClass(settings.theme)
    i18n.global.locale.value = settings.language
  },

  setTheme: (next) => {
    set({ theme: next })
    applyThemeClass(next)
    void services.settings.update({ theme: next }).catch(() => {/* local-only fallback */})
  },

  updateAi: async (patch) => {
    const updated = await services.aiConfig.update(patch)
    set({ aiSettings: updated })
  },

  setSidebarCollapsed: async (collapsed) => {
    const previous = get().sidebarCollapsed
    set({ sidebarCollapsed: collapsed })
    try {
      const settings = await services.settings.update({ sidebarCollapsed: collapsed })
      get().apply(settings)
    } catch {
      set({ sidebarCollapsed: previous })
    }
  },
}))
