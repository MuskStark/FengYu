import { Suspense, lazy, useEffect, useState } from 'react'
import { Route, Routes, useParams } from 'react-router-dom'
import AppShell from '@/shell/AppShell'
import BootGate from '@/shell/BootGate'
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

/** PluginPage takes the plugin id as a prop; the route param feeds it (FlowLibraryPage pattern). */
function PluginPageRoute() {
  const { id } = useParams()
  return <PluginPage id={id ?? ''} />
}

/**
 * Root: probe SETUP vs APP mode once (the backend's setup status endpoint), gate on backend
 * readiness, then hand over to the shell's routes. Mirrors the Vue App.vue BootGate logic.
 */
export default function App() {
  const [mode, setMode] = useState<BootMode>('booting')

  useEffect(() => {
    let cancelled = false
    const probe = async () => {
      try {
        const status = await services.system.setupStatus()
        if (!cancelled) setMode(status.initialized ? 'app' : 'setup')
      } catch {
        if (!cancelled) setMode('app') // backend briefly unreachable — BootGate keeps polling health
      }
    }
    void probe()
    return () => { cancelled = true }
  }, [])

  if (mode === 'booting') return <BootGate onReady={() => setMode('app')} />
  if (mode === 'setup') {
    return (
      <Suspense fallback={<div className="cx-grow" />}>
        <SetupPage />
      </Suspense>
    )
  }

  return (
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
  )
}
