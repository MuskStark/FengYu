import { ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '@/api/client'
import type {
  StoreCatalogEntry,
  StoreInstalledEntry,
  StoreListingDetail,
  StoreUpdateEntry,
} from '@/api/types'

/**
 * Infinia Store state: catalog + installed + update views against the local
 * /api/store surface. Mutations never throw — failures land in `error`.
 */
export const useStoreStore = defineStore('storeStore', () => {
  const catalog = ref<StoreCatalogEntry[]>([])
  const installed = ref<StoreInstalledEntry[]>([])
  const updates = ref<StoreUpdateEntry[]>([])
  const loading = ref(false)
  const busy = ref<string | null>(null) // coordinate of in-flight install/uninstall
  const error = ref<string | null>(null)
  const apiBase = ref('')

  async function loadCatalog(type?: string, query?: string) {
    loading.value = true
    error.value = null
    try {
      const raw = await api.getStoreCatalog({ type, query })
      catalog.value = raw ?? []
    } catch (e) {
      error.value = errMsg(e)
    } finally {
      loading.value = false
    }
  }

  async function loadInstalled() {
    try {
      installed.value = (await api.getStoreInstalled()) ?? []
    } catch (e) {
      error.value = errMsg(e)
    }
  }

  async function loadUpdates() {
    try {
      updates.value = (await api.getStoreUpdates()) ?? []
    } catch (e) {
      error.value = errMsg(e)
    }
  }

  async function loadStatus() {
    try {
      apiBase.value = (await api.getStoreStatus())?.apiBase ?? ''
    } catch {
      apiBase.value = ''
    }
  }

  async function refreshAll(type?: string, query?: string) {
    await Promise.all([loadCatalog(type, query), loadInstalled(), loadUpdates()])
  }

  async function install(coordinate: string, confirmPermissions = false) {
    busy.value = coordinate
    error.value = null
    try {
      const result = await api.installFromStore(coordinate, confirmPermissions)
      await refreshAll()
      return result
    } catch (e) {
      error.value = errMsg(e)
      throw e
    } finally {
      busy.value = null
    }
  }

  async function uninstall(coordinate: string, deleteData = false) {
    busy.value = coordinate
    error.value = null
    try {
      await api.uninstallFromStore(coordinate, deleteData)
      await refreshAll()
    } catch (e) {
      error.value = errMsg(e)
    } finally {
      busy.value = null
    }
  }

  async function listing(namespace: string, slug: string): Promise<StoreListingDetail | null> {
    try {
      return await api.getStoreListing(namespace, slug)
    } catch (e) {
      error.value = errMsg(e)
      return null
    }
  }

  function errMsg(e: unknown): string {
    return e instanceof Error ? e.message : String(e)
  }

  return {
    catalog,
    installed,
    updates,
    loading,
    busy,
    error,
    apiBase,
    loadCatalog,
    loadInstalled,
    loadUpdates,
    loadStatus,
    refreshAll,
    install,
    uninstall,
    listing,
  }
})
