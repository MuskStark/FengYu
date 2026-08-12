import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import { localeRef } from '@/i18n'
import type { PluginDescriptor } from '@/api/types'

export const usePluginsStore = defineStore('plugins', () => {
  const plugins = ref<PluginDescriptor[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const favorites = ref<Set<string>>(new Set())

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
  }

  const isFavorite = computed(() => (id: string) => favorites.value.has(id))

  return { plugins, loading, error, favorites, load, byId, toggleFavorite, isFavorite }
})
