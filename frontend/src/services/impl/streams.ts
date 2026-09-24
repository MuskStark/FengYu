/**
 * SSE transport engine for the three backend streams (ai / agent / notifications).
 *
 * All three share one auth pattern: EventSource cannot set headers, so each
 * connection redeems a one-time `?ticket=` minted by the header-authenticated
 * POST /api/{ai|agent|notifications}/stream-ticket — the full token never rides
 * in a URL that proxy/access logs can capture. Because a ticket is single-use,
 * reconnection is always MANAGED HERE, never by the browser's built-in retry
 * (it would replay a spent ticket and die on 401).
 *
 * Reconnection policies differ per stream and are part of the contract:
 * - chat: single-shot, no reconnect (the backend cancels the generation when
 *   the transport drops — a reconnect can only earn "unknown stream");
 * - agent run: bounded reconnect (5 × 800ms) with replay dedup via the
 *   backend's monotonic per-run `seq`;
 * - notifications: unbounded reconnect with capped backoff (1s → 15s) — the
 *   stream backs the shell's whole notification surface and must heal after
 *   backend restarts and machine sleep.
 *
 * This module is i18n-free: failures surface structured codes + the backend's
 * own message; callers map codes to user-facing copy.
 */
import { backendUrl } from '@/platform'
import { http } from './http'

export type StreamKind = 'ai' | 'agent' | 'notifications'

export interface StreamHandle {
  close: () => void
}

/** Mints the one-time ticket an SSE EventSource redeems as `?ticket=`. */
export async function issueStreamTicket(kind: StreamKind): Promise<string> {
  const { data } = await http.post<{ ticket: string; expiresAt: string }>(
    kind === 'ai' ? '/api/ai/stream-ticket'
      : kind === 'agent' ? '/api/agent/stream-ticket'
        : '/api/notifications/stream-ticket')
  return data.ticket
}

function parseEvent<T>(ev: Event): T | null {
  try {
    return JSON.parse((ev as MessageEvent).data) as T
  } catch {
    return null
  }
}

// ── Chat stream (single-shot) ────────────────────────────────────────────────

export interface ChatStreamError {
  /** 'ticket_failed' (mint), 'stream_lost' (native drop), 'stream_ended' (backend cancelled the generation), or a backend-provided code. */
  code?: string
  /** The backend's own message when it sent one. */
  message?: string
}

export interface ChatStreamHandlers {
  onToken?: (text: string) => void
  onThinking?: (text: string) => void
  onTool?: (payload: Record<string, unknown>) => void
  onDone?: (payload: { text: string; tokens?: number; tps?: number }) => void
  onError?: (err: ChatStreamError) => void
}

