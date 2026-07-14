import { createApp, watchEffect } from 'vue'
import { createPinia } from 'pinia'
import { createVuetify } from 'vuetify'
import { VAlert, VApp, VBtn, VBtnToggle, VCard, VCardActions, VCardText, VCardTitle, VCheckbox,
  VCheckboxBtn, VChip, VCombobox, VDialog, VList, VListItem, VMain, VSelect, VSheet, VSpacer,
  VTab, VTable, VTabs, VTextarea, VTextField, VWindow, VWindowItem } from 'vuetify/components'
import { Ripple } from 'vuetify/directives'
import 'vuetify/styles'
import App from './App.vue'
import './styles.css'
import { disposeSdk, initializeSdk, useEnvironment } from './sdk'
import { i18n, syncLocale } from './i18n'

const emailLightTheme = {
  dark: false,
  colors: {
    background: '#f7f7f5', surface: '#ffffff', 'surface-variant': '#f0f0ed',
    primary: '#292927', 'on-primary': '#ffffff', secondary: '#2f8f57',
    success: '#2f8f57', info: '#4b6b88', warning: '#9a6700', error: '#b42318',
    'on-background': '#20201e', 'on-surface': '#20201e', outline: '#deded9',
  },
}
const emailDarkTheme = {
  dark: true,
  colors: {
    background: '#181817', surface: '#212120', 'surface-variant': '#292927',
    primary: '#eeeeeb', 'on-primary': '#20201e', secondary: '#55b779',
    success: '#55b779', info: '#8bb4d8', warning: '#e4b65a', error: '#ff8a80',
    'on-background': '#eeeeeb', 'on-surface': '#eeeeeb', outline: '#3a3a37',
  },
}

const app = createApp(App)
const vuetify = createVuetify({
  components: { VAlert, VApp, VBtn, VBtnToggle, VCard, VCardActions, VCardText, VCardTitle, VCheckbox,
    VCheckboxBtn, VChip, VCombobox, VDialog, VList, VListItem, VMain, VSelect, VSheet, VSpacer,
    VTab, VTable, VTabs, VTextarea, VTextField, VWindow, VWindowItem },
  directives: { Ripple },
  theme: { defaultTheme: 'light', themes: { light: emailLightTheme, dark: emailDarkTheme } },
  defaults: {
    VAlert: { density: 'compact', variant: 'tonal', border: 'start' },
    VBtn: { density: 'compact', height: 34, rounded: 'lg', elevation: 0 },
    VCard: { elevation: 0 },
    VCheckbox: { density: 'compact', color: 'secondary', hideDetails: 'auto' },
    VCheckboxBtn: { density: 'compact', color: 'secondary' },
    VChip: { density: 'compact', size: 'small', variant: 'tonal' },
    VCombobox: { density: 'compact', variant: 'outlined', color: 'secondary', hideDetails: 'auto' },
    VDialog: { transition: 'fade-transition', scrim: '#000000' },
    VList: { density: 'compact', bgColor: 'transparent' },
    VListItem: { density: 'compact', rounded: 'lg' },
    VSelect: { density: 'compact', variant: 'outlined', color: 'secondary', hideDetails: 'auto' },
    VTable: { density: 'compact' },
    VTextarea: { density: 'compact', variant: 'outlined', color: 'secondary', hideDetails: 'auto' },
    VTextField: { density: 'compact', variant: 'outlined', color: 'secondary', hideDetails: 'auto' },
  },
})
app.use(createPinia()).use(i18n).use(vuetify).mount('#app')
watchEffect(() => {
  vuetify.theme.global.name.value = useEnvironment().theme
  syncLocale(useEnvironment().locale)
})
initializeSdk().catch(error => document.dispatchEvent(new CustomEvent('fengyu-sdk-error', { detail: error })))
window.addEventListener('unload', disposeSdk, { once: true })
