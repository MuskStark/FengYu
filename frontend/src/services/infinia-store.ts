/**
 * Infinia Store domain — native integration with the store platform (the app's
 * single store surface: whole catalog, installs, cloud account).
 */
import type {
  StoreCatalogPage,
  StoreInstallResult,
  StoreInstalledEntry,
  StoreListingDetail,
  StoreStatusView,
  StoreUpdateEntry,
} from './types'
import { http } from './impl/http'

export interface InfiniaStoreService {
  /** One catalog page; pass the previous nextCursor to fetch the next one. */
  catalog(params?: { type?: string; query?: string; cursor?: string }): Promise<StoreCatalogPage>
  listing(namespace: string, slug: string): Promise<StoreListingDetail>
  installed(): Promise<StoreInstalledEntry[]>
  updates(): Promise<StoreUpdateEntry[]>
  install(coordinate: string, confirmPermissions?: boolean): Promise<StoreInstallResult>
  uninstall(coordinate: string, deleteData?: boolean): Promise<void>
  status(): Promise<StoreStatusView>
}

export const infiniaStoreService: InfiniaStoreService = {
  catalog: (params) =>
    http.get<StoreCatalogPage>('/api/store/catalog', { params }).then((r) => r.data),
  listing: (namespace, slug) =>
    http.get<StoreListingDetail>(
      `/api/store/listings/${encodeURIComponent(namespace)}/${encodeURIComponent(slug)}`)
      .then((r) => r.data),
  installed: () => http.get<StoreInstalledEntry[]>('/api/store/installed').then((r) => r.data),
  updates: () => http.get<StoreUpdateEntry[]>('/api/store/updates').then((r) => r.data),
  install: (coordinate, confirmPermissions = false) =>
    http.post<StoreInstallResult>('/api/store/install', { coordinate, confirmPermissions }).then((r) => r.data),
  uninstall: (coordinate, deleteData = false) =>
    http.delete('/api/store/installed', { params: { coordinate, deleteData } }),
  status: () => http.get<StoreStatusView>('/api/store/status').then((r) => r.data),
}