export function openChatStream(streamId: string, cb: ChatStreamHandlers): StreamHandle {
  let es: EventSource | null = null
  let closed = false

  const fail = (err: ChatStreamError) => {
    if (closed) return
    closed = true
    es?.close()
    cb.onError?.(err)
  }

  const connect = async () => {
    let ticket: string
    try {
      ticket = await issueStreamTicket('ai')
    } catch {
      fail({ code: 'ticket_failed' })
      return
    }
    if (closed) return
    const url = backendUrl(`/api/ai/stream?streamId=${encodeURIComponent(streamId)}&ticket=${encodeURIComponent(ticket)}`)
    es = new EventSource(url)

    es.addEventListener('token', (ev) => {
      const d = parseEvent<{ text: string }>(ev)
      if (d && cb.onToken) cb.onToken(d.text)
    })

    es.addEventListener('thinking', (ev) => {
      const d = parseEvent<{ text: string }>(ev)
      if (d && cb.onThinking) cb.onThinking(d.text)
    })

    es.addEventListener('tool', (ev) => {
      const d = parseEvent<Record<string, unknown>>(ev)
      if (d && cb.onTool) cb.onTool(d)
    })

    es.addEventListener('done', (ev) => {
      const d = parseEvent<{ text: string; tokens?: number; tps?: number }>(ev)
      closed = true
      es?.close()
      if (cb.onDone) cb.onDone(d ?? { text: '' })
    })

    es.addEventListener('error', (ev) => {
      // Named "error" event from the backend carries a JSON message; the native
      // EventSource error (connection drop) has no parseable data.
      const d = parseEvent<{ message: string; code?: string }>(ev)
      if (d?.message) {
        // "Unknown or expired streamId" is the normal outcome of a dropped
        // transport (the backend already cancelled the generation), not a
        // separate failure — surface it as the dedicated code.
        if (d.code === 'unknown_stream' || String(d.message).includes('Unknown or expired streamId')) {
          fail({ code: 'stream_ended', message: d.message })
          return
        }
        fail({ code: d.code, message: d.message })
        return
      }
      // Native drop: the generation was cancelled server-side, so close and fail
      // immediately — the browser's built-in retry would also be wrong here (it
      // replays the single-use ticket).
      fail({ code: 'stream_lost' })
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

// ── Agent run stream (bounded reconnect + replay dedup) ─────────────────────

export type AgentRunEventName =
  | 'plan_token'
  | 'plan_ready'
  | 'plan_approval_requested'
  | 'step_start'
  | 'step_complete'
  | 'step_retry'
  | 'step_skipped'
  | 'step_approval_requested'
  | 'complete'
  | 'error'

export interface AgentRunStreamHandlers {
  /**
   * One live event after replay dedup: `payload` is the parsed JSON data (null
   * for payloadless events from older backends). The transport owns the seq
   * high-water mark; a NEW stream session resets it, a reconnect keeps it.
   */
  onEvent: (name: AgentRunEventName, payload: Record<string, unknown> | null) => void
  /** Native transport drop before any terminal event; the transport keeps reconnecting. */
  onReconnecting?: (attempt: number) => void
  /** Reconnect budget exhausted — the run is over client-side. */
  onTransportLost?: () => void
  /** Ticket mint failed at open — terminal. */
  onOpenFailed?: () => void
}

const AGENT_STREAM_RETRY_LIMIT = 5
const AGENT_STREAM_RETRY_DELAY_MS = 800

/**
 * Replay-dedup state for one agent stream session. The backend sink re-buffers
 * events while no client is attached and replays them on reconnect, tagging every
 * payload with a monotonic per-run `seq` (starting at 1) — without dedup the
 * replayed prefix would re-drive every handler after each reconnect.
 */
export interface AgentStreamSeqState {
  lastSeq: number
}

/** A fresh high-water mark; each new run's replay starts dispatching from seq 1. */
export function newAgentStreamSeqState(): AgentStreamSeqState {
  return { lastSeq: 0 }
}

/**
 * Whether a parsed event payload is a replay this session has already dispatched.
 * A payload at or below the high-water mark is a replay (true); a higher `seq`
 * advances the mark. Payloads without a numeric `seq` (older backend, payloadless
 * events) never dedup.
 */
export function isAgentEventReplayed(payload: unknown, state: AgentStreamSeqState): boolean {
  if (!payload || typeof payload !== 'object') return false
  const seq = (payload as { seq?: unknown }).seq
  if (typeof seq !== 'number') return false
  if (seq <= state.lastSeq) return true
  state.lastSeq = seq
  return false
}

export function openAgentRunStream(runId: string, handlers: AgentRunStreamHandlers): StreamHandle {
  let es: EventSource | null = null
  let retries = 0
  // Incremented on every open/close so a ticket minted for an old stream is
  // discarded when a newer stream took over while the request was in flight.
  let epoch = 0
  // Replay dedup for the CURRENT stream session. Survives reconnects (a reconnect
  // replays already-seen events), resets only when a NEW stream is opened.
  let seqState = newAgentStreamSeqState()
  let settled = false

  const closeStream = () => {
    epoch += 1
    if (es) {
      es.close()
      es = null
    }
  }

  const connect = (currentRunId: string, currentEpoch: number): Promise<void> => {
    return issueStreamTicket('agent').then((ticket) => {
      if (currentEpoch !== epoch) return // a newer stream took over while minting
      const params = new URLSearchParams({ runId: currentRunId })
      params.set('ticket', ticket)
      es = new EventSource(backendUrl(`/api/agent/stream?${params.toString()}`))
      bindHandlers(es, currentRunId, currentEpoch)
    }).catch(() => {
      if (currentEpoch !== epoch) return
      settled = true
      closeStream()
      handlers.onOpenFailed?.()
    })
  }

  const bindHandlers = (source: EventSource, currentRunId: string, currentEpoch: number) => {
    source.addEventListener('open', () => {
      retries = 0
    })

    const dispatchParsed = (name: AgentRunEventName, payload: Record<string, unknown> | null) => {
      if (isAgentEventReplayed(payload, seqState)) return
      handlers.onEvent(name, payload)
    }
    const dispatch = (name: AgentRunEventName, ev: Event) => {
      dispatchParsed(name, parseEvent<Record<string, unknown>>(ev))
    }

    for (const name of ['plan_token', 'plan_ready', 'plan_approval_requested', 'step_start',
      'step_complete', 'step_retry', 'step_skipped', 'step_approval_requested', 'complete'] as const) {
      source.addEventListener(name, (ev) => dispatch(name, ev))
    }

    // Named "error" event from the backend carries a JSON message; the native
    // EventSource error (connection drop) has no parseable data.
    source.addEventListener('error', (ev) => {
      const d = parseEvent<{ message: string }>(ev)
      if (d?.message) {
        dispatchParsed('error', d)
        settled = true
        closeStream()
        return
      }
      // Native drop: the browser's built-in retry would replay the spent ticket (401),
      // so take over — close, mint a fresh ticket, reconnect, up to the retry limit.
      if (settled) return
      source.close()
      if (es === source) es = null
      retries += 1
      if (retries >= AGENT_STREAM_RETRY_LIMIT) {
        settled = true
        closeStream()
        handlers.onTransportLost?.()
        return
      }
      handlers.onReconnecting?.(retries)
      window.setTimeout(() => {
        if (currentEpoch === epoch) void connect(currentRunId, currentEpoch)
      }, AGENT_STREAM_RETRY_DELAY_MS)
    })
  }

  void connect(runId, epoch)

  return { close: closeStream }
}

// ── Notification stream (unbounded reconnect, capped backoff) ────────────────

export interface NotificationStreamHandlers {
  /** One live `notification` event (already-persisted row, newest). */
  onNotification: (notification: { id: number; [key: string]: unknown }) => void
  /** The transport (re)connected — refetch history here to close any gap. */
  onOpen?: () => void
}

export function openNotificationStream(cb: NotificationStreamHandlers): StreamHandle {
  let es: EventSource | null = null
  let closed = false
  let retryDelayMs = 1_000
  const MAX_RETRY_DELAY_MS = 15_000

  const connect = async () => {
    let ticket: string
    try {
      ticket = await issueStreamTicket('notifications')
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
      const data = parseEvent<{ id?: unknown }>(ev)
      // Malformed frames are dropped rather than killing the stream.
      if (data && typeof data.id === 'number') cb.onNotification(data as { id: number })
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
