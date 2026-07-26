<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useThemeStore } from '@/stores/theme'
import AppShell from './shell/AppShell.vue'
import StatusBar from './shell/StatusBar.vue'

const route = useRoute()
const theme = useThemeStore()

// Vuetify emits `.v-theme--dark { --v-theme-* }` / `.v-theme--light { … }`.
// We no longer mount <v-app>, so stamp the matching class on our own root to
// resolve those CSS vars. Flips live with the theme store.
const themeClass = computed(() => `v-theme--${theme.theme}`)
</script>

<template>
  <div class="cx-root" :class="themeClass">
    <div class="cx-body">
      <router-view v-if="route.name === 'setup'" />
      <AppShell v-else />
    </div>
    <StatusBar />
  </div>
</template>

<style scoped>
.cx-root {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: rgb(var(--v-theme-background));
  color: rgb(var(--v-theme-on-surface));
}
.cx-body {
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  display: flex;
}
</style>
