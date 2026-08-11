import { fengyu } from '@infinia/plugin-sdk'
import { mountFengYuApp } from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
import App from './MarkdownEditor.vue'
import { pluginI18n } from './i18n'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
// Bind to a local so the narrowed (non-undefined) type survives the top-level await
// below — TS widens imported const bindings across await.
const client = fengyu
await mountFengYuApp({ root: App, client, onEnvironment: pluginI18n.applyEnvironment })
