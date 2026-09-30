import { createPinia } from 'pinia'
import { mountFengYuApp } from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
import App from './App.vue'
import './styles.css'
import { applyEnvironment, client } from './sdk'
import { i18n, syncLocale } from './i18n'

await mountFengYuApp({
  root: App,
  client,
  plugins: [createPinia(), i18n],
  onEnvironment(environment) {
    applyEnvironment(environment)
    syncLocale(environment.locale)
  },
  onReadyError(error) {
    document.dispatchEvent(new CustomEvent('fengyu-sdk-error', { detail: error }))
  },
})
