<script setup lang="ts">
import { inject } from 'vue';
import type { PluginUiContext } from './pluginUi';

// Host context is provided by main.ts via app.provide('pluginCtx', ctx).
// Placeholder component: proves the mount + Vuetify sharing works.
// Task 14 replaces this with the real split wizard.
const ctx = inject<PluginUiContext>('pluginCtx')!;
</script>

<template>
  <v-container>
    <v-alert type="info">Excel Splitter — {{ ctx.desktop ? 'desktop' : 'web' }} mode</v-alert>
  </v-container>
</template>
