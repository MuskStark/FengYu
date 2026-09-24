import { create } from 'zustand'
import { services } from '@/services'

/**
 * Backend connectivity state machine, polled like the Vue shell's StatusBar: connecting →
 * connected | reconnecting | offline | restarting. UI surfaces read `state`; the poll loop
 * runs from AppShell.
 */
export type ConnectionState = 'connecting' | 'connected' | 'reconnecting' | 'offline' | 'restarting'

interface ConnectionStore {
  state: ConnectionState
  tick: () => Promise<void>
}

let reconnectAttempts = 0

export const useConnectionStore = create<ConnectionStore>((set) => ({
  state: 'connecting',
  tick: async () => {
    try {
      await services.system.health()
      reconnectAttempts = 0
      set({ state: 'connected' })
    } catch {
      reconnectAttempts += 1
      set({ state: reconnectAttempts >= 3 ? 'offline' : 'reconnecting' })
    }
  },
}))
