import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import { localeRef } from '@/i18n'
import type { PluginDescriptor } from '@/api/types'

/** Local persistence for the ToolGrid favorites (same convention as the sidebar width). */
const FAVORITES_STORAGE_KEY = 'fengyu:tools-grid:favorite-plugins'

export const usePluginsStore = defineStore('plugins', () => {
  const plugins = ref<PluginDescriptor[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const favorites = ref<Set<string>>(readPersistedFavorites())

  // Favorites are pure local UI state (the ToolGrid star), so they persist in
  // localStorage like the sidebar width — no backend round-trip, survives restarts.
  function readPersistedFavorites(): Set<string> {
    if (typeof window === 'undefined') return new Set()
    try {
      const raw = window.localStorage.getItem(FAVORITES_STORAGE_KEY)
      const list = raw ? JSON.parse(raw) as unknown : null
      return Array.isArray(list) ? new Set(list.filter((id): id is string => typeof id === 'string')) : new Set()
    } catch {
      return new Set() // storage unavailable or corrupted — start fresh rather than throw
    }
  }

  function persistFavorites(next: Set<string>): void {
    if (typeof window === 'undefined') return
    try {
      window.localStorage.setItem(FAVORITES_STORAGE_KEY, JSON.stringify([...next]))
    } catch {
      // Storage can be unavailable (private mode, hardened Electron); favorites are cosmetic.
    }
  }

  async function load() {
    loading.value = true
    error.value = null
    try {
      plugins.value = await api.getPlugins()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load plugins'
    } finally {
      loading.value = false
    }
  }

  // Plugin name/description are resolved server-side per request locale (each manifest's i18n
  // block), so re-fetch when the UI language changes so the cards (ToolGrid, PluginView) track the
  // new language without a manual page reload.
  watch(localeRef, () => { void load() })

  function byId(id: string): PluginDescriptor | undefined {
    return plugins.value.find((p) => p.id === id)
  }

  function toggleFavorite(id: string) {
    const next = new Set(favorites.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    favorites.value = next
    persistFavorites(next)
  }

  const isFavorite = computed(() => (id: string) => favorites.value.has(id))

  return { plugins, loading, error, favorites, load, byId, toggleFavorite, isFavorite }
})
