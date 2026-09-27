import { StrictMode, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { configureServices } from '@/services'
import { instance as i18nInstance } from '@/i18n'
import { getPlatform } from '@/platform'
import { markReactCommit, markRendererStart } from '@/shell/launch-perf'
import { applyUiScale, readUiScale } from '@/lib/uiScale'
import './styles/index.css'

// T4: the renderer bundle is executing (main.tsx module top).
markRendererStart()

// Restore the saved UI scale before first paint of the React tree — otherwise a
// non-default scale only reappears after the user re-opens appearance settings.
applyUiScale(readUiScale())

// Service-layer bootstrap (see docs/service-layer.md §4): the one place the app injects
// its runtime locale into the framework-free service layer.
configureServices({ locale: () => i18nInstance.language ?? 'en' })

/**
 * Hands the screen from the static HTML boot shell (index.html #boot-loading) to
 * React: on the first commit it records the T5 launch mark, adds
 * `fengyu-startup-ready` to <body> — which cross-fades the shell out and #root in —
 * and removes the shell shortly after. Idempotent under StrictMode's double effect
 * and HMR re-mounts (both marks and the class-add are guarded no-ops).
 */
function StartupReady() {
  useEffect(() => {
    markReactCommit()
    document.body.classList.add('fengyu-startup-ready')
    const shell = document.getElementById('boot-loading')
    if (shell) window.setTimeout(() => shell.remove(), 600)
  }, [])
  return null
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <StartupReady />
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)

// Global error surfaces → console, mirrored into desktop.log on the desktop platform
// (the renderer has no filesystem access; the platform layer is a no-op on the web).
window.addEventListener('error', (event) => {
  if (event.message) {
    console.warn('[shell]', event.message)
    const origin = event.filename ? ` @${event.filename}:${event.lineno}` : ''
    getPlatform().reportLog('error', `${event.message}${origin}`)
  }
})
window.addEventListener('unhandledrejection', (event) => {
  console.warn('[shell] unhandled rejection:', event.reason)
  const reason = event.reason instanceof Error
    ? `${event.reason.name}: ${event.reason.message}`
    : String(event.reason)
  getPlatform().reportLog('error', `unhandled rejection: ${reason}`)
})
