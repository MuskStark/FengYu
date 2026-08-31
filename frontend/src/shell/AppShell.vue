<script setup lang="ts">
import Sidebar from './Sidebar.vue'
import BackgroundExecutionIndicator from './BackgroundExecutionIndicator.vue'
import NotificationToasts from '@/components/notifications/NotificationToasts.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDisplay } from 'vuetify'
import { useAiSessionStore } from '@/stores/aiSession'
import { useNotificationsStore } from '@/stores/notifications'
import { useSettingsStore } from '@/stores/settings'
import { useUpdateStore } from '@/stores/update'
import {
  SIDEBAR_AUTO_COLLAPSE_VIEWPORT,
  SIDEBAR_DEFAULT_WIDTH,
  SIDEBAR_MAX_WIDTH_RATIO,
  SIDEBAR_MIN_WIDTH,
  clampSidebarWidth,
  isToggleSidebarShortcut,
  persistSidebarWidth,
  readPersistedSidebarWidth,
} from './sidebar-layout'

const route = useRoute()
const { t } = useI18n()
const ai = useAiSessionStore()
const notifications = useNotificationsStore()
const settings = useSettingsStore()
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
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})

const settingsRoute = computed(() => route.name === 'settings')
const macTitleBar = computed(() => window.fengyu?.platform === 'darwin')
const showChatHeader = computed(() => route.name === 'ai')

// ZCode-style collapse state lives in the shell (the sidebar only renders it). The persisted
// desktop setting wins; the browser shell additionally auto-collapses when the viewport gets
// too narrow for plugin content. Auto-collapse stays off on macOS so the native title-bar
// collapse control is always reversible.
const { width: viewportWidth } = useDisplay()
const autoCollapse = computed(() => viewportWidth.value < SIDEBAR_AUTO_COLLAPSE_VIEWPORT)
const sidebarCollapsed = computed(() => settings.sidebarCollapsed || (!macTitleBar.value && autoCollapse.value))

// Remembered expanded width: persisted locally (cosmetic, unlike the backend-owned collapse
// flag) and re-clamped whenever the viewport shrinks below half of it.
const sidebarWidth = ref(clampSidebarWidth(readPersistedSidebarWidth() ?? SIDEBAR_DEFAULT_WIDTH, viewportWidth.value))
watch(viewportWidth, viewport => {
  sidebarWidth.value = clampSidebarWidth(sidebarWidth.value, viewport)
})

function toggleSidebar() {
  settings.setSidebarCollapsed(!settings.sidebarCollapsed).catch(() => {})
}

/** ⌘B / Ctrl+B toggles the sidebar from anywhere outside the settings surface. */
function onKeydown(event: KeyboardEvent) {
  if (settingsRoute.value || event.repeat || event.isComposing) return
  if (!isToggleSidebarShortcut(event)) return
  event.preventDefault()
  toggleSidebar()
}

// ── drag-to-resize handle (ZCode parity: pointer drag + keyboard arrows on the separator) ──
const sidebarResizing = ref(false)
let resizeStartX = 0
let resizeStartWidth = 0

function onResizerPointerDown(event: PointerEvent) {
  sidebarResizing.value = true
  resizeStartX = event.clientX
  resizeStartWidth = sidebarWidth.value
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  event.preventDefault()
}

function onResizerPointerMove(event: PointerEvent) {
  if (!sidebarResizing.value) return
  const max = Math.max(SIDEBAR_MIN_WIDTH, window.innerWidth * SIDEBAR_MAX_WIDTH_RATIO)
  // Below the minimum the sidebar keeps shrinking (drag-to-collapse preview); the snap
  // decision happens on release.
  sidebarWidth.value = Math.max(0, Math.min(resizeStartWidth + event.clientX - resizeStartX, max))
}

