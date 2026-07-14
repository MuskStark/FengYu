import { createApp, watchEffect } from 'vue'
import { createPinia } from 'pinia'
import { createVuetify } from 'vuetify'
import { VAlert, VApp, VBtn, VBtnToggle, VCard, VCardActions, VCardText, VCardTitle, VCheckbox,
  VCheckboxBtn, VChip, VCombobox, VDialog, VList, VListItem, VMain, VSelect, VSheet, VSpacer,
  VTab, VTable, VTabs, VTextarea, VTextField, VWindow, VWindowItem } from 'vuetify/components'
import { Ripple } from 'vuetify/directives'
import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import App from './App.vue'
import './styles.css'
import { disposeSdk, initializeSdk, useEnvironment } from './sdk'

const app = createApp(App)
const vuetify = createVuetify({
  components: { VAlert, VApp, VBtn, VBtnToggle, VCard, VCardActions, VCardText, VCardTitle, VCheckbox,
    VCheckboxBtn, VChip, VCombobox, VDialog, VList, VListItem, VMain, VSelect, VSheet, VSpacer,
    VTab, VTable, VTabs, VTextarea, VTextField, VWindow, VWindowItem },
  directives: { Ripple }, theme: { defaultTheme: 'light' },
})
app.use(createPinia()).use(vuetify).mount('#app')
watchEffect(() => { vuetify.theme.global.name.value = useEnvironment().theme })
initializeSdk().catch(error => document.dispatchEvent(new CustomEvent('fengyu-sdk-error', { detail: error })))
window.addEventListener('unload', disposeSdk, { once: true })
