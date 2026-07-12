import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { i18n } from '@/i18n'
import { useThemeStore } from './stores/theme'
import { useSettingsStore } from './stores/settings'
import './theme/tokens.css'
import './theme/codex.css'
import { vuetify } from './plugins/vuetify'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(i18n)
app.use(vuetify)

// Apply a saved theme class to <html> as early as possible (avoids flash).
// index.html defaults to .theme-dark; settings load may switch it.
const theme = useThemeStore()
theme.setTheme(theme.theme)

// Best-effort load of persisted settings from the backend. Failure (e.g.
// backend not up yet) is non-fatal — the shell still renders with defaults.
useSettingsStore()
  .load()
  .catch(() => {
    /* StatusBar surfaces connectivity; keep defaults */
  })

app.mount('#app')
