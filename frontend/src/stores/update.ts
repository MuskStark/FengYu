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
 * The global StatusBar owns the startup check (fire-and-forget) so the badge can appear without
 * the user opening About; About/Settings read the same state and expose the "update now" action.
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
  const error = ref(false)
  const lastChecked = ref<number | undefined>(undefined)
  /** Set when a user-initiated install returns the macOS manual path (unsigned Gatekeeper). */
  const manualRequired = ref(false)

  async function check(force = false): Promise<void> {
    if (checking.value) return
    checking.value = true
    error.value = false
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
    } catch {
      // Update check must never disrupt the UI — degrade silently (badge stays hidden).
      error.value = true
    } finally {
      checking.value = false
    }
  }

  /** User has explicitly clicked "update now". Route to the mode-appropriate installer. */
  async function agreeAndUpdate(): Promise<{ action: string; releaseUrl?: string } | void> {
    if (downloading.value) return
    error.value = false
    manualRequired.value = false

    if (isDesktop()) {
      downloading.value = true
      const offP = window.fengyu!.onUpdateProgress((info) => {
        downloadPercent.value = Math.round(info.percent)
      })
      try {
        const result = await window.fengyu!.downloadAndInstall()
        if (result.action === 'manual') {
          manualRequired.value = true
          releaseUrl.value = result.releaseUrl
          return result
        }
        return result // 'restarting' — the app will quit and relaunch on its own
      } catch {
        error.value = true
      } finally {
        offP()
        downloading.value = false
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
    } catch {
      error.value = true
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
    error,
    lastChecked,
    manualRequired,
    check,
    agreeAndUpdate,
  }
})
