<script setup lang="ts">
import { onMounted } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import type { LanguageName, ThemeName } from '@/api/types'

const settings = useSettingsStore()

onMounted(() => {
  if (!settings.loaded) void settings.load().catch(() => {})
})

function onTheme(e: Event) {
  const v = (e.target as HTMLSelectElement).value as ThemeName
  void settings.setTheme(v)
}
function onLanguage(e: Event) {
  const v = (e.target as HTMLSelectElement).value as LanguageName
  void settings.setLanguage(v)
}
</script>

<template>
  <div class="settings-page">
    <h1 class="section-header page-title">Settings</h1>

    <div class="row">
      <label class="lbl">Theme</label>
      <select class="sk-combo" :value="settings.theme" @change="onTheme">
        <option value="dark">Dark</option>
        <option value="light">Light</option>
      </select>
    </div>

    <div class="row">
      <label class="lbl">Language</label>
      <select class="sk-combo" :value="settings.language" @change="onLanguage">
        <option value="en">English</option>
        <option value="zh">中文</option>
      </select>
    </div>
  </div>
</template>

<style scoped>
.settings-page {
  padding: 20px 24px;
  max-width: 520px;
}
.page-title {
  margin: 0 0 20px;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid var(--sk-border);
}
.lbl {
  color: var(--sk-text);
  font-weight: 500;
}
.sk-combo {
  min-width: 160px;
}
</style>
