import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import type { AppNotification } from '@/api/types'

// Mock the API client so store tests stay pure unit tests.
const apiMocks = vi.hoisted(() => ({
  listNotifications: vi.fn(),
  unreadNotificationCount: vi.fn(),
  createNotification: vi.fn(),
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn(),
  deleteNotification: vi.fn(),
  issueStreamTicket: vi.fn(),
}))
vi.mock('@/api/client', () => ({
  api: {
    listNotifications: apiMocks.listNotifications,
    unreadNotificationCount: apiMocks.unreadNotificationCount,
    createNotification: apiMocks.createNotification,
    markNotificationRead: apiMocks.markNotificationRead,
    markAllNotificationsRead: apiMocks.markAllNotificationsRead,
    deleteNotification: apiMocks.deleteNotification,
    issueStreamTicket: apiMocks.issueStreamTicket,
  },
}))

// Capture the stream callbacks the store subscribes with, without real EventSources.
let streamCallbacks: { onNotification: (n: AppNotification) => void; onOpen?: () => void } | null = null
vi.mock('@/api/notificationStream', () => ({
  openNotificationStream: vi.fn((cb: typeof streamCallbacks) => {
    streamCallbacks = cb
    return { close: vi.fn() }
  }),
}))

import { useNotificationsStore } from './notifications'
import { i18n } from '@/i18n'

function notification(partial: Partial<AppNotification> & { id: number }): AppNotification {
  return {
    source: 'host',
    level: 'info',
    title: `title ${partial.id}`,
    body: '',
    link: null,
    read: false,
    createdAt: '2026-08-19T10:00:00',
    readAt: null,
    ...partial,
  }
}

describe('notifications store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    streamCallbacks = null
    apiMocks.listNotifications.mockResolvedValue([])
    apiMocks.unreadNotificationCount.mockResolvedValue(0)
    // Node test env has no DOM: provide the globals the store touches.
    vi.stubGlobal('document', { visibilityState: 'visible' })
    vi.stubGlobal('window', {
      setTimeout: (fn: () => void, ms: number) => setTimeout(fn, ms),
      clearTimeout: (id: ReturnType<typeof setTimeout>) => clearTimeout(id),
    })
  })

  it('init loads history + badge once and opens exactly one stream', async () => {
    apiMocks.listNotifications.mockResolvedValue([notification({ id: 1, read: true })])
    apiMocks.unreadNotificationCount.mockResolvedValue(3)
    const store = useNotificationsStore()

    store.init()
    store.init()
    await vi.waitFor(() => expect(store.items).toHaveLength(1))

    expect(apiMocks.listNotifications).toHaveBeenCalledTimes(1)
    expect(store.unreadCount).toBe(3)
  })

  it('live events dedupe by id and bump the unread badge', () => {
    const store = useNotificationsStore()
    const n = notification({ id: 5 })

    store.receive(n)
    store.receive(n)

    expect(store.items).toHaveLength(1)
    expect(store.unreadCount).toBe(1)
  })

  it('shows an in-app toast while the app is visible', () => {
    const store = useNotificationsStore()

    store.receive(notification({ id: 6 }))

    expect(store.toasts).toHaveLength(1)
    expect(store.toasts[0]!.notification.id).toBe(6)
  })

  it('routes to a native desktop notification while the app is hidden', () => {
    vi.stubGlobal('document', { visibilityState: 'hidden' })
    const showNotification = vi.fn().mockResolvedValue(true)
    vi.stubGlobal('window', { fengyu: { showNotification } })
    const store = useNotificationsStore()

    store.receive(notification({ id: 7, source: 'agent', level: 'success', title: 'Agent run completed' }))

    expect(store.toasts).toHaveLength(0)
    expect(showNotification).toHaveBeenCalledWith({
      title: 'Agent run completed',
      body: '',
    })
  })

  it('markRead is optimistic with rollback on failure', async () => {
    apiMocks.markNotificationRead.mockRejectedValue(new Error('offline'))
    const store = useNotificationsStore()
    store.receive(notification({ id: 8 }))

    const ok = await store.markRead(8)

    expect(ok).toBe(false)
    expect(store.items[0]!.read).toBe(false)
    expect(store.unreadCount).toBe(1)
    expect(store.error).toContain('offline')
  })

  it('markAllRead flips everything and zeroes the badge', async () => {
    apiMocks.markAllNotificationsRead.mockResolvedValue({ marked: 2 })
    const store = useNotificationsStore()
    store.receive(notification({ id: 9 }))
    store.receive(notification({ id: 10 }))

    const ok = await store.markAllRead()

    expect(ok).toBe(true)
    expect(store.unreadCount).toBe(0)
    expect(store.items.every((n) => n.read)).toBe(true)
  })

  it('remove drops the row, its toasts, and the badge entry', async () => {
    apiMocks.deleteNotification.mockResolvedValue(undefined)
    const store = useNotificationsStore()
    store.receive(notification({ id: 11 }))

    const ok = await store.remove(11)

    expect(ok).toBe(true)
    expect(store.items).toHaveLength(0)
    expect(store.toasts).toHaveLength(0)
    expect(store.unreadCount).toBe(0)
  })

  it('localizes known agent titles and passes stored titles through', () => {
    const store = useNotificationsStore()
    const prevLocale = i18n.global.locale.value
    i18n.global.locale.value = 'zh'
    try {
      expect(store.displayTitle(notification({
        id: 12, source: 'agent', level: 'error', title: 'Agent run failed',
      }))).toBe('Agent 运行失败')
    } finally {
      i18n.global.locale.value = prevLocale
    }
    expect(store.displayTitle(notification({
      id: 13, source: 'plugin:demo', title: 'Demo plugin',
    }))).toBe('Demo plugin')
  })

  it('createPluginNotification reports failure so the bridge falls back', async () => {
    apiMocks.createNotification.mockRejectedValue(new Error('backend down'))
    const store = useNotificationsStore()

    const ok = await store.createPluginNotification('demo', 'Demo', 'hello')

    expect(ok).toBe(false)
    expect(apiMocks.createNotification).toHaveBeenCalledWith({
      source: 'plugin:demo',
      level: 'info',
      title: 'Demo',
      body: 'hello',
    })
  })

  it('reconnect refetches history to close the gap', async () => {
    const store = useNotificationsStore()
    store.init()
    await vi.waitFor(() => expect(streamCallbacks).not.toBeNull())

    streamCallbacks!.onOpen?.()

    expect(apiMocks.listNotifications.mock.calls.length).toBeGreaterThanOrEqual(2)
  })
})
