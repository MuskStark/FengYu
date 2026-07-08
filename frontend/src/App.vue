<script setup lang="ts">
import { watchEffect } from 'vue'
import { useThemeStore } from './stores/theme'
import AppShell from './shell/AppShell.vue'

// Keep the <html> theme class in sync with the store. The store's setTheme
// already stamps the class; this guards against any external reset.
const theme = useThemeStore()
watchEffect(() => {
  const root = document.documentElement
  root.classList.remove('theme-dark', 'theme-light')
  root.classList.add(theme.theme === 'light' ? 'theme-light' : 'theme-dark')
})
</script>

<template>
  <AppShell />
</template>
