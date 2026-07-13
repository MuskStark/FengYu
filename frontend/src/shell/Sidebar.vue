<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { useThemeStore } from '@/stores/theme'
import { useAiSessionStore } from '@/stores/aiSession'

const settings = useSettingsStore()
const theme = useThemeStore()
const ai = useAiSessionStore()
const router = useRouter()
const route = useRoute()

const rail = computed(() => settings.sidebarCollapsed)

onMounted(() => {
  void ai.loadHistory()
})

function startChat() {
  ai.newConversation()
  if (route.name !== 'ai') void router.push('/')
}
function openConversation(id: number) {
  void ai.select(id)
  if (route.name !== 'ai') void router.push('/')
}

const bottomNav = [
  { key: 'tools', to: '/tools', labelKey: 'sidebar.all', icon: 'mdi-view-grid-outline' },
  { key: 'plugins', to: '/plugins', labelKey: 'sidebar.plugins', icon: 'mdi-puzzle-outline' },
  { key: 'agent', to: '/agent', labelKey: 'sidebar.agent', icon: 'mdi-robot-outline' },
  { key: 'settings', to: '/settings', labelKey: 'sidebar.settings', icon: 'mdi-cog-outline' },
]
</script>

<template>
  <aside class="cx-sidebar" :class="{ rail }">
    <!-- Brand + collapse -->
    <div class="cx-row" :class="rail ? 'justify-center px-0' : ''" style="padding: 12px; gap: 10px">
      <img class="brand-logo" src="/infinia-logo.svg" alt="" />
      <span v-if="!rail" class="cx-grow" style="font-weight: 600">{{ $t('brand') }}</span>
      <button
        v-if="!rail"
        class="cx-iconbtn cx-iconbtn--sm"
        :title="$t('sidebar.collapse')"
        @click="settings.setSidebarCollapsed(true)"
      ><i class="mdi mdi-dock-left" /></button>
    </div>

    <!-- New chat -->
    <div style="padding: 0 8px 4px">
      <button
        v-if="!rail"
        class="cx-btn cx-btn--tonal cx-btn--block"
        style="justify-content: flex-start"
        @click="startChat"
      ><i class="mdi mdi-plus" />{{ $t('sidebar.newChat') }}</button>
      <button
        v-else
        class="cx-iconbtn"
        style="margin: 0 auto; display: flex"
        :title="$t('sidebar.newChat')"
        @click="startChat"
      ><i class="mdi mdi-plus" /></button>
    </div>

    <!-- Conversation history -->
    <div v-if="!rail" class="cx-grow" style="overflow-y: auto; padding-bottom: 6px">
      <div v-if="ai.conversations.length" class="cx-subheader">{{ $t('sidebar.history') }}</div>
      <div
        v-for="c in ai.conversations"
        :key="c.id"
        class="cx-nav-item"
        :class="{ active: c.id === ai.activeId }"
        @click="openConversation(c.id)"
      >
        <span class="cx-nav-label">{{ c.title || $t('sidebar.untitled') }}</span>
        <button
          class="cx-iconbtn cx-iconbtn--sm"
          :title="$t('aichat.clear')"
          @click.stop="ai.removeConversation(c.id)"
        ><i class="mdi mdi-close" /></button>
      </div>
    </div>
    <div v-else class="cx-grow" />

    <!-- Bottom nav -->
    <div style="padding: 6px 0; border-top: 1px solid rgb(var(--v-theme-outline-variant))">
      <div
        v-if="rail"
        class="cx-nav-item rail"
        :title="$t('sidebar.expand')"
        @click="settings.setSidebarCollapsed(false)"
      ><i class="mdi mdi-dock-right" /></div>
      <div
        v-for="item in bottomNav"
        :key="item.key"
        class="cx-nav-item"
        :class="{ rail, active: route.path === item.to }"
        :title="rail ? $t(item.labelKey) : undefined"
        @click="router.push(item.to)"
      >
        <i class="mdi" :class="item.icon" />
        <span v-if="!rail" class="cx-nav-label">{{ $t(item.labelKey) }}</span>
      </div>
      <div
        class="cx-nav-item"
        :class="{ rail }"
        :title="$t('sidebar.theme')"
        @click="settings.setTheme(theme.theme === 'dark' ? 'light' : 'dark')"
      >
        <i class="mdi" :class="theme.theme === 'dark' ? 'mdi-weather-night' : 'mdi-weather-sunny'" />
        <span v-if="!rail" class="cx-nav-label">{{ $t('sidebar.theme') }}</span>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.brand-logo {
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  object-fit: contain;
}
</style>