function onResizerPointerUp() {
  if (!sidebarResizing.value) return
  sidebarResizing.value = false
  if (sidebarWidth.value < SIDEBAR_MIN_WIDTH) {
    // Dragged past the minimum width → collapse; restore a sane width for the next expand.
    sidebarWidth.value = Math.max(resizeStartWidth, SIDEBAR_MIN_WIDTH)
    persistSidebarWidth(sidebarWidth.value)
    toggleSidebar()
    return
  }
  persistSidebarWidth(sidebarWidth.value)
}

function onResizerPointerCancel() {
  if (!sidebarResizing.value) return
  sidebarResizing.value = false
  sidebarWidth.value = clampSidebarWidth(sidebarWidth.value, viewportWidth.value)
  persistSidebarWidth(sidebarWidth.value)
}

function onResizerKeydown(event: KeyboardEvent) {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
  event.preventDefault()
  const step = event.key === 'ArrowLeft' ? -16 : 16
  sidebarWidth.value = clampSidebarWidth(sidebarWidth.value + step, viewportWidth.value)
  persistSidebarWidth(sidebarWidth.value)
}

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
  <div
    class="cx-shell"
    :class="{ 'mac-titlebar': macTitleBar, 'chat-header-visible': showChatHeader, 'settings-shell': settingsRoute, 'sidebar-collapsed': sidebarCollapsed }"
  >
    <div v-if="macTitleBar && !settingsRoute" class="shell-window-controls">
      <button
        class="cx-iconbtn cx-iconbtn--sm shell-sidebar-toggle"
        :title="sidebarCollapsed ? $t('sidebar.expand') : $t('sidebar.collapse')"
        :aria-label="sidebarCollapsed ? $t('sidebar.expand') : $t('sidebar.collapse')"
        @click="toggleSidebar"
      ><i class="mdi" :class="sidebarCollapsed ? 'mdi-dock-right' : 'mdi-dock-left'" /></button>
    </div>
    <!-- Collapsed-corners handle: without a persistent top bar (non-macOS), this floating
         button is what keeps "collapse" reversible on every route. -->
    <button
      v-if="sidebarCollapsed && !macTitleBar && !settingsRoute"
      class="cx-iconbtn cx-iconbtn--sm shell-sidebar-handle"
      :title="$t('sidebar.expand')"
      :aria-label="$t('sidebar.expand')"
      @click="toggleSidebar"
    ><i class="mdi mdi-dock-right" /></button>
    <Sidebar
      v-if="!settingsRoute"
      :mac-title-bar="macTitleBar"
      :collapsed="sidebarCollapsed"
      :width="sidebarWidth"
      :resizing="sidebarResizing"
    />
    <div
      v-if="!settingsRoute && !sidebarCollapsed"
      class="shell-sidebar-resizer"
      :class="{ resizing: sidebarResizing }"
      role="separator"
      tabindex="0"
      aria-orientation="vertical"
      :aria-label="$t('sidebar.resize')"
      :aria-valuemin="SIDEBAR_MIN_WIDTH"
      :aria-valuenow="Math.round(sidebarWidth)"
      :aria-valuemax="Math.max(SIDEBAR_MIN_WIDTH, Math.round(viewportWidth * SIDEBAR_MAX_WIDTH_RATIO))"
      @pointerdown="onResizerPointerDown"
      @pointermove="onResizerPointerMove"
      @pointerup="onResizerPointerUp"
      @pointercancel="onResizerPointerCancel"
      @keydown="onResizerKeydown"
    />
    <div class="cx-content-column">
      <header v-if="showChatHeader" class="shell-header">
        <i class="mdi mdi-folder-outline shell-header-icon" aria-hidden="true" />
        <span class="shell-header-title">{{ headerTitle }}</span>
        <button class="cx-iconbtn cx-iconbtn--sm shell-header-more" aria-label="More options">
          <i class="mdi mdi-dots-horizontal" />
        </button>
        <span class="shell-header-drag-tail" aria-hidden="true" />
      </header>
      <main class="cx-main">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </main>
    </div>
    <BackgroundExecutionIndicator />
    <!-- Unified host notifications: live toasts over every non-setup route. -->
    <NotificationToasts />
  </div>
