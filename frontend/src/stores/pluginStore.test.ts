import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import type { UnifiedCatalogEntry } from '@/api/types'

// Mock the API client so store tests are pure unit tests.
const apiMocks = vi.hoisted(() => ({
  getUnifiedCatalog: vi.fn(),
  installUnified: vi.fn(),
  updateUnified: vi.fn(),
  uninstallUnified: vi.fn(),
  getInstallHistory: vi.fn(),
  getStoreSources: vi.fn(),
}))
vi.mock('@/api/client', () => ({
  api: {
    getUnifiedCatalog: apiMocks.getUnifiedCatalog,
    installUnified: apiMocks.installUnified,
    updateUnified: apiMocks.updateUnified,
    uninstallUnified: apiMocks.uninstallUnified,
    getInstallHistory: apiMocks.getInstallHistory,
    getStoreSources: apiMocks.getStoreSources,
  },
}))

import { usePluginStore } from './pluginStore'

describe('pluginStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    apiMocks.getInstallHistory.mockResolvedValue([])
    apiMocks.getStoreSources.mockResolvedValue([])
  })

  it('surfaces install errors to the user instead of swallowing them (M-5)', async () => {
    apiMocks.installUnified.mockRejectedValue(new Error('clone failed: sha mismatch'))
    apiMocks.getUnifiedCatalog.mockResolvedValue([])
    const store = usePluginStore()

    await store.install('evil:CLAUDE:x')

    expect(store.error).toBeTruthy()
    expect(store.error).toContain('sha mismatch')
    // busy must be reset even on failure
    expect(store.busy).toBeNull()
  })

  it('surfaces uninstall errors to the user (M-5)', async () => {
    apiMocks.uninstallUnified.mockRejectedValue(new Error('record not found'))
    apiMocks.getUnifiedCatalog.mockResolvedValue([])
    const store = usePluginStore()

    await store.uninstall('evil:CLAUDE:x', false)

    expect(store.error).toContain('not found')
    expect(apiMocks.uninstallUnified).toHaveBeenCalledWith('evil:CLAUDE:x', false)
    expect(store.busy).toBeNull()
  })

  it('clears a previous error on the next successful action (M-5)', async () => {
    apiMocks.installUnified.mockRejectedValueOnce(new Error('first fails'))
    apiMocks.installUnified.mockResolvedValueOnce(undefined)
    apiMocks.getUnifiedCatalog.mockResolvedValue([])
    const store = usePluginStore()

    await store.install('a:CLAUDE:x')
    expect(store.error).toBeTruthy()

    await store.install('a:CLAUDE:x')
    expect(store.error).toBeNull()
  })

  it('normalizes malformed catalog arrays so the template never throws (M-7)', async () => {
    // A malicious/malformed catalog could return null or a string for array fields. The store must
    // coerce them to [] so v-for/.length in the template never throws.
    const malformed = [
      { sourceType: 'CLAUDE', uid: 'a:CLAUDE:x', name: 'x' }, // missing arrays entirely
      { sourceType: 'CLAUDE', uid: 'b:CLAUDE:y', name: 'y', keywords: null, declaredSkills: 'oops', mcpServers: null },
    ] as unknown as UnifiedCatalogEntry[]
    apiMocks.getUnifiedCatalog.mockResolvedValue(malformed)
    const store = usePluginStore()

    await store.loadCatalog()

    expect(store.catalog).toHaveLength(2)
    for (const entry of store.catalog) {
      expect(Array.isArray(entry.keywords)).toBe(true)
      expect(Array.isArray(entry.declaredSkills)).toBe(true)
      expect(Array.isArray(entry.mcpServers)).toBe(true)
    }
  })
})
