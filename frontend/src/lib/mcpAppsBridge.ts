/**
 * Host-side bridge session for one MCP Apps sandbox page. Wraps the official
 * `AppBridge` with FengYu's handlers: page-initiated tool calls go through the
 * caller-provided confirm gate + the server-scoped REST endpoint, resource reads
 * through the page-cap'ed read endpoint, links through a validated window.open.
 * The page runs in a sandboxed (opaque-origin) iframe served from the sandbox
 * origin — a distinct loopback port on 127.0.0.1 — so the bridge never grants
 * it DOM or storage access.
 */
import { AppBridge } from '@modelcontextprotocol/ext-apps/app-bridge'
import { PostMessageTransport } from '@modelcontextprotocol/ext-apps'
import type { CallToolResult, Implementation, ReadResourceResult } from '@modelcontextprotocol/client'
import type { McpUiHostCapabilities, McpUiHostContext } from '@modelcontextprotocol/ext-apps/app-bridge'
import { z } from 'zod'
import { services } from '@/services'
import type { McpAppPrepareResult, McpToolUiInfo } from '@/services/mcpApps'

const HOST_INFO: Implementation = { name: 'fengyu', version: '4.1.0' }

/**
 * Experimental capability keys the host declares for its own extensions. A page
 * feature-detects them; anything absent means "not supported", so pages degrade.
 * `ui/set-widget-state` is the companion request method of the widgetState keys.
 */
export const WIDGET_STATE_EXPERIMENTAL_KEYS = ['fengyu/widgetState'] as const

export type McpAppDisplayMode = 'inline' | 'fullscreen' | 'pip'

export interface McpAppSessionCallbacks {
  /** Page-initiated tools/call. Return null to refuse (the page gets an error result). */
  onCallTool(args: { tool: string; args: Record<string, unknown> }): Promise<CallToolResult | null>
  /** Page asked to open an external link; the host validates and opens it. */
  onOpenLink(url: string): void
  /** Page asked the host to resize the card (clamped by the card, not here). */
  onSizeChange(height: number): void
  /** Page requested its own teardown (close the card). */
  onRequestTeardown(): void
  /** Page completed the ui/initialize handshake — now tool input/result may flow. */
  onInitialized(): void
  /** Page-initiated sampling request (only declared when the backend opted in). */
  onSampling?(messages: { role: 'user' | 'assistant'; content: string }[], systemPrompt?: string): Promise<string>
  /** Page committed its UI state to the host (memory-only, per logical page scope). */
  onWidgetState?(state: unknown): void
  /** Page subscribed one of its server's resources (backend ref-counts per (server, uri)). */
  onSubscribeResource?(uri: string): Promise<void>
  /** Page released a subscription. */
  onUnsubscribeResource?(uri: string): Promise<void>
}

export interface McpAppSession {
  readonly bridge: AppBridge
  sendToolResultFromUi(ui: McpToolUiInfo): Promise<void>
  /** Forwards a backend resource-updated event to the page (spec notification). */
  notifyResourceUpdated(uri: string): Promise<void>
  dispose(): Promise<void>
}

/** Host capabilities: only what FengYu actually implements, so pages degrade honestly. */
function buildHostCapabilities(prepared: McpAppPrepareResult): McpUiHostCapabilities {
  return {
    openLinks: {},
    serverTools: {},
    serverResources: { listChanged: false },
    logging: {},
    downloadFile: {},
    experimental: {
      'fengyu/widgetState': {},
    },
    ...(prepared.samplingEnabled ? { sampling: {} } : {}),
  }
}

function buildHostContext(ui: McpToolUiInfo, displayMode: McpAppDisplayMode): McpUiHostContext {
  const theme = document.documentElement.classList.contains('theme-fengyu-dark') ? 'dark' : 'light'
  void ui
  return {
    theme,
    displayMode,
  }
}

