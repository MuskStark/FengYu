import { backendUrl } from './config'
import { api } from './client'
import type { AppNotification } from './types'

export interface NotificationStreamHandle {
  close: () => void
}

export interface NotificationStreamCallbacks {
  /** One live `notification` event (already-persisted row, newest). */
  onNotification: (notification: AppNotification) => void
  /** The transport (re)connected — refetch history here to close any gap. */
  onOpen?: () => void
}

/**
 * Open an EventSource on /api/notifications/stream and dispatch each named
 * `notification` event to the callback.
 *
 * Auth follows the ai/agent stream pattern: EventSource cannot set headers, so
 * each connection redeems a one-time `?ticket=` minted by the header-authenticated
 * POST /api/notifications/stream-ticket — the full token never rides in a URL.
 *
 * Reconnection is MANAGED HERE (a ticket is single-use, so the browser's built-in
 * retry would replay a spent ticket and die on 401). Unlike the single-shot AI
 * stream this channel backs the shell's whole notification surface, so retries
 * are unbounded with capped backoff (1s → 15s) instead of giving up: the stream
 * heals after backend restarts and machine sleep. Each successful reconnect
 * fires onOpen so the store can refetch history (SSE events emitted while the
 * socket was down were never delivered — the backend broadcasts live only).
 */
export function openNotificationStream(cb: NotificationStreamCallbacks): NotificationStreamHandle {
  let es: EventSource | null = null
  let closed = false
  let retryDelayMs = 1_000
  const MAX_RETRY_DELAY_MS = 15_000

  const connect = async () => {
    let ticket: string
    try {
      ticket = await api.issueStreamTicket('notifications')
    } catch {
      if (!closed) scheduleReconnect()
      return
    }
    if (closed) return
    const url = backendUrl(`/api/notifications/stream?ticket=${encodeURIComponent(ticket)}`)
    es = new EventSource(url)

    es.addEventListener('open', () => {
      retryDelayMs = 1_000
      cb.onOpen?.()
    })

    es.addEventListener('notification', (ev) => {
      try {
        const data = JSON.parse((ev as MessageEvent).data) as AppNotification
        if (data && typeof data.id === 'number') cb.onNotification(data)
      } catch {
        // Malformed frame — drop it rather than kill the stream.
      }
    })

    es.addEventListener('error', () => {
      // The backend never sends a named terminal error on this stream, so every
      // native error is a transport drop — take over from the browser's retry.
      if (closed) return
      es?.close()
      es = null
      scheduleReconnect()
    })
  }

  const scheduleReconnect = () => {
    window.setTimeout(() => {
      if (!closed) void connect()
    }, retryDelayMs)
    retryDelayMs = Math.min(retryDelayMs * 2, MAX_RETRY_DELAY_MS)
  }

  void connect()

  return {
    close: () => {
      closed = true
      es?.close()
      es = null
    },
  }
}
