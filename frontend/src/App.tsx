import { Suspense, lazy, useCallback, useEffect, useState } from 'react'
import { Route, Routes, useParams } from 'react-router-dom'
import AppShell from '@/shell/AppShell'
import BootGate from '@/shell/BootGate'
import { markBootInputReady } from '@/shell/launch-perf'
import { getPlatform } from '@/platform'
import { services } from '@/services'

const AiChatPage = lazy(() => import('@/pages/AiChatPage'))
const FlowLibraryPage = lazy(() => import('@/pages/FlowLibraryPage'))
const SchedulesPage = lazy(() => import('@/pages/SchedulesPage'))
const ToolsPage = lazy(() => import('@/pages/ToolsPage'))
const StorePage = lazy(() => import('@/pages/StorePage'))
const AccountPage = lazy(() => import('@/pages/AccountPage'))
const SettingsPage = lazy(() => import('@/pages/SettingsPage'))
const AboutPage = lazy(() => import('@/pages/AboutPage'))
const PluginPage = lazy(() => import('@/pages/PluginPage'))
const NotFoundPage = lazy(() => import('@/pages/NotFoundPage'))
const SetupPage = lazy(() => import('@/pages/SetupPage'))

type BootMode = 'booting' | 'setup' | 'app'

/** Decide setup vs app from the backend; null = the probe failed (backend down). */
const probeBootMode = async (): Promise<Exclude<BootMode, 'booting'> | null> => {
  try {
    const status = await services.system.setupStatus()
    return status.initialized ? 'app' : 'setup'
  } catch {
    return null
  }
}

/** PluginPage takes the plugin id as a prop; the route param feeds it (FlowLibraryPage pattern). */
function PluginPageRoute() {
  const { id } = useParams()
  return <PluginPage id={id ?? ''} />
}

/**
 * Root: probe SETUP vs APP mode once (the backend's setup status endpoint), gate on backend
 * readiness, then hand over to the shell's routes. Mirrors the Vue App.vue BootGate logic.
 *
 * The hand-off is a cross-fade: once the gate opens, BootGate stays mounted as a
 * fading veil over the freshly mounted app shell (see BootGate's exiting mode),
 * so the startup screen dissolves into the interface instead of hard-cutting.
 */
export default function App() {
  const [mode, setMode] = useState<BootMode>('booting')
  // True while the boot screen is cross-fading out over the mounted app. Keeps
  // BootGate alive past its gate so its visual (and running animations) persist.
  const [bootExit, setBootExit] = useState(false)

  /** Leave the boot gate: swap in the destination mode under the fading boot veil. */
  const enterApp = useCallback((next: Exclude<BootMode, 'booting'>) => {
    setMode(next)
    setBootExit(true)
  }, [])

  useEffect(() => {
    let cancelled = false
    const probe = async () => {
      const isDesktop = getPlatform().kind === 'desktop'
      const next = await probeBootMode()
      if (cancelled) return
      if (next !== null) {
        enterApp(next)
        return
      }
      // Desktop: the backend is still booting — hold the boot gate (its health
      // poll and the shell's in-app failure screen own recovery). Browser: no
      // shell to recover, fall through to the app shell as before.
      if (isDesktop) setMode('booting')
      else enterApp('app')
    }
    void probe()
    return () => { cancelled = true }
  }, [enterApp])

  // T6 launch mark: the app shell is mounting (every path into 'app' — probe ok,
  // probe failed, or the boot gate's health poll). First-run SETUP is deliberately
  // excluded, like ZCode excludes its welcome screen from launch-to-input.
  useEffect(() => {
    if (mode === 'app') markBootInputReady()
  }, [mode])

  /** The gate's health poll passed — now decide setup vs app (the mount-time probe
   *  ran against a backend that was not up yet on desktop). A failing probe here,
   *  right after /api/health answered, falls to 'app' (the shell's boot modes are
   *  only setup/app anyway). */
  const handleBootReady = useCallback(() => {
    void (async () => {
      enterApp((await probeBootMode()) ?? 'app')
    })()
  }, [enterApp])

  const handleBootExited = useCallback(() => setBootExit(false), [])

  return (
    <>
      {(mode === 'booting' || bootExit) && (
        <BootGate
          onReady={handleBootReady}
          exiting={mode !== 'booting'}
          onExited={handleBootExited}
        />
      )}
      {mode === 'setup' && (
        <Suspense fallback={<div className="cx-grow" />}>
          <SetupPage />
        </Suspense>
      )}
      {mode === 'app' && (
        <AppShell>
          <Suspense fallback={<div className="cx-grow" />}>
            <Routes>
              <Route path="/" element={<AiChatPage />} />
              <Route path="/flows" element={<FlowLibraryPage />} />
              <Route path="/flows/new" element={<FlowLibraryPage />} />
              <Route path="/flows/:id" element={<FlowLibraryPage />} />
              <Route path="/schedules" element={<SchedulesPage />} />
              <Route path="/tools" element={<ToolsPage />} />
              <Route path="/store" element={<StorePage />} />
              <Route path="/account" element={<AccountPage />} />
              <Route path="/settings" element={<SettingsPage />} />
              <Route path="/about" element={<AboutPage />} />
              <Route path="/plugin/:id" element={<PluginPageRoute />} />
              <Route path="*" element={<NotFoundPage />} />
            </Routes>
          </Suspense>
        </AppShell>
      )}
    </>
  )
}
