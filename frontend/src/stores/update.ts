import { create } from 'zustand'
import { services } from '@/services'
import { getPlatform } from '@/platform'

/**
 * Application update probe — the shell-side half of the Vue update store. AppShell owns the
 * startup + periodic checks so the sidebar's About red dot can appear without the user
 * opening About; the About/Settings pages keep their own interactive check + apply flows
 * (they read fresh results, not this cache). Desktop routes through the Electron updater
 * bridge (platform capability), portable/browser through the backend's /api/updates/check.
 * Checks must never disrupt the UI — failures degrade silently (the dot stays hidden).
 */
interface UpdateState {
  updateAvailable: boolean
  latestVersion: string
  checking: boolean
  check: (force?: boolean) => Promise<void>
  startPeriodicChecks: () => void
}

/** Periodic re-check cadence — polite to the release API, still catches updates same-day. */
const PERIODIC_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000
let periodicTimer: number | undefined

export const useUpdateStore = create<UpdateState>((set, get) => ({
  updateAvailable: false,
  latestVersion: '',
  checking: false,

  check: async (force = false) => {
    if (get().checking) return
    set({ checking: true })
    try {
      const platform = getPlatform()
      if (platform.kind === 'desktop' && platform.capabilities.desktopUpdater) {
        const r = await platform.checkForUpdates()
        set({ updateAvailable: r.updateAvailable, latestVersion: r.version ?? '' })
      } else {
        const r = await services.appUpdate.check(force)
        set({ updateAvailable: r.updateAvailable, latestVersion: r.latestVersion })
      }
    } catch {
      // Degrade silently — the badge stays hidden; About shows the real failure reason.
    } finally {
      set({ checking: false })
    }
  },

  startPeriodicChecks: () => {
    if (periodicTimer !== undefined) return
    periodicTimer = window.setInterval(() => { void get().check() }, PERIODIC_CHECK_INTERVAL_MS)
  },
}))
