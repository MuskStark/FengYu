<script setup lang="ts">
import Sidebar from './Sidebar.vue'
import NotificationToasts from '@/components/notifications/NotificationToasts.vue'
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAiSessionStore } from '@/stores/aiSession'
import { useNotificationsStore } from '@/stores/notifications'
import { useUpdateStore } from '@/stores/update'

const route = useRoute()
const { t } = useI18n()
const ai = useAiSessionStore()
const notifications = useNotificationsStore()
const update = useUpdateStore()

// One live notification stream per shell (toasts, native desktop notifications, and the
// bell badge all hang off this single subscription). Lives HERE, not in Sidebar, so the
// stream also opens on the settings route where the sidebar is unmounted.
onMounted(() => {
  notifications.init()
  // Update probe (startup + periodic) rides here for the same reason — AppShell is the one
  // component mounted on every route, so the About-button red dot appears wherever the
  // user lands. Non-blocking; the dot stays hidden until a newer release is found.
  void update.check()
  update.startPeriodicChecks()
})
const settingsRoute = computed(() => route.name === 'settings')
const macTitleBar = computed(() => window.fengyu?.platform === 'darwin')
const showChatHeader = computed(() => route.name === 'ai')
const routeTitles: Record<string, string> = {
  tools: 'grid.title',
  agent: 'agent.title',
  'plugin-market': 'market.title',
  store: 'store.title',
  account: 'account.title',
  settings: 'settings.title',
  about: 'about.title',
}
const headerTitle = computed(() => {
  if (route.name === 'ai') return ai.active?.title || t('aichat.title')
  if (route.name === 'plugin') return String(route.params.id || t('tools.title'))
  const key = routeTitles[String(route.name)]
  return key ? t(key) : t('brand')
})

</script>

<template>
  <div class="cx-shell" :class="{ 'mac-titlebar': macTitleBar, 'chat-header-visible': showChatHeader, 'settings-shell': settingsRoute }">
    <Sidebar v-if="!settingsRoute" />
    <div class="cx-content-column">
      <header v-if="showChatHeader" class="shell-header">
        <i class="mdi mdi-folder-outline shell-header-icon" aria-hidden="true" />
        <span class="shell-header-title">{{ headerTitle }}</span>
        <button class="cx-iconbtn cx-iconbtn--sm shell-header-more" aria-label="More options">
          <i class="mdi mdi-dots-horizontal" />
        </button>
      </header>
      <main class="cx-main">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </main>
    </div>
    <!-- Unified host notifications: live toasts over every non-setup route. -->
    <NotificationToasts />
  </div>
</template>

<style scoped>
.cx-shell {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  height: 100%;
  width: 100%;
  overflow: hidden;
}
.cx-content-column {
  flex: 1 1 auto;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.shell-header {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 14px;
  flex: 0 0 var(--cx-window-bar-height);
  min-height: var(--cx-window-bar-height);
  padding: 0 18px 0 28px;
  border-bottom: 1px solid var(--cx-border);
  background: rgb(var(--v-theme-background));
  -webkit-app-region: drag;
  user-select: none;
}
.shell-header-icon { font-size: 23px; color: rgb(var(--v-theme-on-surface)); }
.shell-header-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 17px;
  font-weight: 600;
}
.shell-header-more { margin-left: 0; -webkit-app-region: no-drag; }
.shell-header-more .mdi { font-size: 20px; }
.cx-shell.mac-titlebar :deep(.cx-sidebar)::before {
  content: '';
  display: block;
  flex: 0 0 var(--cx-native-titlebar-space);
  height: var(--cx-native-titlebar-space);
  -webkit-app-region: drag;
}
.cx-shell.mac-titlebar.settings-shell :deep(.set-nav)::before {
  content: '';
  display: block;
  height: 24px;
  margin: -10px -12px 0;
  -webkit-app-region: drag;
}
.cx-main {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
