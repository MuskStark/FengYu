import { useEffect, useState } from 'react'
import { services } from '@/services'
import { getPlatform, type BootStage, type BootStateEvent } from '@/platform'
import StartupScreen from '@/shell/StartupScreen'
import StartupFailureScreen from '@/shell/StartupFailureScreen'

/** How long the exit veil cross-fades (keep in sync with fx-startup-veil in shell.css). */
const EXIT_FADE_MS = 240

/**
 * First-boot readiness gate (backend still starting): polls /api/health and hands
 * over to the app the moment it answers, while showing the shared StartupScreen
 * (the same mark the static HTML shell played before React mounted). React port
 * of the Vue BootGate.
 *
 * On desktop the shell also pushes boot-phase events: a `booting` push carries
 * the fine-grained startup stage (spawning → port-ready → health-ready →
 * loading-ui) that the StartupScreen renders as its progress label, and a
 * `failed` push swaps in the recoverable StartupFailureScreen (retry / logs /
 * diagnostics / quit) — the next `booting` push (a retry starting) swaps back.
 *
 * The desktop window loads before the backend port is known, so the health poll
 * stays idle until the platform's apiBase() flips non-empty (the endpoint:ready
 * push); on web the empty base means same-origin requests through the dev proxy.
 *
 * Hand-off is a cross-fade, not a hard cut: App keeps this component mounted
 * after the gate opens (`exiting`) — the startup visual becomes a fixed veil
 * that fades out over the freshly mounted app shell. The wrapper element is
 * STABLE across that switch (only its class changes), so the StartupScreen
 * subtree is never remounted and its running animations (track sweep, stage
 * label) continue seamlessly through the fade. The veil is pointer-events:none,
 * so the app beneath is interactive immediately; a timer (not animationend —
 * reduced-motion disables the animation) unmounts it once the fade is done.
 */
export default function BootGate({
  onReady,
  exiting = false,
  onExited,
}: {
  onReady: () => void
  /** True once the gate has opened: render the fading exit veil instead of gating. */
  exiting?: boolean
  /** Called when the exit fade is over (App then unmounts this component). */
  onExited?: () => void
}) {
  const [failure, setFailure] = useState<BootStateEvent | null>(null)
  const [stage, setStage] = useState<BootStage | null>(null)
  const platform = getPlatform()

  useEffect(() => {
    if (!exiting || !onExited) return
    const timer = window.setTimeout(onExited, EXIT_FADE_MS + 100)
    return () => window.clearTimeout(timer)
  }, [exiting, onExited])

  useEffect(() => {
    if (exiting) return
    const timer = window.setInterval(async () => {
      // Desktop with no endpoint yet: the shell has not resolved the backend
      // port — polling would only stack failed same-origin requests.
      if (platform.kind === 'desktop' && !platform.apiBase()) return
      try {
        await services.system.health()
        window.clearInterval(timer)
        onReady()
      } catch {
        /* keep waiting */
      }
    }, 900)
    return () => window.clearInterval(timer)
  }, [onReady, platform, exiting])

  useEffect(() => {
    if (!platform.capabilities.bootRecovery) return
    const applyState = (state: BootStateEvent) => {
      setFailure(state.phase === 'failed' ? state : null)
      if (state.phase === 'booting' && state.stage) setStage(state.stage)
    }
    // Subscribe first, then pull the current state: a push can fire before this
    // page finished loading (the shell's push to an unloaded page is dropped),
    // and the pull closes that gap without missing pushes in between.
    const unsubscribe = platform.onBootState(applyState)
    void platform.getBootState().then((state) => {
      if (state) applyState(state)
    }).catch(() => {
      // dev-connect (external backend) registers no boot IPC: the push subscription
      // above stays quiet and the normal health-wait gate takes over — not an error.
    })
    return unsubscribe
  }, [platform])

  return (
    <div className={exiting ? 'fx-startup-veil' : 'fx-startup-holder'}>
      {failure ? <StartupFailureScreen failure={failure} /> : <StartupScreen stage={stage ?? undefined} />}
    </div>
  )
}
