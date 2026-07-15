import { createApp } from 'vue'
import { fengyu } from '@fengyu/plugin-sdk'
import { bindFengYuEnvironment, createFengYuVuetify, provideFengYuClient } from '@fengyu/plugin-ui'
import '@fengyu/plugin-ui/style.css'
import App from './App.vue'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
const vuetify = createFengYuVuetify()
const disposeEnvironment = await bindFengYuEnvironment(vuetify, fengyu)
const app = createApp(App)
provideFengYuClient(app, fengyu)
app.use(vuetify)
app.mount('#app')
window.addEventListener('pagehide', () => { disposeEnvironment(); fengyu.dispose() }, { once: true })
