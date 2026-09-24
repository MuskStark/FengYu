import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { MarketplaceSkill, SkillDetail, SkillSummary } from '@/api/types'

/**
 * Runtime skills — managed like plugins (Codex-style progressive disclosure).
 *
 * Two parallel collections are kept in sync with the backend:
 *  - {@link skills} — the discovered summary list (builtin + installed), used by the listing
 *    pane and consulted for the enabled-state toggle.
 *  - {@link market} — the marketplace merged view (remote catalog + local install state),
 *    which drives the Install / Update / Enable / Uninstall action buttons.
 *
 * Lifecycle mutators (install / uninstall / upload / update / setEnabled) follow the plugin
 * market's {@code run(id, action)} pattern: perform the action, then reload both collections
 * so the UI reflects the new state without a manual refresh.
 */
export const useSkillsStore = defineStore('skills', () => {
  const skills = ref<SkillSummary[]>([])
  const market = ref<MarketplaceSkill[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function load() {
    loading.value = true
    error.value = null
    try {
      skills.value = await api.listSkills()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load skills'
    } finally {
      loading.value = false
    }
  }

  async function loadMarket() {
    try {
      market.value = await api.getSkillMarket()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load skill marketplace'
    }
  }

  function byId(id: string): SkillSummary | undefined {
    return skills.value.find((s) => s.id === id)
  }

  /** Fetch the full body of a skill for the preview pane. */
  async function detail(id: string): Promise<SkillDetail | null> {
    try {
      return await api.getSkill(id)
    } catch {
      return null
    }
  }

  /** Reload both the discovered list and the marketplace (used after every mutation). */
  async function refresh() {
    await Promise.all([load(), loadMarket()])
  }

  /** Optimistically toggle enabled; roll back on failure. Reloads after success. */
  async function setEnabled(id: string, enabled: boolean): Promise<boolean> {
    const prev = skills.value.find((s) => s.id === id)?.enabled
    if (prev === enabled) return true
    skills.value = skills.value.map((s) => (s.id === id ? { ...s, enabled } : s))
    market.value = market.value.map((s) => (s.id === id ? { ...s, enabled } : s))
    try {
      await api.setSkillEnabled(id, enabled)
      return true
    } catch (e) {
      skills.value = skills.value.map((s) =>
        s.id === id && prev !== undefined ? { ...s, enabled: prev } : s,
      )
      market.value = market.value.map((s) =>
        s.id === id && prev !== undefined ? { ...s, enabled: prev } : s,
      )
      error.value = e instanceof Error ? e.message : 'Failed to update skill'
      return false
    }
  }

  async function install(id: string): Promise<boolean> {
    try {
      await api.installSkill(id)
      await refresh()
      return true
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to install skill'
      return false
    }
  }

  async function update(id: string): Promise<boolean> {
    try {
      await api.updateSkill(id)
      await refresh()
      return true
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to update skill'
      return false
    }
  }

  async function uninstall(id: string): Promise<boolean> {
    try {
      await api.uninstallSkill(id)
      await refresh()
      return true
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to uninstall skill'
      return false
    }
  }

  async function uploadFile(file: File): Promise<boolean> {
    try {
      await api.uploadSkill(file)
      await refresh()
      return true
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to upload skill'
      return false
    }
  }

  async function uploadNative(path: string): Promise<boolean> {
    try {
      await api.uploadNativeSkill(path)
      await refresh()
      return true
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to upload skill'
      return false
    }
  }

  return {
    skills, market, loading, error,
    load, loadMarket, byId, detail, refresh,
    setEnabled, install, update, uninstall, uploadFile, uploadNative,
  }
})
