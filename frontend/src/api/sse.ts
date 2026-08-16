import { backendUrl } from './config'
import { api } from './client'
import { i18n } from '@/i18n'

export interface AiDonePayload {
  text: string
  tokens?: number
  tps?: number
}

export interface SseCallbacks {
  onToken?: (text: string) => void
  onThinking?: (text: string) => void
  onTool?: (payload: Record<string, unknown>) => void
  onDone?: (payload: AiDonePayload) => void
  onError?: (message: string) => void
}

export interface SseHandle {
  close: () => void
}

/**
 * Open an EventSource on /api/ai/stream and dispatch the backend's named
 * events (token / thinking / tool / done / error) to the callbacks.
 *
 * Auth: EventSource cannot set headers, so each connection redeems a one-time
 * `?ticket=` minted by the header-authenticated POST /api/ai/stream-ticket —
 * the full token never rides in a URL that proxy/access logs can capture.
 *
 * Reconnection is MANAGED HERE instead of left to the EventSource: a ticket is
 * single-use, so the browser's built-in retry would replay a spent ticket and
 * die on 401. On a native connection drop we close, mint a fresh ticket, and
 * reconnect — giving up after RETRY_LIMIT consecutive failures.
 */
export function openAiStream(streamId: string, cb: SseCallbacks): SseHandle {
  let es: EventSource | null = null
  let closed = false
  let retries = 0
  const RETRY_LIMIT = 5
  const RETRY_DELAY_MS = 800

  const parse = <T>(ev: MessageEvent): T | null => {
    try {
      return JSON.parse(ev.data) as T
    } catch {
      return null
    }
  }

  const fail = (message: string) => {
    if (closed) return
    closed = true
    es?.close()
    cb.onError?.(message)
  }

  const connect = async () => {
    let ticket: string
    try {
      ticket = await api.issueStreamTicket('ai')
    } catch {
      fail(i18n.global.t('agent.streamTicketFailed'))
      return
    }
    if (closed) return
    const url = backendUrl(`/api/ai/stream?streamId=${encodeURIComponent(streamId)}&ticket=${encodeURIComponent(ticket)}`)
    es = new EventSource(url)

    // A successful (re)connect clears the failure streak — the limit below counts
    // consecutive drops, not lifetime failures.
    es.addEventListener('open', () => {
      retries = 0
    })

    es.addEventListener('token', (ev) => {
      const d = parse<{ text: string }>(ev as MessageEvent)
      if (d && cb.onToken) cb.onToken(d.text)
    })

    es.addEventListener('thinking', (ev) => {
      const d = parse<{ text: string }>(ev as MessageEvent)
      if (d && cb.onThinking) cb.onThinking(d.text)
    })

    es.addEventListener('tool', (ev) => {
      const d = parse<Record<string, unknown>>(ev as MessageEvent)
      if (d && cb.onTool) cb.onTool(d)
    })

    es.addEventListener('done', (ev) => {
      const d = parse<AiDonePayload>(ev as MessageEvent)
      closed = true
      es?.close()
      if (cb.onDone) cb.onDone(d ?? { text: '' })
    })

    es.addEventListener('error', (ev) => {
      // Named "error" event from the backend carries a JSON message; the native
      // EventSource error (connection drop) has no parseable data.
      const d = parse<{ message: string; code?: string }>(ev as MessageEvent)
      if (d?.message) {
        // The backend consumed the stream entry on FIRST connect and cancels the
        // generation when the transport drops — reconnecting with the same streamId
        // can only earn this error again. AI chat is single-shot (no resume), so fail
        // fast with an honest "send again" message; `fail` sets `closed`, so the
        // network-drop retry path below can never re-arm after this.
        if (d.code === 'unknown_stream' || String(d.message).includes('Unknown or expired streamId')) {
          fail(i18n.global.t('agent.streamEnded'))
          return
        }
        fail(d.message)
        return
      }
      // Native drop: the built-in retry would replay the spent ticket (401), so
      // take over — close, mint a fresh ticket, reconnect, up to RETRY_LIMIT.
      if (closed) return
      es?.close()
      es = null
      retries += 1
      if (retries >= RETRY_LIMIT) {
        fail(i18n.global.t('agent.streamLost'))
        return
      }
      window.setTimeout(() => {
        if (!closed) void connect()
      }, RETRY_DELAY_MS)
    })
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