</template>

<style scoped>
.cx-shell {
  position: relative;
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  height: 100%;
  width: 100%;
  overflow: hidden;
}
.shell-window-controls {
  position: absolute;
  z-index: 30;
  inset: 0 auto auto 0;
  width: 112px;
  height: var(--cx-window-bar-height);
  app-region: drag;
  -webkit-app-region: drag;
}
.shell-sidebar-toggle {
  position: absolute;
  top: 10px;
  left: 84px;
  pointer-events: auto;
  app-region: no-drag;
  -webkit-app-region: no-drag;
}
.shell-sidebar-toggle .mdi { font-size: 17px; }
.shell-sidebar-handle {
  position: absolute;
  z-index: 30;
  top: 10px;
  left: 10px;
}
/* Drag-to-resize separator riding the sidebar's right edge; the visual hairline only
 * appears on hover/focus/drag so it stays out of the default chrome. */
.shell-sidebar-resizer {
  position: relative;
  z-index: 10;
  flex: 0 0 7px;
  width: 7px;
  height: 100%;
  margin-left: -4px;
  cursor: col-resize;
  touch-action: none;
}
.shell-sidebar-resizer::before {
  content: '';
  position: absolute;
  inset-block: 0;
  left: 3px;
  width: 1px;
  background: transparent;
  transition: background 0.12s ease;
}
.shell-sidebar-resizer:hover::before,
.shell-sidebar-resizer:focus-visible::before,
.shell-sidebar-resizer.resizing::before {
  background: rgb(var(--v-theme-primary));
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
  gap: 10px;
  flex: 0 0 var(--cx-window-bar-height);
  min-height: var(--cx-window-bar-height);
  padding: 0 16px;
  border-bottom: 1px solid var(--cx-border);
  background: rgb(var(--v-theme-background));
  app-region: no-drag;
  -webkit-app-region: no-drag;
  user-select: none;
  transition: padding-left 0.16s ease;
}
.shell-header-icon {
  font-size: 18px;
  color: rgb(var(--v-theme-on-surface));
  app-region: drag;
  -webkit-app-region: drag;
}
.shell-header-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 600;
  app-region: drag;
  -webkit-app-region: drag;
}
.shell-header-more { margin-left: 0; -webkit-app-region: no-drag; }
.shell-header-more .mdi { font-size: 17px; }
.shell-header-drag-tail {
  align-self: stretch;
  flex: 1 1 auto;
  app-region: drag;
  -webkit-app-region: drag;
}
/* Collapsed sidebar: the mac title-bar controls (traffic lights + toggle button, 112px wide)
 * float over the content column, and elsewhere the corner handle does — route headers must
 * keep their interactive content clear of that corner strip. */
.cx-shell :deep(.cx-topbar),
.cx-shell :deep(.flow-toolbar) {
  transition: padding-left 0.2s ease-out;
}
.cx-shell.mac-titlebar.sidebar-collapsed :deep(.shell-header),
.cx-shell.mac-titlebar.sidebar-collapsed :deep(.cx-topbar),
.cx-shell.mac-titlebar.sidebar-collapsed :deep(.flow-toolbar) {
  padding-left: 116px;
}
.cx-shell.sidebar-collapsed:not(.mac-titlebar) :deep(.shell-header),
.cx-shell.sidebar-collapsed:not(.mac-titlebar) :deep(.cx-topbar),
.cx-shell.sidebar-collapsed:not(.mac-titlebar) :deep(.flow-toolbar) {
  padding-left: 52px;
}
/* Centered pages have no header bar: drop their title below the corner strip instead. */
.cx-shell.sidebar-collapsed :deep(.cx-page) {
  padding-top: 60px;
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
