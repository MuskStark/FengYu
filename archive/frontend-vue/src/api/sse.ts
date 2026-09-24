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
 * This is a single-shot stream with NO reconnection: the backend consumes the
 * stream entry on first connect and cancels the generation the moment the
 * transport drops, so a reconnect with the same streamId can only earn an
 * "unknown stream" error. A native connection drop is therefore terminal —
 * the error bubbles up and the user re-sends. (The notification and agent
 * streams, which do resume, keep their own reconnect logic — see
 * notificationStream.ts.)
 */
export function openAiStream(streamId: string, cb: SseCallbacks): SseHandle {
  let es: EventSource | null = null
  let closed = false

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
        // "Unknown or expired streamId" is the normal outcome of a dropped
        // transport (the backend already cancelled the generation), not a
        // separate failure — surface the honest "send again" wording for it.
        if (d.code === 'unknown_stream' || String(d.message).includes('Unknown or expired streamId')) {
          fail(i18n.global.t('agent.streamEnded'))
          return
        }
        fail(d.message)
        return
      }
      // Native drop: the generation was cancelled server-side, so close and fail
      // immediately — the browser's built-in retry would also be wrong here (it
      // replays the single-use ticket).
      fail(i18n.global.t('agent.streamLost'))
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
