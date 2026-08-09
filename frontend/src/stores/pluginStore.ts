import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type {
  InstallRecord,
  StoreSource,
  StoreSourceType,
  UnifiedCatalogEntry,
} from '@/api/types'

export interface StoreFilter {
  sourceType?: StoreSourceType
  category?: string
  q?: string
}

export const usePluginStore = defineStore('pluginStore', () => {
  const sources = ref<StoreSource[]>([])
  const catalog = ref<UnifiedCatalogEntry[]>([])
  const history = ref<InstallRecord[]>([])
  const filter = ref<StoreFilter>({})
  const loading = ref(false)
  const error = ref<string | null>(null)
  const busy = ref<string | null>(null) // uid of in-flight install/update

  async function loadSources() {
    try {
      sources.value = await api.getStoreSources()
    } catch (e) {
      error.value = errMsg(e)
    }
  }

  async function loadCatalog() {
    loading.value = true
    error.value = null
    try {
      // Catalog data arrives from third-party marketplaces and may be malformed; coerce the array
      // fields to [] so the template's v-for/.length can never throw on null/string values (M-7).
      const raw = await api.getUnifiedCatalog(filter.value)
      catalog.value = (raw ?? []).map(normalizeEntry)
    } catch (e) {
      error.value = errMsg(e)
    } finally {
      loading.value = false
    }
  }

  async function loadHistory() {
    try {
      history.value = await api.getInstallHistory()
    } catch (e) {
      error.value = errMsg(e)
    }
  }

  async function addSource(name: string, sourceType: StoreSourceType, catalogUrl: string) {
    await api.addStoreSource(name, sourceType, catalogUrl)
    await loadSources()
    await loadCatalog()
  }

  async function deleteSource(origin: string) {
    await api.deleteStoreSource(origin)
    await loadSources()
    await loadCatalog()
  }

  async function refreshSource(origin: string) {
    await api.refreshStoreSource(origin)
    await loadCatalog()
  }

  async function install(uid: string) {
    busy.value = uid
    error.value = null
    try {
      await api.installUnified(uid)
      await Promise.all([loadCatalog(), loadHistory()])
    } catch (e) {
      error.value = errMsg(e)
    } finally {
      busy.value = null
    }
  }

  async function update(uid: string) {
    busy.value = uid
    error.value = null
    try {
      await api.updateUnified(uid)
      await Promise.all([loadCatalog(), loadHistory()])
    } catch (e) {
      error.value = errMsg(e)
    } finally {
      busy.value = null
    }
  }

  async function uninstall(uid: string, deleteData: boolean) {
    busy.value = uid
    error.value = null
    try {
      await api.uninstallUnified(uid, deleteData)
      await Promise.all([loadCatalog(), loadHistory()])
    } catch (e) {
      error.value = errMsg(e)
    } finally {
      busy.value = null
    }
  }

  async function setEnabled(uid: string, enabled: boolean) {
    await api.setUnifiedEnabled(uid, enabled)
    await loadCatalog()
  }

  function setFilter(f: StoreFilter) {
    filter.value = f
  }

  function errMsg(e: unknown): string {
    return e instanceof Error ? e.message : String(e)
  }

  /** Coerces the array fields of a (possibly malformed) catalog entry to real arrays (M-7). */
  function normalizeEntry(e: UnifiedCatalogEntry): UnifiedCatalogEntry {
    return {
      ...e,
      keywords: asArray(e.keywords),
      declaredSkills: asArray(e.declaredSkills),
      mcpServers: asArray(e.mcpServers),
    }
  }

  function asArray(v: unknown): string[] {
    return Array.isArray(v) ? v : []
  }

  return {
    sources, catalog, history, filter, loading, error, busy,
    loadSources, loadCatalog, loadHistory,
    addSource, deleteSource, refreshSource,
    install, uninstall, update, setEnabled, setFilter,
  }
})
