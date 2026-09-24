/**
 * Unified plugin store domain — marketplace sources, aggregated catalog, and
 * install lifecycle across all source types.
 */
import type { InstallRecord, StoreSource, StoreSourceType, UnifiedCatalogEntry } from './types'
import { http } from './impl/http'

export interface StoreService {
  sources(): Promise<StoreSource[]>
  addSource(name: string, sourceType: StoreSourceType, catalogUrl: string): Promise<StoreSource>
  /** Unsubscribe a source (does not uninstall plugins). */
  deleteSource(origin: string): Promise<void>
  /** Force-refresh a source's cached catalog. */
  refreshSource(origin: string): Promise<void>
  /** Aggregated catalog (optionally filtered by sourceType/category/query server-side). */
  catalog(params?: { sourceType?: StoreSourceType; category?: string; q?: string }): Promise<UnifiedCatalogEntry[]>
  /** Install (or update) a plugin by uid; backend dispatches by sourceType. */
  install(uid: string): Promise<void>
  update(uid: string, confirmPermissions?: boolean): Promise<void>
  uninstall(uid: string, deleteData: boolean): Promise<void>
  setEnabled(uid: string, enabled: boolean): Promise<void>
  /** Installation history (install records across all sources). */
  history(): Promise<InstallRecord[]>
}

export const storeService: StoreService = {
  sources: () => http.get<StoreSource[]>('/api/plugin-store/sources').then((r) => r.data),
  addSource: (name, sourceType, catalogUrl) =>
    http.post<StoreSource>('/api/plugin-store/sources', { name, sourceType, catalogUrl }).then((r) => r.data),
  deleteSource: (origin) => http.delete(`/api/plugin-store/sources/${encodeURIComponent(origin)}`),
  refreshSource: (origin) => http.post(`/api/plugin-store/sources/${encodeURIComponent(origin)}/refresh`),
  catalog: (params) =>
    http.get<UnifiedCatalogEntry[]>('/api/plugin-store/catalog', { params }).then((r) => r.data),
  install: (uid) => http.post(`/api/plugin-store/${encodeURIComponent(uid)}/install`),
  update: (uid, confirmPermissions = false) =>
    http.post(`/api/plugin-store/${encodeURIComponent(uid)}/update`, undefined, { params: { confirmPermissions } }),
  uninstall: (uid, deleteData) =>
    http.delete(`/api/plugin-store/${encodeURIComponent(uid)}`, { params: { deleteData } }),
  setEnabled: (uid, enabled) =>
    http.patch(`/api/plugin-store/${encodeURIComponent(uid)}/enabled`, { enabled }),
  history: () => http.get<InstallRecord[]>('/api/plugin-store/history').then((r) => r.data),
}
