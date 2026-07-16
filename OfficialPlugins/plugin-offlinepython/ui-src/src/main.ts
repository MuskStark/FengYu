import { createApp } from 'vue'
import { fengyu } from '@infinia/plugin-sdk'
import { bindFengYuEnvironment, createFengYuVuetify, provideFengYuClient } from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
import App from './App.vue'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
// Bind to a local so the narrowed (non-undefined) type survives the top-level await
// below — TS widens imported const bindings across await.
const client = fengyu
const vuetify = createFengYuVuetify()
const disposeEnvironment = await bindFengYuEnvironment(vuetify, client)
const app = createApp(App)
provideFengYuClient(app, client)
app.use(vuetify)
app.mount('#app')
window.addEventListener('pagehide', () => { disposeEnvironment(); client.dispose() }, { once: true })
