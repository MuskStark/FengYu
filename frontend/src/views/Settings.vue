<script setup lang="ts">
import { onMounted } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import type { LanguageName, ThemeName } from '@/api/types'

const settings = useSettingsStore()

onMounted(() => {
  if (!settings.loaded) void settings.load().catch(() => {})
})

const themes: ThemeName[] = ['dark', 'light']
const languages: LanguageName[] = ['en', 'zh']
</script>

<template>
  <v-container max-width="640">
    <h1 class="text-h5 mb-4">{{ $t('settings.title') }}</h1>

    <v-list lines="two">
      <v-list-item>
        <template #prepend><v-icon icon="mdi-palette-outline" /></template>
        <v-list-item-title>{{ $t('settings.theme') }}</v-list-item-title>
        <template #append>
          <v-select
            :model-value="settings.theme"
            :items="themes"
            density="compact"
            variant="outlined"
            hide-details
            style="max-width: 160px"
            @update:model-value="(v: ThemeName) => settings.setTheme(v)"
          />
        </template>
      </v-list-item>

      <v-list-item>
        <template #prepend><v-icon icon="mdi-translate" /></template>
        <v-list-item-title>{{ $t('settings.language') }}</v-list-item-title>
        <template #append>
          <v-select
            :model-value="settings.language"
            :items="languages"
            density="compact"
            variant="outlined"
            hide-details
            style="max-width: 160px"
            @update:model-value="(v: LanguageName) => settings.setLanguage(v)"
          />
        </template>
      </v-list-item>
    </v-list>
  </v-container>
</template>
