import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { i18n } from '@/i18n'
import { useThemeStore } from '@/stores/theme'
import { useSettingsStore } from '@/stores/settings'
import { useNotificationsStore } from '@/stores/notifications'
import './theme/tokens.css'
import './theme/codex.css'
import { vuetify } from './plugins/vuetify'
import { setAccountProvider } from '@/auth/accountProvider'
import { ApiAccountProvider } from '@/auth/apiAccountProvider'

// Account pages talk to the loopback host, which drives the OAuth 2.1 + PKCE
// browser flow against the Infinia Store (design §7.2).
setAccountProvider(new ApiAccountProvider())

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(i18n)
app.use(vuetify)

// Last-resort error reporting for a long-lived desktop webview (E4): stray renderer errors
// and unhandled promise rejections would otherwise only reach the console. Detail goes to the
// console for diagnosis; the user gets an error toast through the notifications center.
function reportRendererError(detail: unknown): void {
  console.error(detail)
  try {
    const notifications = useNotificationsStore()
    notifications.receive({
      id: -Date.now(),
      source: 'host',
      level: 'error',
      title: i18n.global.t('common.unexpectedError'),
      body: detail instanceof Error ? detail.message : String(detail ?? ''),
      link: null,
      read: false,
      createdAt: new Date().toISOString(),
      readAt: null,
    })
  } catch {
    /* reporting must never throw again */
  }
}

app.config.errorHandler = (err, _instance, info) => {
  reportRendererError(err ?? info)
}

window.addEventListener('unhandledrejection', (event) => {
  reportRendererError(event.reason)
})

// Apply a saved theme class to <html> as early as possible (avoids flash).
// index.html defaults to .theme-dark; settings load may switch it.
const theme = useThemeStore()
theme.setTheme(
  typeof window !== 'undefined' && window.fengyu
    ? window.fengyu.initialTheme()
    : theme.theme,
)

// Best-effort load of persisted settings from the backend. Failure (e.g.
// backend not up yet) is non-fatal — the shell still renders with defaults.
useSettingsStore()
  .load()
  .catch(() => {
    /* StatusBar surfaces connectivity; keep defaults */
  })

app.mount('#app')
