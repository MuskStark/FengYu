<script setup lang="ts">
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/settings'
import type { LanguageName, ThemeName } from '@/api/types'

const { t } = useI18n()
const settings = useSettingsStore()

onMounted(() => {
  if (!settings.loaded) void settings.load().catch(() => {})
})

// Vuetify v-select renders `title`; the `value` (the enum) is what flows back
// into the store. Localized labels reuse the existing settings.* i18n keys.
const themeItems: { title: string; value: ThemeName }[] = [
  { title: t('settings.dark'), value: 'dark' },
  { title: t('settings.light'), value: 'light' },
]
const languageItems: { title: string; value: LanguageName }[] = [
  { title: t('settings.english'), value: 'en' },
  { title: t('settings.chinese'), value: 'zh' },
]
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
            :items="themeItems"
            item-title="title"
            item-value="value"
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
            :items="languageItems"
            item-title="title"
            item-value="value"
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
