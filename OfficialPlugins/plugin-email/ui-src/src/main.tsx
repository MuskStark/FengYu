import { mountFengYuApp } from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
import './styles.css'
import App from './App'
import { applyEnvironment, client } from './sdk'
import { messages, sdkI18n } from './i18n'

await mountFengYuApp({
  root: App,
  client,
  messages,
  onEnvironment(environment) {
    applyEnvironment(environment)
    // Keep the module-level runtime (sdk.actionable) on the host locale; the
    // in-tree runtime is driven by mountFengYuApp itself.
    sdkI18n.applyEnvironment(environment)
  },
  onReadyError(error) {
    document.dispatchEvent(new CustomEvent('fengyu-sdk-error', { detail: error }))
  },
})
