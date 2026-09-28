import { fengyu } from '@infinia/plugin-sdk'
import { mountFengYuApp } from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
import App from './App'
import { tables } from './i18n'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
// Bind to a local so the narrowed (non-undefined) type survives the top-level await
// below — TS widens imported const bindings across await.
const client = fengyu
// mountFengYuApp owns the whole lifecycle: client provider, host-driven i18n over the
// `exui.*` tables, theme binding, notification host, and the React root.
await mountFengYuApp({ root: App, client, messages: tables })
