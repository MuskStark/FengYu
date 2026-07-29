import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { AppSettings, LanguageName, LogLevel, ThemeName } from '@/api/types'
import type { AiConfigTestRequest, AiConfigTestResult, AiSettings, PartialAiSettings } from '@/api/types'
import { i18n } from '@/i18n'
import { useThemeStore } from './theme'

export const useSettingsStore = defineStore('settings', () => {
  const sidebarCollapsed = ref(false)
  const theme = ref<ThemeName>('dark')
  const language = ref<LanguageName>('en')
  const logLevel = ref<LogLevel>('INFO')
  const loaded = ref(false)
  let desktopTheme: ThemeName | null = null

  function syncDesktopTheme(next: ThemeName) {
    if (desktopTheme === next) return
    if (typeof window !== 'undefined' && window.fengyu) {
      window.fengyu.setTheme(next)
      desktopTheme = next
    }
  }

  function apply(s: AppSettings) {
    sidebarCollapsed.value = s.sidebarCollapsed
    theme.value = s.theme
    language.value = s.language
    logLevel.value = s.logLevel ?? 'INFO'
    useThemeStore().setTheme(s.theme)
    syncDesktopTheme(s.theme)
    // Drive vue-i18n from the host language setting. apply() is the single
    // funnel for both the initial settings load() and every update(), so this
    // covers the initial-load case (reactively, after the fire-and-forget load
    // resolves) as well as each setLanguage() switch — no need to await load()
    // before mount, preserving the anti-flash theme logic in main.ts.
    i18n.global.locale.value = s.language
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
    // Reflect in both the renderer and native window immediately, then persist.
    // Waiting for the backend before notifying Electron leaves macOS's native
    // title bar on the old appearance while the page has already switched.
    useThemeStore().setTheme(next)
    theme.value = next
    syncDesktopTheme(next)
    await update({ theme: next })
  }

  async function setLanguage(next: LanguageName) {
    await update({ language: next })
  }

  async function setSidebarCollapsed(collapsed: boolean) {
    sidebarCollapsed.value = collapsed
    await update({ sidebarCollapsed: collapsed })
  }

  async function setLogLevel(next: LogLevel) {
    await update({ logLevel: next })
  }

  // ── AI Config ───────────────────────────────────────────────
  const aiSettings = ref<AiSettings | null>(null)
  const aiLoaded = ref(false)

  async function loadAi() {
    aiSettings.value = await api.getAiSettings()
    aiLoaded.value = true
  }

  async function updateAi(partial: PartialAiSettings) {
    aiSettings.value = await api.putAiSettings(partial)
  }

  async function testAi(req: AiConfigTestRequest): Promise<AiConfigTestResult> {
    return await api.testAiConnection(req)
  }

  return {
    sidebarCollapsed,
    theme,
    language,
    logLevel,
    loaded,
    load,
    update,
    setTheme,
    setLanguage,
    setSidebarCollapsed,
    setLogLevel,
    aiSettings,
    aiLoaded,
    loadAi,
    updateAi,
    testAi,
  }
})
