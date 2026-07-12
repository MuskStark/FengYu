<script setup lang="ts">
import { useRoute } from 'vue-router'
import AppShell from './shell/AppShell.vue'
import StatusBar from './shell/StatusBar.vue'

const route = useRoute()
</script>

<template>
  <!-- v-app is required by Vuetify for theme/layout (data-v-app). -->
  <v-app>
    <!-- Setup wizard renders full-screen without the app shell. -->
    <router-view v-if="route.name === 'setup'" />
    <AppShell v-else />
    <!-- Global bottom info bar: version + backend state. Always present. -->
    <StatusBar />
  </v-app>
</template>
