import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'

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

  /**
   * Boot-gate poll: resolve once the backend answers /api/health with ok. The desktop
   * shell creates this window BEFORE the backend is healthy (the SPA load overlaps the
   * JVM boot), so App.vue holds the full shell behind this wait — views then mount once,
   * without a burst of failed requests. Resolves false on timeout; the caller opens the
   * gate anyway and StatusBar surfaces the offline state.
   */
  async function waitForBackend(timeoutMs = 120_000, intervalMs = 500): Promise<boolean> {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      try {
        const r = await api.health()
        if (r.status === 'ok') {
          state.value = 'connected'
          return true
        }
      } catch {
        /* backend not up yet — keep polling */
      }
      await new Promise(resolve => setTimeout(resolve, intervalMs))
    }
    return false
  }

  return { state, setRestarting, waitForBackend }
})
