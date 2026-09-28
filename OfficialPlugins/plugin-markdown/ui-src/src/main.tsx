import { fengyu } from '@infinia/plugin-sdk'
import { mountFengYuApp } from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
import './styles.css'
import App from './App'
import { pluginI18n, tables } from './i18n'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
// Bind to a local so the narrowed (non-undefined) type survives the top-level await
// below — TS widens imported const bindings across await.
const client = fengyu
await mountFengYuApp({
  root: App,
  client,
  messages: tables,
  onEnvironment: pluginI18n.applyEnvironment,
})
