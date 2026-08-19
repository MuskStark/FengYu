<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useNotificationsStore } from '@/stores/notifications'
import type { AppNotification } from '@/api/types'

const notifications = useNotificationsStore()
const router = useRouter()
const { t } = useI18n()

const LEVEL_ICONS: Record<string, string> = {
  info: 'mdi-information-outline',
  success: 'mdi-check-circle-outline',
  warning: 'mdi-alert-outline',
  error: 'mdi-close-circle-outline',
}

function levelIcon(level: string): string {
  return LEVEL_ICONS[level] ?? LEVEL_ICONS.info!
}

function activate(n: AppNotification) {
  void notifications.markRead(n.id)
  notifications.dismissToastsFor(n.id)
  if (n.link) void router.push(n.link)
}
</script>

<template>
  <div class="notification-toasts" aria-live="polite" role="status">
    <div
      v-for="toast in notifications.toasts"
      :key="toast.uid"
      class="notification-toast"
      :class="`notification-toast--${toast.notification.level}`"
      tabindex="0"
      role="button"
      @click="activate(toast.notification)"
      @keydown.enter="activate(toast.notification)"
    >
      <i class="mdi notification-toast-icon" :class="levelIcon(toast.notification.level)" aria-hidden="true" />
      <div class="notification-toast-copy">
        <div class="notification-toast-title">{{ notifications.displayTitle(toast.notification) }}</div>
        <div v-if="toast.notification.body" class="notification-toast-body">{{ toast.notification.body }}</div>
      </div>
      <button
        class="cx-iconbtn cx-iconbtn--sm notification-toast-close"
        :aria-label="t('notifications.dismiss')"
        @click.stop="notifications.dismissToast(toast.uid)"
      ><i class="mdi mdi-close" /></button>
    </div>
  </div>
</template>

<style scoped>
.notification-toasts {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: 80;
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: min(360px, calc(100vw - 40px));
  pointer-events: none;
}
.notification-toast {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  padding: 12px 12px 12px 14px;
  border: 1px solid var(--cx-border);
  border-radius: 13px;
  background: rgb(var(--v-theme-surface-container-high));
  box-shadow: 0 10px 26px rgba(0, 0, 0, .28);
  color: rgb(var(--v-theme-on-surface));
  font: inherit;
  text-align: left;
  cursor: pointer;
  pointer-events: auto;
  animation: notification-toast-in 180ms ease-out;
}
@keyframes notification-toast-in {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: none; }
}
.notification-toast-icon { font-size: 21px; margin-top: 1px; flex: 0 0 auto; }
.notification-toast--info .notification-toast-icon { color: rgb(var(--v-theme-primary)); }
/* The MD3 theme has no success key — tertiary is its green accent. */
.notification-toast--success .notification-toast-icon { color: rgb(var(--v-theme-tertiary)); }
.notification-toast--warning .notification-toast-icon { color: rgb(var(--v-theme-warning)); }
.notification-toast--error .notification-toast-icon { color: rgb(var(--v-theme-error)); }
.notification-toast-copy { flex: 1 1 auto; min-width: 0; }
.notification-toast-title {
  font-weight: 600;
  font-size: 13.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notification-toast-body {
  margin-top: 2px;
  font-size: 12.5px;
  color: rgb(var(--v-theme-secondary));
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}
.notification-toast-close { margin: -3px -4px 0 0; flex: 0 0 auto; }
</style>
