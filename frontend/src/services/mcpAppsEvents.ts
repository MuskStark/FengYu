/**
 * Browser consumer of the backend MCP Apps event stream (`GET /api/mcp/ui/events`,
 * one-time ticket like every SSE endpoint). A singleton with reference counting: the
 * stream opens when the first sandbox page session subscribes and closes when the last
 * one leaves. `resource-updated` payloads ({serverId, uri}) fan out to registered
 * listeners, which forward the news into the matching page over the bridge.
 */
import { http } from './impl/http'
import { getPlatform } from '@/platform'

export type McpAppsEventListener = (event: { serverId: string; uri: string }) => void

/** Self-heal cadence, mirroring the notification stream (`impl/streams.ts`): 1s doubling
 *  to a 15s cap, reset once a connection opens again. */
const RECONNECT_DELAY_MS = 1_000
const MAX_RECONNECT_DELAY_MS = 15_000

class McpAppsEventsClient {
  private source: EventSource | null = null
  private opening = false
  private reconnectTimer: number | null = null
  private retryDelayMs = RECONNECT_DELAY_MS
  private readonly listeners = new Set<McpAppsEventListener>()

  /** Registers a listener; opens the shared stream on the first subscription. */
  async acquire(listener: McpAppsEventListener): Promise<void> {
    this.listeners.add(listener)
    if (this.source || this.opening) return
    await this.open()
  }

  /** Unregisters a listener; closes the shared stream when the last one leaves. */
  release(listener: McpAppsEventListener): void {
    this.listeners.delete(listener)
    if (this.listeners.size === 0) {
      // Nobody is listening: cancel any pending reconnect and reset the backoff so a
      // future session starts fresh.
      if (this.reconnectTimer !== null) {
        window.clearTimeout(this.reconnectTimer)
        this.reconnectTimer = null
      }
      this.retryDelayMs = RECONNECT_DELAY_MS
      if (this.source) {
        this.source.close()
        this.source = null
      }
    }
  }

  /** Test hook. */
  listenerCount(): number {
    return this.listeners.size
  }

  /** Mints a ticket and connects. Transport drops and failed mints alike self-heal via
   *  {@link #scheduleReconnect} while at least one listener remains. */
  private async open(): Promise<void> {
    this.opening = true
    try {
      const ticket = await http
        .post<{ ticket: string }>('/api/mcp/ui/stream-ticket')
        .then((r) => r.data.ticket)
      // A stream client that connected while we were minting the ticket wins; drop ours.
      if (this.source) return
      const base = getPlatform().apiBase()?.replace(/\/$/, '') ?? ''
      const source = new EventSource(`${base}/api/mcp/ui/events?ticket=${encodeURIComponent(ticket)}`)
      source.addEventListener('open', () => {
        this.retryDelayMs = RECONNECT_DELAY_MS
      })
      source.addEventListener('resource-updated', (event) => {
        try {
          const payload = JSON.parse((event as MessageEvent).data as string) as {
            serverId?: string
            uri?: string
          }
          if (!payload.serverId || !payload.uri) return
          this.listeners.forEach((listener) => {
            try {
              listener({ serverId: payload.serverId as string, uri: payload.uri as string })
            } catch {
              // One broken listener must not starve the others.
            }
          })
        } catch {
          // Malformed frame: ignore.
        }
      })
      source.onerror = () => {
        // The browser retry never applies (the one-time ticket is spent) — close and
        // self-heal with a fresh ticket, but only if this is still the live source (a
        // superseded one must not steal the slot from its successor).
        source.close()
        const wasCurrent = this.source === source
        if (wasCurrent) this.source = null
        if (wasCurrent && this.listeners.size > 0) this.scheduleReconnect()
      }
      this.source = source
    } catch {
      // The ticket mint failed; retry later rather than stranding registered listeners
      // until some future card happens to call acquire() again.
      if (this.listeners.size > 0) this.scheduleReconnect()
    } finally {
      this.opening = false
    }
  }

  /** One reconnect attempt per pending timer, backing off between tries. The fire-time
   *  guards make a late timer a no-op once everyone left, a new source is already live,
   *  or an open() is mid-flight. */
  private scheduleReconnect(): void {
    if (this.reconnectTimer !== null) return
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      if (this.listeners.size === 0 || this.source || this.opening) return
      void this.open()
    }, this.retryDelayMs)
    this.retryDelayMs = Math.min(this.retryDelayMs * 2, MAX_RECONNECT_DELAY_MS)
  }
}

export const mcpAppsEvents = new McpAppsEventsClient()
