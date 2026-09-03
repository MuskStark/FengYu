import { defineStore } from 'pinia'
import { ref, type Ref } from 'vue'
import { api } from '@/api/client'
import type { AppSettings, ComputerUseStatus, LanguageName, LogLevel, PermissionRuleTable, ThemeName } from '@/api/types'
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
  const storeAllowPrivateNetwork = ref(false)
  const computerUseEnabled = ref(true)
  const computerUse = ref<ComputerUseStatus | null>(null)
  const memoryEnabled = ref(false)
  const marketplaceRequireChecksum = ref(false)
  const permissionRules = ref<{ allow: string[]; ask: string[]; deny: string[] }>({ allow: [], ask: [], deny: [] })
  const invalidPermissionRules = ref<string[]>([])
  const hooksJson = ref('[]')
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
    storeAllowPrivateNetwork.value = s.storeAllowPrivateNetwork ?? false
    computerUseEnabled.value = s.computerUseEnabled ?? true
    computerUse.value = s.computerUse ?? null
    memoryEnabled.value = s.memoryEnabled ?? false
    marketplaceRequireChecksum.value = s.marketplaceRequireChecksum ?? false
    const rules = s.permissionRules as PermissionRuleTable | undefined
    permissionRules.value = {
      allow: Array.isArray(rules?.allow) ? rules!.allow : [],
      ask: Array.isArray(rules?.ask) ? rules!.ask : [],
      deny: Array.isArray(rules?.deny) ? rules!.deny : [],
    }
    invalidPermissionRules.value = s.invalidPermissionRules ?? []
    hooksJson.value = typeof s.hooks === 'string' ? s.hooks : '[]'
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

  // Monotonic sequence guarding apply(): full-settings responses can arrive out of order
  // (concurrent updates, an update racing the initial load), and a stale response must never
  // clobber state already written by a newer one.
  let applySeq = 0

  async function load() {
    const seq = ++applySeq
    const s = await api.getSettings()
    if (seq === applySeq) apply(s)
    loaded.value = true
  }

  async function update(partial: Partial<AppSettings>) {
    const seq = ++applySeq
    const s = await api.putSettings(partial)
    if (seq === applySeq) apply(s)
  }

  async function setTheme(next: ThemeName) {
    // Reflect in both the renderer and native window immediately, then persist.
    // Waiting for the backend before notifying Electron leaves macOS's native
    // title bar on the old appearance while the page has already switched.
    const previous = theme.value
    useThemeStore().setTheme(next)
    theme.value = next
    syncDesktopTheme(next)
    try {
      await update({ theme: next })
    } catch (error) {
      if (previous !== next) {
        theme.value = previous
        useThemeStore().setTheme(previous)
        syncDesktopTheme(previous)
      }
      throw error
    }
  }

  async function setLanguage(next: LanguageName) {
    await update({ language: next })
  }

  /**
   * Optimistically flip a boolean setting, persist it, and restore the previous value when the
   * backend rejects the change — the visible toggle must never lie about persisted state. The
   * error is rethrown so callers with an error surface (Settings.vue) can show it.
   */
  async function setBooleanFlag(
    flag: Ref<boolean>, patch: (value: boolean) => Partial<AppSettings>, next: boolean,
  ): Promise<void> {
    const previous = flag.value
    flag.value = next
    try {
      await update(patch(next))
    } catch (error) {
      if (previous !== next) flag.value = previous
      throw error
    }
  }

  async function setSidebarCollapsed(collapsed: boolean) {
    await setBooleanFlag(sidebarCollapsed, value => ({ sidebarCollapsed: value }), collapsed)
  }

  async function setLogLevel(next: LogLevel) {
    await update({ logLevel: next })
  }

  async function setUnsandboxedPlugins(enabled: boolean) {
    await setBooleanFlag(unsandboxedPlugins, value => ({ unsandboxedPlugins: value }), enabled)
  }

  async function setUpdateApiBase(next: string) {
    await update({ updateApiBase: next })
  }

  async function setStoreAllowPrivateNetwork(enabled: boolean) {
    await setBooleanFlag(
      storeAllowPrivateNetwork, value => ({ storeAllowPrivateNetwork: value }), enabled)
  }

  async function setComputerUseEnabled(enabled: boolean) {
    await setBooleanFlag(computerUseEnabled, value => ({ computerUseEnabled: value }), enabled)
  }

  async function setMemoryEnabled(enabled: boolean) {
    await setBooleanFlag(memoryEnabled, value => ({ memoryEnabled: value }), enabled)
  }

  async function setMarketplaceRequireChecksum(required: boolean) {
    await setBooleanFlag(marketplaceRequireChecksum, value => ({ marketplaceRequireChecksum: value }), required)
  }

  /** Saves the permission-rule table; the host rejects invalid rules with a 400. */
  async function savePermissionRules() {
    const result = await api.putPermissionRules(permissionRules.value)
    await load()
    return result
  }

  /** Saves the hook list (raw JSON, validated host-side) from the editor's value. */
  async function saveHooks(json: string) {
    const result = await api.putHooks(json)
    hooksJson.value = json
    await load()
    return result
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
    storeAllowPrivateNetwork,
    computerUseEnabled,
    computerUse,
    memoryEnabled,
    marketplaceRequireChecksum,
    permissionRules,
    invalidPermissionRules,
    hooksJson,
    loaded,
    load,
    update,
    setTheme,
    setLanguage,
    setSidebarCollapsed,
    setLogLevel,
    setUnsandboxedPlugins,
    setUpdateApiBase,
    setStoreAllowPrivateNetwork,
    setComputerUseEnabled,
    setMemoryEnabled,
    setMarketplaceRequireChecksum,
    savePermissionRules,
    saveHooks,
    aiSettings,
    aiLoaded,
    loadAi,
    updateAi,
    testAi,
  }
})
