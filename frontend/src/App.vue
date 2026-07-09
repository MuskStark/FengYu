<script setup lang="ts">
import { watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { useThemeStore } from './stores/theme'
import AppShell from './shell/AppShell.vue'

const theme = useThemeStore()
const route = useRoute()

// Keep the <html> theme class in sync with the store.
watchEffect(() => {
  const root = document.documentElement
  root.classList.remove('theme-dark', 'theme-light')
  root.classList.add(theme.theme === 'light' ? 'theme-light' : 'theme-dark')
})
</script>

<template>
  <!-- Setup wizard renders full-screen without the app shell -->
  <router-view v-if="route.name === 'setup'" />
  <AppShell v-else />
</template>
