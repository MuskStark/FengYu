import { create } from 'zustand'
import { services } from '@/services'
import type { PluginDescriptor } from '@/services/types'

/** Installed plugins mirror — React port of the Pinia plugins store (listing surface). */
interface PluginsState {
  plugins: PluginDescriptor[]
  loaded: boolean
  load: () => Promise<void>
}

export const usePluginsStore = create<PluginsState>((set, get) => ({
  plugins: [],
  loaded: false,
  load: async () => {
    if (get().loaded) return
    try {
      const plugins = await services.plugin.list()
      set({ plugins, loaded: true })
    } catch {
      /* surfaces stay empty; the mention group simply hides */
    }
  },
}))
