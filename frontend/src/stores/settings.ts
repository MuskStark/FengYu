import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { AppSettings, ComputerUseStatus, LanguageName, LogLevel, ThemeName } from '@/api/types'
import type { AiConfigTestRequest, AiConfigTestResult, AiSettings, PartialAiSettings } from '@/api/types'
import { i18n } from '@/i18n'
import { useThemeStore } from './theme'

export const useSettingsStore = defineStore('settings', () => {
  const sidebarCollapsed = ref(false)
  const theme = ref<ThemeName>('dark')
  const language = ref<LanguageName>('en')
  const logLevel = ref<LogLevel>('INFO')
  const unsandboxedPlugins = ref(false)
  const updateApiBase = ref('')
  const computerUseEnabled = ref(true)
  const computerUse = ref<ComputerUseStatus | null>(null)
  const loaded = ref(false)
  let desktopTheme: ThemeName | null = null

  function syncDesktopTheme(next: ThemeName) {
    if (desktopTheme === next) return
    if (typeof window !== 'undefined' && window.fengyu) {
      window.fengyu.setTheme(next)
      desktopTheme = next
    }
  }

  // Push the update-channel proxy URL into the Electron main process so the next update check
  // reads it. In-process IPC over the preload bridge — works offline (no network needed). The
  // guard keeps the no-op call benign in browser mode (window.fengyu undefined).
  function syncDesktopUpdateApiBase(next: string) {
    if (typeof window !== 'undefined' && window.fengyu?.setUpdateApiBase) {
      void window.fengyu.setUpdateApiBase(next ?? '')
    }
  }

  function apply(s: AppSettings) {
    sidebarCollapsed.value = s.sidebarCollapsed
    theme.value = s.theme
    language.value = s.language
    logLevel.value = s.logLevel ?? 'INFO'
    unsandboxedPlugins.value = s.unsandboxedPlugins ?? false
    updateApiBase.value = s.updateApiBase ?? ''
    computerUseEnabled.value = s.computerUseEnabled ?? true
    computerUse.value = s.computerUse ?? null
    useThemeStore().setTheme(s.theme)
    syncDesktopTheme(s.theme)
    syncDesktopUpdateApiBase(updateApiBase.value)
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

  async function setUnsandboxedPlugins(enabled: boolean) {
    unsandboxedPlugins.value = enabled
    await update({ unsandboxedPlugins: enabled })
  }

  async function setUpdateApiBase(next: string) {
    await update({ updateApiBase: next })
  }

  async function setComputerUseEnabled(enabled: boolean) {
    computerUseEnabled.value = enabled
    await update({ computerUseEnabled: enabled })
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
    unsandboxedPlugins,
    updateApiBase,
    computerUseEnabled,
    computerUse,
    loaded,
    load,
    update,
    setTheme,
    setLanguage,
    setSidebarCollapsed,
    setLogLevel,
    setUnsandboxedPlugins,
    setUpdateApiBase,
    setComputerUseEnabled,
    aiSettings,
    aiLoaded,
    loadAi,
    updateAi,
    testAi,
  }
})