/** Parses the byte-capped JSON texts the backend projected into the SSE `ui` payload. */
function parseJsonText(value: string | undefined): unknown {
  if (!value) return undefined
  try {
    return JSON.parse(value) as unknown
  } catch {
    return undefined
  }
}

export function startMcpAppSession(opts: {
  frame: HTMLIFrameElement
  ui: McpToolUiInfo
  prepared: McpAppPrepareResult
  displayMode: McpAppDisplayMode
  /** Logical page scope: `tool:<toolCallId>` for a card, `surface:<id>` for a panel. */
  scopeId: string
  /** Widget state previously committed by this logical page (memory store), if any. */
  initialWidgetState?: unknown
  callbacks: McpAppSessionCallbacks
}): McpAppSession {
  const { frame, ui, prepared, displayMode, scopeId, initialWidgetState, callbacks } = opts

  const bridge = new AppBridge(null, HOST_INFO, buildHostCapabilities(prepared), {
    hostContext: buildHostContext(ui, displayMode),
  })

  bridge.oncalltool = async (params) => {
    const granted = await callbacks.onCallTool({
      tool: params.name,
      args: (params.arguments ?? {}) as Record<string, unknown>,
    })
    if (!granted) {
      return {
        content: [{ type: 'text', text: 'The user declined this tool call.' }],
        isError: true,
      }
    }
    return granted
  }

  bridge.onreadresource = async (params): Promise<ReadResourceResult> => {
    const result = await services.mcpApps.read(ui.serverId, params.uri)
    return { contents: result.contents as ReadResourceResult['contents'] }
  }

  bridge.onlistresources = async (params) => {
    const page = await services.mcpApps.listResources(ui.serverId, params?.cursor ?? undefined)
    return {
      resources: page.resources.map(resource => ({ ...resource, name: resource.name || resource.uri })),
      nextCursor: page.nextCursor || undefined,
    }
  }

  bridge.onlistresourcetemplates = async () => {
    const page = await services.mcpApps.listResourceTemplates(ui.serverId)
    return {
      resourceTemplates: page.resourceTemplates.map(template => ({
        ...template,
        name: template.name || template.uriTemplate,
      })),
    }
  }

  bridge.onopenlink = async (params) => {
    callbacks.onOpenLink(params.url)
    return {}
  }

  bridge.ondownloadfile = async (params) => {
    const contents = Array.isArray(params.contents) ? params.contents : []
    contents.forEach((item, index) => {
      const resource = (item as { resource?: { uri?: string; name?: string; mimeType?: string; text?: string; blob?: string } }).resource
      if (!resource) return
      const name = resource.name || `mcp-app-file-${index + 1}`
      const href = resource.blob
        ? `data:${resource.mimeType || 'application/octet-stream'};base64,${resource.blob}`
        : resource.text !== undefined
          ? `data:${resource.mimeType || 'text/plain'};charset=utf-8,${encodeURIComponent(resource.text)}`
          : null
      if (!href) return
      const anchor = document.createElement('a')
      anchor.href = href
      anchor.download = name
      anchor.rel = 'noopener'
      anchor.click()
    })
    return {}
  }

  bridge.onsizechange = (params) => {
    const height = params.height
    if (typeof height === 'number' && Number.isFinite(height)) callbacks.onSizeChange(height)
  }

  bridge.onrequestteardown = () => {
    callbacks.onRequestTeardown()
  }

  // ── FengYu extensions (experimental capability keys; pages feature-detect) ──

  // Widget state: the page hands its UI state to the host; memory-only, scoped to
  // serverId::scopeId, re-delivered on remount via the widget-state notification below.
  bridge.setRequestHandler(
    'ui/set-widget-state',
    { params: z.object({ widgetState: z.unknown() }), result: z.object({}).loose() },
    async (request) => {
      const params = (request as { params?: { widgetState?: unknown } }).params
      callbacks.onWidgetState?.(params?.widgetState)
      return {}
    },
  )

  // Page-facing resource subscriptions: the standard MCP requests, proxied to the
  // backend hub (which ref-counts per server+uri). Updates come back through
  // notifyResourceUpdated as the standard resources/updated notification.
  bridge.setRequestHandler(
    'resources/subscribe',
    { params: z.object({ uri: z.string() }), result: z.object({}).loose() },
    async (request) => {
      const params = (request as { params?: { uri?: string } }).params
      if (params?.uri) await callbacks.onSubscribeResource?.(params.uri)
      return {}
    },
  )
  bridge.setRequestHandler(
    'resources/unsubscribe',
    { params: z.object({ uri: z.string() }), result: z.object({}).loose() },
    async (request) => {
      const params = (request as { params?: { uri?: string } }).params
      if (params?.uri) await callbacks.onUnsubscribeResource?.(params.uri)
      return {}
    },
  )

  if (prepared.samplingEnabled && callbacks.onSampling) {
    const samplingCallback = callbacks.onSampling
    bridge.oncreatesamplingmessage = async (params) => {
      const messages = (params.messages ?? []) as Array<{
        role?: string
        content?: { type?: string; text?: string }
      }>
      const text = await samplingCallback(
        messages
          .map((message) => ({
            role: message.role === 'assistant' ? ('assistant' as const) : ('user' as const),
            content: message.content?.type === 'text' ? String(message.content.text ?? '') : '',
          }))
          .filter((message) => message.content !== ''),
        params.systemPrompt,
      )
      return {
        role: 'assistant' as const,
        model: 'fengyu',
        content: { type: 'text' as const, text },
      }
    }
  }

  // connect() only starts the handshake once the page posts `ui/initialize`; the frame is
  // mounted by the time the session is created, so the contentWindow is non-null here.
  const target = frame.contentWindow
  if (!target) throw new Error('MCP app iframe is not mounted')
  const transport = new PostMessageTransport(target, target)

  const sendToolResultFromUi = async (uiInfo: McpToolUiInfo): Promise<void> => {
    const content = parseJsonText(uiInfo.content)
    const structuredContent = parseJsonText(uiInfo.structuredContent)
    await bridge.sendToolResult({
      content: Array.isArray(content)
        ? content as CallToolResult['content']
        : [{ type: 'text', text: 'The tool result carried no renderable content.' }],
      ...(structuredContent !== undefined && structuredContent !== null
        ? { structuredContent }
        : {}),
      isError: uiInfo.isError === true,
    })
  }

  const dispose = async (): Promise<void> => {
    // Spec teardown: give the page ≤500ms to clean up, then drop the transport.
    try {
      await Promise.race([
        bridge.teardownResource({}),
        new Promise((resolve) => setTimeout(resolve, 500)),
      ])
    } catch {
      // A page that ignores teardown still gets unmounted by the caller.
    }
    for (const method of ['ui/set-widget-state', 'resources/subscribe', 'resources/unsubscribe']) {
      try {
        bridge.removeRequestHandler(method)
      } catch {
        // Never registered (capability off): nothing to clean.
      }
    }
    await transport.close().catch(() => undefined)
  }

  void bridge.connect(transport)

  const notifyResourceUpdated = async (uri: string): Promise<void> => {
    await bridge.notification({
      method: 'notifications/resources/updated',
      params: { uri },
    })
  }

  bridge.oninitialized = () => {
    callbacks.onInitialized()
    // Initial widget state for a remounting page: delivered as a dedicated notification
    // right after the handshake (the hostContext schema strips unknown keys, so a context
    // key would never survive the official SDK's validation).
    if (initialWidgetState !== undefined) {
      void bridge
        .notification({
          method: 'ui/notifications/widget-state',
          params: { widgetState: initialWidgetState, scopeId },
        })
        .catch(() => undefined)
    }
  }

  return { bridge, sendToolResultFromUi, notifyResourceUpdated, dispose }
}
