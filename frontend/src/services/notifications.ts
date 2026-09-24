/**
 * Notifications domain — host-side unified notification history plus the
 * live push stream (unbounded reconnect; onOpen signals "refetch history").
 */
import type { AppNotification, CreateNotificationPayload } from './types'
import { http } from './impl/http'
import { openNotificationStream, type NotificationStreamHandlers, type StreamHandle } from './impl/streams'

export type { NotificationStreamHandlers, StreamHandle }

export interface NotificationService {
  /** Newest-first notification history (optionally unread only). */
  list(limit?: number, unreadOnly?: boolean): Promise<AppNotification[]>
  /** Create + broadcast one notification (persisted AND pushed to every live shell). */
  create(payload: CreateNotificationPayload): Promise<AppNotification>
  unreadCount(): Promise<number>
  markRead(id: number): Promise<AppNotification>
  markAllRead(): Promise<{ marked: number }>
  delete(id: number): Promise<void>
  subscribe(cb: NotificationStreamHandlers): StreamHandle
}

export const notificationService: NotificationService = {
  list: (limit = 50, unreadOnly = false) =>
    http.get<AppNotification[]>('/api/notifications', { params: { limit, unreadOnly } }).then((r) => r.data),
  create: (payload) => http.post<AppNotification>('/api/notifications', payload).then((r) => r.data),
  unreadCount: () => http.get<{ count: number }>('/api/notifications/unread-count').then((r) => r.data.count),
  markRead: (id) =>
    http.post<AppNotification>(`/api/notifications/${encodeURIComponent(id)}/read`).then((r) => r.data),
  markAllRead: () => http.post<{ marked: number }>('/api/notifications/read-all').then((r) => r.data),
  delete: (id) => http.delete(`/api/notifications/${encodeURIComponent(id)}`),
  subscribe: (cb) => openNotificationStream(cb),
}
