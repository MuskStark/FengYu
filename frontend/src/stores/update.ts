import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import { isDesktop } from '@/mf/desktop'

/**
 * Application update state. Mode-routed:
 * - Desktop (Electron) — checks + installs via the shell's IPC bridge (electron-updater).
 * - Portable (java -jar) / browser — checks via the backend's /api/updates/check; portable
 *   installs via POST /api/updates/apply (backend swaps its own JAR and restarts).
 *
 * AppShell owns the startup + periodic checks so the badge/red dot can appear without the
 * user opening About; About/Settings read the same state and expose the "update now" action.
 */
export const useUpdateStore = defineStore('update', () => {
  const updateAvailable = ref(false)
  const latestVersion = ref('')
  const currentVersion = ref('')
  const releaseUrl = ref('')
  const portableMode = ref(false)
  const checking = ref(false)
  const downloading = ref(false)
  const downloadPercent = ref(0)
  /**
   * Desktop install phase for the button label: downloading → installing → restarting.
   * 'installing' covers the in-app pre-copy (files being replaced, percent in
   * downloadPercent); 'restarting' is the final quit-and-relaunch handoff.
   */
  const phase = ref<'idle' | 'downloading' | 'installing' | 'restarting'>('idle')
  const error = ref(false)
  /** Real failure reason from the last check/install (backend 4xx/5xx body or IPC error). */
  const errorMessage = ref('')
  const lastChecked = ref<number | undefined>(undefined)
  /** Set when a user-initiated install returns the macOS manual path (unsigned Gatekeeper). */
  const manualRequired = ref(false)

  /** Periodic re-check cadence — polite to the release API, still catches updates same-day. */
  const PERIODIC_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000
  let periodicTimer: number | undefined

  /**
   * Idempotent periodic update check (one timer per shell). The startup probe lives in
   * AppShell; this keeps the badge honest for a long-running session. check() itself
   * no-ops while a check is already in flight.
   */
  function startPeriodicChecks() {
    if (periodicTimer !== undefined) return
    periodicTimer = window.setInterval(() => { void check() }, PERIODIC_CHECK_INTERVAL_MS)
  }

  function describe(e: unknown): string {
    return e instanceof Error ? e.message : String(e)
  }

  async function check(force = false): Promise<void> {
    if (checking.value) return
    checking.value = true
    error.value = false
    errorMessage.value = ''
    try {
      if (isDesktop()) {
        const r = await window.fengyu!.checkForUpdates()
        updateAvailable.value = r.updateAvailable
        latestVersion.value = r.version ?? ''
        releaseUrl.value = r.releaseUrl ?? 'https://github.com/MuskStark/FengYu/releases'
        // Desktop builds don't report currentVersion via the bridge; keep the build-time constant.
        currentVersion.value = __APP_VERSION__
        portableMode.value = false
      } else {
        const r = await api.checkForUpdates(force)
        updateAvailable.value = r.updateAvailable
        latestVersion.value = r.latestVersion
        currentVersion.value = r.currentVersion
        releaseUrl.value = r.releaseUrl || 'https://github.com/MuskStark/FengYu/releases'
        portableMode.value = r.portableMode
      }
      lastChecked.value = Date.now()
    } catch (e) {
      // Update check must never disrupt the UI — degrade silently (badge stays hidden).
      // The reason is kept so the About page can show WHY the check failed (e.g. a
      // 503 from the update proxy) instead of a bare "failed".
      error.value = true
      errorMessage.value = describe(e)
    } finally {
      checking.value = false
    }
  }

  /** User has explicitly clicked "update now". Route to the mode-appropriate installer. */
  async function agreeAndUpdate(): Promise<{ action: string; releaseUrl?: string } | void> {
    if (downloading.value) return
    error.value = false
    errorMessage.value = ''
    manualRequired.value = false

    if (isDesktop()) {
      downloading.value = true
      phase.value = 'downloading'
      const offP = window.fengyu!.onUpdateProgress((info) => {
        downloadPercent.value = Math.round(info.percent)
      })
      // The main process narrates stage transitions (downloading → installing); the percent
      // stream keeps flowing through the same onUpdateProgress channel.
      const offS = window.fengyu!.onUpdateState((s) => {
        if (s.state === 'installing') {
          phase.value = 'installing'
          downloadPercent.value = 0
        }
      })
      try {
        const result = await window.fengyu!.downloadAndInstall()
        if (result.action === 'manual') {
          manualRequired.value = true
          releaseUrl.value = result.releaseUrl
          return result
        }
        phase.value = 'restarting' // 'restarting' — the app will quit and relaunch on its own
        return result
      } catch (e) {
        error.value = true
        errorMessage.value = describe(e)
      } finally {
        offP()
        offS()
        // Keep the button in its "restarting…" state until the window closes; only reset
        // when the flow actually ended (manual fallback or error).
        if (phase.value !== 'restarting') {
          downloading.value = false
          phase.value = 'idle'
        }
      }
      return
    }

    if (!portableMode.value) {
      // Browser (non-portable) deployment: no self-install path — open the release page.
      window.open(releaseUrl.value, '_blank', 'noopener')
      return { action: 'manual', releaseUrl: releaseUrl.value }
    }

    // Portable self-update: backend downloads + verifies + restarts.
    downloading.value = true
    try {
      const result = await api.applyPortableUpdate()
      return result
    } catch (e) {
      error.value = true
      errorMessage.value = describe(e)
    } finally {
      downloading.value = false
    }
  }

  return {
    updateAvailable,
    latestVersion,
    currentVersion,
    releaseUrl,
    portableMode,
    checking,
    downloading,
    downloadPercent,
    phase,
    error,
    errorMessage,
    lastChecked,
    manualRequired,
    check,
    startPeriodicChecks,
    agreeAndUpdate,
  }
})
