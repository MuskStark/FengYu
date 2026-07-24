import { backendUrl, getToken } from './config'

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
 * EventSource cannot set headers, so the token is passed as a query param
 * when non-empty (per the backend contract).
 */
export function openAiStream(streamId: string, cb: SseCallbacks): SseHandle {
  const token = getToken()
  const params = new URLSearchParams({ streamId })
  if (token) params.set('token', token)
  const url = backendUrl(`/api/ai/stream?${params.toString()}`)

  const es = new EventSource(url)
  let closed = false

  // Diagnostic: some webviews silently drop SSE connections that never receive data;
  // log open/error/readyState so we can confirm the heartbeat fix works.
  es.onopen = () => {
    console.debug('[sse] connected', { readyState: es.readyState, url })
  }
  es.onerror = () => {
    console.debug('[sse] native error', { readyState: es.readyState, url })
  }
  const close = () => {
    if (!closed) {
      closed = true
      es.close()
    }
  }

  const parse = <T>(ev: MessageEvent): T | null => {
    try {
      return JSON.parse(ev.data) as T
    } catch {
      return null
    }
  }

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
    if (cb.onDone) cb.onDone(d ?? { text: '' })
    close()
  })

  es.addEventListener('error', (ev) => {
    // Named "error" event from the backend carries a JSON message; the native
    // EventSource error (connection drop) has no parseable data.
    const d = parse<{ message: string }>(ev as MessageEvent)
    if (cb.onError) cb.onError(d?.message ?? 'Connection to AI stream lost')
    close()
  })

  return { close }
}
