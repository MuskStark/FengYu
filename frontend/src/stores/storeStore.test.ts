import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useStoreStore } from './storeStore'
import type { StoreCatalogEntry } from '@/api/types'

const mocks = vi.hoisted(() => ({
  getStoreCatalog: vi.fn(),
  getStoreInstalled: vi.fn(),
  getStoreUpdates: vi.fn(),
  getStoreStatus: vi.fn(),
  installFromStore: vi.fn(),
  uninstallFromStore: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  api: {
    getStoreCatalog: mocks.getStoreCatalog,
    getStoreInstalled: mocks.getStoreInstalled,
    getStoreUpdates: mocks.getStoreUpdates,
    getStoreStatus: mocks.getStoreStatus,
    installFromStore: mocks.installFromStore,
    uninstallFromStore: mocks.uninstallFromStore,
  },
}))

const entry = (over: Partial<StoreCatalogEntry> = {}): StoreCatalogEntry => ({
  item: null,
  coordinate: 'infinia://plugin/official/markdown',
  type: 'PLUGIN',
  namespace: 'official',
  slug: 'markdown',
  name: 'Markdown Tools',
  summary: 'sum',
  category: 'Productivity',
  latestVersion: '2.4.0',
  installedVersion: null,
  installed: false,
  ...over,
})

describe('storeStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads catalog, installed and updates together', async () => {
    mocks.getStoreCatalog.mockResolvedValue([entry()])
    mocks.getStoreInstalled.mockResolvedValue([])
    mocks.getStoreUpdates.mockResolvedValue([])
    mocks.getStoreStatus.mockResolvedValue({ apiBase: 'http://localhost:8080' })

    const store = useStoreStore()
    await store.refreshAll()
    await store.loadStatus()

    expect(store.catalog).toHaveLength(1)
    expect(store.loading).toBe(false)
    expect(store.apiBase).toBe('http://localhost:8080')
  })

  it('captures catalog failures in error without throwing', async () => {
    mocks.getStoreCatalog.mockRejectedValue(new Error('store unreachable'))
    mocks.getStoreInstalled.mockResolvedValue([])
    mocks.getStoreUpdates.mockResolvedValue([])

    const store = useStoreStore()
    await expect(store.refreshAll()).resolves.toBeUndefined()

    expect(store.error).toBe('store unreachable')
    expect(store.catalog).toEqual([])
  })

  it('installs through the api and refreshes views', async () => {
    mocks.getStoreCatalog.mockResolvedValue([entry({ installed: true, installedVersion: '2.4.0' })])
    mocks.getStoreInstalled.mockResolvedValue([])
    mocks.getStoreUpdates.mockResolvedValue([])
    mocks.installFromStore.mockResolvedValue({
      coordinate: 'infinia://plugin/official/markdown',
      type: 'PLUGIN',
      localId: 'official.markdown',
      version: '2.4.0',
      permissions: [],
      dependenciesInstalled: [],
    })

    const store = useStoreStore()
    const result = await store.install('infinia://plugin/official/markdown', false)

    expect(mocks.installFromStore).toHaveBeenCalledWith(
      'infinia://plugin/official/markdown',
      false,
    )
    expect(result?.localId).toBe('official.markdown')
    expect(store.busy).toBe(null)
  })

  it('keeps the failing coordinate out of busy and records the error', async () => {
    mocks.installFromStore.mockRejectedValue(new Error('missing dependencies []'))
    const store = useStoreStore()

    await expect(store.install('infinia://plugin/x', false)).rejects.toBeTruthy()

    expect(store.error).toBe('missing dependencies []')
    expect(store.busy).toBe(null)
  })
})
