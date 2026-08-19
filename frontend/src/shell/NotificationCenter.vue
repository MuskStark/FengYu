<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useNotificationsStore } from '@/stores/notifications'
import type { AppNotification } from '@/api/types'

const notifications = useNotificationsStore()
const router = useRouter()
const { t, locale } = useI18n()

const open = ref(false)
const area = ref<HTMLElement | null>(null)

const LEVEL_ICONS: Record<string, string> = {
  info: 'mdi-information-outline',
  success: 'mdi-check-circle-outline',
  warning: 'mdi-alert-outline',
  error: 'mdi-close-circle-outline',
}

function levelIcon(level: string): string {
  return LEVEL_ICONS[level] ?? LEVEL_ICONS.info!
}

/** Compact relative timestamp, matching the shell's density. */
function relativeTime(iso: string): string {
  const then = new Date(iso).getTime()
  if (!Number.isFinite(then)) return ''
  const minutes = Math.round((Date.now() - then) / 60_000)
  if (minutes < 1) return t('notifications.justNow')
  if (minutes < 60) return t('notifications.minutesAgo', { n: minutes })
  const hours = Math.round(minutes / 60)
  if (hours < 24) return t('notifications.hoursAgo', { n: hours })
  return new Intl.DateTimeFormat(locale.value, { month: 'short', day: 'numeric' }).format(new Date(then))
}

function toggle() {
  open.value = !open.value
  if (open.value) void notifications.refresh()
}

function activate(n: AppNotification) {
  void notifications.markRead(n.id)
  if (n.link) {
    open.value = false
    void router.push(n.link)
  }
}

function closeOnOutsideClick(event: PointerEvent) {
  if (!area.value?.contains(event.target as Node)) open.value = false
}

function closeOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') open.value = false
}

onMounted(() => {
  document.addEventListener('pointerdown', closeOnOutsideClick)
  document.addEventListener('keydown', closeOnEscape)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeOnOutsideClick)
  document.removeEventListener('keydown', closeOnEscape)
})
</script>

<template>
  <div ref="area" class="notification-center">
    <button
      class="cx-iconbtn cx-iconbtn--sm notification-bell"
      :class="{ active: open }"
      :title="t('notifications.title')"
      :aria-label="t('notifications.title')"
      aria-haspopup="menu"
      :aria-expanded="open"
      @click="toggle"
    >
      <i class="mdi" :class="notifications.badged ? 'mdi-bell-badge' : 'mdi-bell-outline'" />
    </button>

    <div v-if="open" class="notification-panel" role="menu" :aria-label="t('notifications.title')">
      <div class="notification-panel-header">
        <span class="notification-panel-title">{{ t('notifications.title') }}</span>
        <button
          v-if="notifications.unreadCount > 0"
          class="cx-btn cx-btn--text cx-btn--sm"
          @click="notifications.markAllRead()"
        >{{ t('notifications.markAllRead') }}</button>
      </div>

      <div v-if="!notifications.items.length" class="notification-empty">
        <i class="mdi mdi-bell-off-outline" aria-hidden="true" />
        <span>{{ t('notifications.empty') }}</span>
      </div>

      <div v-else class="notification-list">
        <div
          v-for="n in notifications.items"
          :key="n.id"
          class="notification-item"
          :class="{ unread: !n.read }"
          role="menuitem"
          tabindex="0"
          @click="activate(n)"
          @keydown.enter="activate(n)"
        >
          <i class="mdi notification-item-icon" :class="levelIcon(n.level)" aria-hidden="true" />
          <div class="notification-item-copy">
            <div class="notification-item-title">
              <span>{{ notifications.displayTitle(n) }}</span>
              <span v-if="!n.read" class="notification-unread-dot" aria-hidden="true" />
            </div>
            <div v-if="n.body" class="notification-item-body">{{ n.body }}</div>
            <div class="notification-item-time">{{ relativeTime(n.createdAt) }}</div>
          </div>
          <button
            class="cx-iconbtn cx-iconbtn--sm notification-item-remove"
            :aria-label="t('notifications.delete')"
            @click.stop="notifications.remove(n.id)"
          ><i class="mdi mdi-close" /></button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.notification-center { position: relative; }
.notification-bell .mdi { font-size: 20px; }
.notification-bell.active { background: rgb(var(--v-theme-surface-container-highest)); }

.notification-panel {
  position: absolute;
  z-index: 10;
  left: 7px;
  right: 7px;
  bottom: 48px;
  display: flex;
  flex-direction: column;
  min-height: 120px;
  max-height: min(430px, calc(100vh - 140px));
  padding: 6px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 11px;
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 12px 28px rgba(0, 0, 0, .2);
}
.notification-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 8px 8px;
  border-bottom: 1px solid rgb(var(--v-theme-outline-variant));
}
.notification-panel-title { font-weight: 650; font-size: 13px; }

.notification-empty {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 22px 10px;
  color: rgb(var(--v-theme-secondary));
  font-size: 12.5px;
}
.notification-empty .mdi { font-size: 26px; }

.notification-list { flex: 1 1 auto; min-height: 0; overflow-y: auto; }
.notification-item {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 9px 8px;
  border-radius: 9px;
  cursor: pointer;
}
.notification-item:hover { background: rgb(var(--v-theme-surface-container-high)); }
.notification-item-icon { font-size: 19px; margin-top: 1px; flex: 0 0 auto; }
.notification-item.unread { background: rgb(var(--v-theme-surface-container-low)); }
.notification-item.unread .notification-item-title { font-weight: 650; }
.notification-item-icon.mdi-information-outline { color: rgb(var(--v-theme-primary)); }
.notification-item-icon.mdi-check-circle-outline { color: rgb(var(--v-theme-tertiary)); }
.notification-item-icon.mdi-alert-outline { color: rgb(var(--v-theme-warning)); }
.notification-item-icon.mdi-close-circle-outline { color: rgb(var(--v-theme-error)); }

.notification-item-copy { flex: 1 1 auto; min-width: 0; }
.notification-item-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notification-item-title > span:first-child { overflow: hidden; text-overflow: ellipsis; }
.notification-unread-dot {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: 50%;
  background: rgb(var(--v-theme-primary));
}
.notification-item-body {
  margin-top: 2px;
  font-size: 12px;
  color: rgb(var(--v-theme-secondary));
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}
.notification-item-time { margin-top: 3px; font-size: 11px; color: rgb(var(--v-theme-secondary)); }
.notification-item-remove { margin: -3px -4px 0 0; flex: 0 0 auto; opacity: 0; }
.notification-item:hover .notification-item-remove,
.notification-item:focus-within .notification-item-remove { opacity: 1; }
</style>
