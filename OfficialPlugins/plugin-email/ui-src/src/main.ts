import { createApp, watchEffect } from 'vue'
import { createPinia } from 'pinia'
import { createFengYuVuetify, provideFengYuClient } from '@fengyu/plugin-ui'
import '@fengyu/plugin-ui/style.css'
import App from './App.vue'
import './styles.css'
import { client, disposeSdk, initializeSdk, useEnvironment } from './sdk'
import { i18n, syncLocale } from './i18n'

const app = createApp(App)
const vuetify = createFengYuVuetify()
provideFengYuClient(app, client)
app.use(createPinia()).use(i18n).use(vuetify).mount('#app')
// sdk.ts drives the reactive environment from client.ready() + `environment` events;
// mirror it into Vuetify theme + vue-i18n locale.
watchEffect(() => {
  vuetify.theme.change(useEnvironment().theme === 'light' ? 'fengyuCodexLight' : 'fengyuCodexDark')
  syncLocale(useEnvironment().locale)
})
initializeSdk().catch(error => document.dispatchEvent(new CustomEvent('fengyu-sdk-error', { detail: error })))
window.addEventListener('pagehide', () => { disposeSdk() }, { once: true })
