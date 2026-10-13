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

class McpAppsEventsClient {
  private source: EventSource | null = null
  private opening = false
  private readonly listeners = new Set<McpAppsEventListener>()

  /** Registers a listener; opens the shared stream on the first subscription. */
  async acquire(listener: McpAppsEventListener): Promise<void> {
    this.listeners.add(listener)
    if (this.source || this.opening) return
    this.opening = true
    try {
      const ticket = await http
        .post<{ ticket: string }>('/api/mcp/ui/stream-ticket')
        .then((r) => r.data.ticket)
      // A stream client that connected while we were minting the ticket wins; drop ours.
      if (this.source) return
      const base = getPlatform().apiBase()?.replace(/\/$/, '') ?? ''
      const source = new EventSource(`${base}/api/mcp/ui/events?ticket=${encodeURIComponent(ticket)}`)
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
        // The browser retry never applies (the one-time ticket is spent); the next
        // acquire() re-opens with a fresh ticket.
        source.close()
        if (this.source === source) this.source = null
      }
      this.source = source
    } finally {
      this.opening = false
    }
  }

  /** Unregisters a listener; closes the shared stream when the last one leaves. */
  release(listener: McpAppsEventListener): void {
    this.listeners.delete(listener)
    if (this.listeners.size === 0 && this.source) {
      this.source.close()
      this.source = null
    }
  }

  /** Test hook. */
  listenerCount(): number {
    return this.listeners.size
  }
}

export const mcpAppsEvents = new McpAppsEventsClient()
