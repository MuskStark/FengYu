import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * Shared backend-connection state. The global StatusBar owns the health-poll
 * loop and writes the derived state here; the setup wizard flips `restarting`
 * on while it waits for the backend process to come back after initialize().
 *
 * Kept in a store (not a local ref in StatusBar) so the wizard and the bar —
 * which live in different parts of the tree — share one source of truth.
 */
export type ConnState = 'connecting' | 'connected' | 'reconnecting' | 'offline' | 'restarting'

export const useConnectionStore = defineStore('connection', () => {
  const state = ref<ConnState>('connecting')

  /** Called by the setup wizard around its restart-wait loop. */
  function setRestarting(on: boolean) {
    state.value = on ? 'restarting' : 'connecting'
  }

  return { state, setRestarting }
})
