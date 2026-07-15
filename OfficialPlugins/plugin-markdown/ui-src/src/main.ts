import { createApp } from 'vue'
import { fengyu } from '@fengyu/plugin-sdk'
import { bindFengYuEnvironment, createFengYuVuetify, provideFengYuClient } from '@fengyu/plugin-ui'
import '@fengyu/plugin-ui/style.css'
import App from './MarkdownEditor.vue'

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
