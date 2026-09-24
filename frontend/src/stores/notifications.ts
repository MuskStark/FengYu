import { create } from 'zustand'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import { i18n } from '@/i18n'
import { useToastStore } from './toasts'
import type { AppNotification } from '@/services/types'

/**
 * Host-side unified notifications — React port of the Vue notifications store: the single
 * source of truth for every surface. Live toasts while the app is visible (rendered through
 * the shared toast store with a `notificationId` tag), native OS notifications through the
 * platform layer when it is not, and the persisted center (badge + panel).
 *
 * One SSE stream (unbounded reconnect; onOpen refetches history to close the gap) feeds the
 * shell; history loads over REST and dedupes against live events by `id`. Producers (the
 * plugin `notify` host bridge) POST /api/notifications and receive the row back through this
 * same stream — one write path, every surface consistent. AppShell owns init() so the stream
 * survives the sidebar unmounting on the settings route.
 */

interface NotificationsState {
  items: AppNotification[]
  unreadCount: number
  connected: boolean
  error: string | null
  init: () => void
  shutdown: () => void
  refresh: () => Promise<boolean>
  receive: (n: AppNotification) => void
  markRead: (id: number) => Promise<boolean>
  markAllRead: () => Promise<boolean>
  remove: (id: number) => Promise<boolean>
}

let stream: { close: () => void } | null = null

/**
 * Title to display: known backend sources carry an i18n key so the shell localizes them
 * regardless of the locale that produced them; everything else (plugin notify, host)
 * displays the stored title — producers on this side of the bridge already localized it
 * at creation time.
 */
export function displayTitle(n: AppNotification): string {
  if (n.source === 'agent') {
    const key = n.level === 'error' ? 'notifications.agentFailed' : 'notifications.agentCompleted'
    return i18n.global.t(key)
  }
  return n.title
}

export const useNotificationsStore = create<NotificationsState>((set, get) => ({
  items: [],
  unreadCount: 0,
  connected: false,
  error: null,

  /** Load history + badge, then keep them current over the live stream. Idempotent. */
  init: () => {
    if (stream) return
    void get().refresh()
    stream = services.notifications.subscribe({
      // The SSE payload is a persisted AppNotification row; the service layer keeps the
      // handler type loose (raw parsed JSON), so the cast lives at this one boundary.
      onNotification: (n) => get().receive(n as unknown as AppNotification),
      onOpen: () => {
        set({ connected: true })
        // Events emitted while the socket was down were never delivered — refetch to close the gap.
        void get().refresh()
      },
    })
  },

  shutdown: () => {
    stream?.close()
    stream = null
    set({ connected: false })
  },

  refresh: async () => {
    try {
      const [list, count] = await Promise.all([
        services.notifications.list(50),
        services.notifications.unreadCount(),
      ])
      set({ items: list, unreadCount: count, error: null })
      return true
    } catch (e) {
      set({ error: e instanceof Error ? e.message : 'Failed to load notifications' })
      return false
    }
  },

  /** One live (or just-created) notification: history, badge, and the right surface. */
  receive: (n) => {
    const { items } = get()
    if (items.some((existing) => existing.id === n.id)) return
    set({ items: [n, ...items].slice(0, 50) })
    if (!n.read) set((state) => ({ unreadCount: state.unreadCount + 1 }))
    surface(n)
  },

  markRead: async (id) => {
    const state = get()
    const target = state.items.find((n) => n.id === id)
    if (!target || target.read) return true
    const prevReadAt = target.readAt
    set({
      items: state.items.map((n) =>
        n.id === id ? { ...n, read: true, readAt: new Date().toISOString() } : n),
      unreadCount: Math.max(0, state.unreadCount - 1),
    })
    useToastStore.getState().dismissForNotification(id)
    try {
      await services.notifications.markRead(id)
      return true
    } catch (e) {
      set((current) => ({
        items: current.items.map((n) => (n.id === id ? { ...n, read: false, readAt: prevReadAt } : n)),
        unreadCount: current.unreadCount + 1,
        error: e instanceof Error ? e.message : 'Failed to mark notification read',
      }))
      return false
    }
  },

  markAllRead: async () => {
    const state = get()
    const unread = state.items.filter((n) => !n.read)
    if (!unread.length) return true
    const now = new Date().toISOString()
    set({
      items: state.items.map((n) => (n.read ? n : { ...n, read: true, readAt: now })),
      unreadCount: 0,
    })
    try {
      await services.notifications.markAllRead()
      return true
    } catch (e) {
      set((current) => ({
        items: current.items.map((n) => (unread.some((u) => u.id === n.id) ? { ...n, read: false, readAt: null } : n)),
        unreadCount: unread.length,
        error: e instanceof Error ? e.message : 'Failed to mark notifications read',
      }))
      return false
    }
  },

  /** Remove from the center (and any live toast). */
  remove: async (id) => {
    const state = get()
    const wasUnread = state.items.some((n) => n.id === id && !n.read)
    const prev = state.items
    set({
      items: state.items.filter((n) => n.id !== id),
      unreadCount: wasUnread ? Math.max(0, state.unreadCount - 1) : state.unreadCount,
    })
    useToastStore.getState().dismissForNotification(id)
    try {
      await services.notifications.delete(id)
      return true
    } catch (e) {
      set((current) => ({
        items: prev,
        unreadCount: wasUnread ? current.unreadCount + 1 : current.unreadCount,
        error: e instanceof Error ? e.message : 'Failed to delete notification',
      }))
      return false
    }
  },
}))

/** Visible app → in-app toast; hidden desktop shell → native OS notification. */
function surface(n: AppNotification): void {
  if (typeof document !== 'undefined' && document.visibilityState === 'visible') {
    useToastStore.getState().push({
      level: n.level,
      title: displayTitle(n),
      body: n.body,
      link: n.link ? { label: '', to: n.link } : undefined,
      notificationId: n.id,
    })
    return
  }
  void getPlatform().showNotification({ title: displayTitle(n), body: n.body })
}

/**
 * The plugin `notify` host bridge (PluginPage). Returns false when the POST fails so the
 * caller can fall back to the iframe-internal notification center — mirroring the SDK
 * contract for an unhandled host method. The row comes back to this shell through the
 * live stream, not the POST response.
 */
export async function createPluginNotification(pluginId: string, pluginName: string, message: string): Promise<boolean> {
  try {
    await services.notifications.create({
      source: `plugin:${pluginId}`,
      level: 'info',
      title: pluginName,
      body: message,
    })
    return true
  } catch (e) {
    useNotificationsStore.setState({
      error: e instanceof Error ? e.message : 'Failed to create notification',
    })
    return false
  }
}
