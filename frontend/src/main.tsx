import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { configureServices } from '@/services'
import { instance as i18nInstance } from '@/i18n'
import './styles/index.css'

// Service-layer bootstrap (see docs/service-layer.md §4): the one place the app injects
// its runtime locale into the framework-free service layer.
configureServices({ locale: () => i18nInstance.language ?? 'en' })

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)

// Global error surfaces → toast, mirroring the Vue shell's errorHandler pipeline.
window.addEventListener('error', (event) => {
  if (event.message) console.warn('[shell]', event.message)
})
window.addEventListener('unhandledrejection', (event) => {
  console.warn('[shell] unhandled rejection:', event.reason)
})
