import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import { openNotificationStream, type NotificationStreamHandle } from '@/api/notificationStream'
import type { AppNotification } from '@/api/types'
import { i18n } from '@/i18n'

/** One transient toast surface entry; `uid` disambiguates duplicates of the same row. */
export interface NotificationToast {
  uid: number
  notification: AppNotification
}

/**
 * Host-side unified notifications — the single source of truth for every surface:
 * live toasts while the app is visible, native OS notifications through the
 * Electron preload when it is not, and the persisted center (badge + panel).
 *
 * One SSE stream (ticket-authenticated, self-healing) feeds every connected shell;
 * history loads over REST and dedupes against live events by `id`. Producers
 * (the plugin `notify` host bridge) POST /api/notifications and receive the row
 * back through this same stream — one write path, every surface consistent.
 */
export const useNotificationsStore = defineStore('notifications', () => {
  const items = ref<AppNotification[]>([])
  const unreadCount = ref(0)
  const connected = ref(false)
  const toasts = ref<NotificationToast[]>([])
  const error = ref<string | null>(null)

  let stream: NotificationStreamHandle | null = null
  let toastUid = 0
  const TOAST_TTL_MS = 6_000
  const MAX_TOASTS = 3
  const toastTimers = new Map<number, number>()

  const badged = computed(() => unreadCount.value > 0)

  /**
   * Title to display: known backend sources carry an i18n key so the shell
   * localizes them regardless of the locale that produced them; everything
   * else (plugin notify, host) displays the stored title — producers on this
   * side of the bridge already localized it at creation time.
   */
  function displayTitle(n: AppNotification): string {
    if (n.source === 'agent') {
      const key = n.level === 'error' ? 'notifications.agentFailed' : 'notifications.agentCompleted'
      return i18n.global.t(key)
    }
    return n.title
  }

  /** Load history + badge, then keep them current over the live stream. Idempotent. */
  function init() {
    if (stream) return
    void refresh()
    stream = openNotificationStream({
      onNotification: receive,
      onOpen: () => {
        connected.value = true
        // Events emitted while the socket was down were never delivered —
        // refetch to close the gap.
        void refresh()
      },
    })
  }

  function shutdown() {
    stream?.close()
    stream = null
    connected.value = false
  }

  async function refresh(): Promise<boolean> {
    try {
      const [list, count] = await Promise.all([
        api.listNotifications(50),
        api.unreadNotificationCount(),
      ])
      items.value = list
      unreadCount.value = count
      error.value = null
      return true
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load notifications'
      return false
    }
  }

  /** One live (or just-created) notification: history, badge, and the right surface. */
  function receive(n: AppNotification) {
    if (items.value.some((existing) => existing.id === n.id)) return
    items.value = [n, ...items.value].slice(0, 50)
    if (!n.read) unreadCount.value += 1
    surface(n)
  }

  /** Visible app → in-app toast; hidden desktop shell → native OS notification. */
  function surface(n: AppNotification) {
    if (typeof document !== 'undefined' && document.visibilityState === 'visible') {
      showToast(n)
      return
    }
    window.fengyu?.showNotification?.({ title: displayTitle(n), body: n.body })
  }

  function showToast(n: AppNotification) {
    const uid = ++toastUid
    toasts.value.push({ uid, notification: n })
    while (toasts.value.length > MAX_TOASTS) dismissToast(toasts.value[0]!.uid)
    toastTimers.set(uid, window.setTimeout(() => dismissToast(uid), TOAST_TTL_MS))
  }

  function dismissToast(uid: number) {
    const timer = toastTimers.get(uid)
    if (timer !== undefined) {
      window.clearTimeout(timer)
      toastTimers.delete(uid)
    }
    toasts.value = toasts.value.filter((toast) => toast.uid !== uid)
  }

  function dismissToastsFor(notificationId: number) {
    for (const toast of toasts.value) {
      if (toast.notification.id === notificationId) dismissToast(toast.uid)
    }
  }

  /** Optimistically acknowledge one; rolls back on failure. */
  async function markRead(id: number): Promise<boolean> {
    const target = items.value.find((n) => n.id === id)
    if (!target || target.read) return true
    const prevReadAt = target.readAt
    target.read = true
    target.readAt = new Date().toISOString()
    unreadCount.value = Math.max(0, unreadCount.value - 1)
    dismissToastsFor(id)
    try {
      await api.markNotificationRead(id)
      return true
    } catch (e) {
      target.read = false
      target.readAt = prevReadAt
      unreadCount.value += 1
      error.value = e instanceof Error ? e.message : 'Failed to mark notification read'
      return false
    }
  }

  async function markAllRead(): Promise<boolean> {
    const unread = items.value.filter((n) => !n.read)
    if (!unread.length) return true
    unread.forEach((n) => {
      n.read = true
      n.readAt = new Date().toISOString()
    })
    unreadCount.value = 0
    try {
      await api.markAllNotificationsRead()
      return true
    } catch (e) {
      unread.forEach((n) => {
        n.read = false
        n.readAt = null
      })
      unreadCount.value = unread.length
      error.value = e instanceof Error ? e.message : 'Failed to mark notifications read'
      return false
    }
  }

  /** Remove from the center (and any live toast). */
  async function remove(id: number): Promise<boolean> {
    const wasUnread = items.value.some((n) => n.id === id && !n.read)
    const prev = items.value
    items.value = items.value.filter((n) => n.id !== id)
    if (wasUnread) unreadCount.value = Math.max(0, unreadCount.value - 1)
    dismissToastsFor(id)
    try {
      await api.deleteNotification(id)
      return true
    } catch (e) {
      items.value = prev
      if (wasUnread) unreadCount.value += 1
      error.value = e instanceof Error ? e.message : 'Failed to delete notification'
      return false
    }
  }

  /**
   * The plugin `notify` host bridge (PluginView). Returns false when the POST
   * fails so the caller can fall back to the iframe-internal notification
   * center — mirroring the SDK contract for an unhandled host method.
   */
  async function createPluginNotification(pluginId: string, pluginName: string, message: string): Promise<boolean> {
    try {
      await api.createNotification({
        source: `plugin:${pluginId}`,
        level: 'info',
        title: pluginName,
        body: message,
      })
      return true
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to create notification'
      return false
    }
  }

  return {
    items, unreadCount, connected, toasts, error, badged,
    init, shutdown, refresh, receive,
    displayTitle, dismissToast, dismissToastsFor,
    markRead, markAllRead, remove, createPluginNotification,
  }
})
