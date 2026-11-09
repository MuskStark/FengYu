/**
 * Host-side bridge session for one MCP Apps sandbox page. Wraps the official
 * `AppBridge` with FengYu's handlers: page-initiated tool calls go through the
 * caller-provided confirm gate + the server-scoped REST endpoint, resource reads
 * through the page-cap'ed read endpoint, links through a validated window.open.
 * The page runs in a sandboxed (opaque-origin) iframe served from the sandbox
 * origin — a distinct loopback port on 127.0.0.1 — so the bridge never grants
 * it DOM or storage access.
 *
 * Spec lifecycle (SEP-1865): after `ui/notifications/initialized` the host sends
 * `tool-input` (the tool arguments, when known) then `tool-result`; later turns
 * may add `tool-cancelled`. Host context changes (theme/locale/display mode) are
 * diffed by the SDK's `setHostContext` and emitted as `host-context-changed`.
 */
import { AppBridge } from '@modelcontextprotocol/ext-apps/app-bridge'
import { PostMessageTransport } from '@modelcontextprotocol/ext-apps'
import type { CallToolResult, Implementation, ReadResourceResult } from '@modelcontextprotocol/client'
import type { McpUiHostCapabilities, McpUiHostContext, McpUiStyles, McpUiStyleVariableKey } from '@modelcontextprotocol/ext-apps/app-bridge'
import { z } from 'zod'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import { instance as i18n } from '@/i18n'
import type { McpAppPrepareResult, McpAppSamplingMessage, McpAppSamplingResult, McpAppSamplingTool, McpToolUiInfo } from '@/services/mcpApps'

const HOST_INFO: Implementation = { name: 'fengyu', version: '4.1.0' }

/**
 * Experimental capability keys the host declares for its own extensions. A page
 * feature-detects them; anything absent means "not supported", so pages degrade.
 * `ui/set-widget-state` is the companion request method of the widgetState keys.
 */
export const WIDGET_STATE_EXPERIMENTAL_KEYS = ['fengyu/widgetState'] as const

export type McpAppDisplayMode = 'inline' | 'fullscreen' | 'pip'

/** Options the page may attach to a sampling request (all optional, host-capped). */
export interface McpAppSamplingOptions {
  systemPrompt?: string
  maxTokens?: number
  tools?: McpAppSamplingTool[]
  toolChoice?: 'auto' | 'required' | 'none'
}

export interface McpAppSessionCallbacks {
  /** Page-initiated tools/call. Return null to refuse (the page gets an error result). */
  onCallTool(args: { tool: string; args: Record<string, unknown> }): Promise<CallToolResult | null>
  /** Page asked to open an external link; the host validates and opens it. */
  onOpenLink(url: string): void
  /** Page asked the host to resize the card (clamped by the card, not here). */
  onSizeChange(height: number): void
  /** Page requested its own teardown (close the card). */
  onRequestTeardown(): void
  /** Handshake complete — tool input/result have been delivered by the session itself. */
  onInitialized(): void
  /**
   * Page sent a message into the conversation (`ui/message`). Only conversation
   * surfaces provide this; its presence also declares the `message` capability.
   */
  onSendMessage?(text: string): { isError?: boolean }
  /**
   * Page parked context for the model's next turn (`ui/update-model-context`).
   * Its presence also declares the `updateModelContext` capability.
   */
  onUpdateModelContext?(text: string, structured?: unknown): void
  /**
   * Page asked for a display-mode change (`ui/request-display-mode`). Resolve
   * with the mode actually applied (the host has the final say).
   */
  onRequestDisplayMode?(mode: McpAppDisplayMode): Promise<McpAppDisplayMode>
  /** Page-initiated sampling request (only declared when the backend opted in). */
  onSampling?(messages: McpAppSamplingMessage[], options: McpAppSamplingOptions): Promise<McpAppSamplingResult>
  /** Page committed its UI state to the host (memory-only, per logical page scope). */
  onWidgetState?(state: unknown): void
  /** Page subscribed one of its server's resources (backend ref-counts per (server, uri)). */
  onSubscribeResource?(uri: string): Promise<void>
  /** Page released a subscription. */
  onUnsubscribeResource?(uri: string): Promise<void>
}

export interface McpAppSession {
  readonly bridge: AppBridge
  /** Re-sends the captured tool result (the initial send happens at handshake). */
  sendToolResultFromUi(ui: McpToolUiInfo): Promise<void>
  /** Tells the page its tool call was cancelled (spec `tool-cancelled` notification). */
  notifyToolCancelled(reason?: string): Promise<void>
  /** Merges host-context fields and emits `host-context-changed` (SDK diffs). */
  updateHostContext(partial: Partial<McpUiHostContext>): void
  /** Forwards a backend resource-updated event to the page (spec notification). */
  notifyResourceUpdated(uri: string): Promise<void>
  dispose(): Promise<void>
}

/** Host capabilities: only what FengYu actually implements, so pages degrade honestly. */
function buildHostCapabilities(
  prepared: McpAppPrepareResult,
  callbacks: Pick<McpAppSessionCallbacks, 'onSendMessage' | 'onUpdateModelContext'>,
): McpUiHostCapabilities {
  return {
    openLinks: {},
    serverTools: {},
    serverResources: { listChanged: false },
    logging: {},
    downloadFile: {},
    ...(callbacks.onSendMessage ? { message: { text: {} } } : {}),
    ...(callbacks.onUpdateModelContext ? { updateModelContext: { text: {}, structuredContent: {} } } : {}),
    ...(prepared.samplingEnabled ? { sampling: { tools: {} } } : {}),
    experimental: {
      'fengyu/widgetState': {},
    },
  }
}

/**
 * SDK style-variable keys → FengYu token expressions. Only tokens that resolve to
 * literal values survive (var()/color-mix() strings cannot resolve inside the page),
 * so indirect tokens are simply omitted — every key is optional per the schema.
 */
const STYLE_VARIABLE_SOURCES: Partial<Record<McpUiStyleVariableKey, string>> = {
  '--color-background-primary': '--color-background',
  '--color-background-secondary': '--color-panel',
  '--color-background-tertiary': '--color-card',
  '--color-background-inverse': '--color-foreground',
  '--color-background-ghost': '--color-surface',
  '--color-background-info': '--color-primary',
  '--color-background-danger': '--color-destructive',
  '--color-background-success': '--color-success',
  '--color-background-warning': '--color-warning',
  '--color-text-primary': '--color-foreground',
  '--color-text-secondary': '--color-foreground-subtle',
  '--color-text-tertiary': '--color-foreground-subtlest',
  '--color-text-inverse': '--color-primary-foreground',
  '--color-text-ghost': '--color-foreground-subtlest',
  '--color-text-info': '--color-accent',
  '--color-text-danger': '--color-destructive',
  '--color-text-success': '--color-success',
  '--color-text-warning': '--color-warning',
  '--color-border-primary': '--color-border',
  '--color-border-secondary': '--color-border-hover',
  '--color-border-tertiary': '--color-input-border',
  '--color-border-inverse': '--color-foreground',
  '--color-border-info': '--color-accent',
  '--color-border-danger': '--color-destructive',
  '--color-border-success': '--color-success',
  '--color-border-warning': '--color-warning',
  '--color-ring-primary': '--color-input-border-focused',
  '--color-ring-secondary': '--color-border-hover',
  '--color-ring-inverse': '--color-foreground',
  '--color-ring-info': '--color-accent',
  '--color-ring-danger': '--color-destructive',
  '--color-ring-success': '--color-success',
  '--color-ring-warning': '--color-warning',
  '--font-mono': '--font-mono',
  '--font-weight-normal': '400',
  '--font-weight-medium': '500',
  '--font-weight-semibold': '600',
  '--font-weight-bold': '700',
  '--font-text-xs-line-height': '1.5',
  '--font-text-sm-line-height': '1.5',
  '--font-text-md-line-height': '1.5',
  '--font-text-lg-line-height': '1.45',
  '--border-radius-xs': '4px',
  '--border-radius-sm': '6px',
  '--border-radius-md': '8px',
  '--border-radius-lg': '12px',
  '--border-radius-xl': '16px',
  '--border-radius-full': '9999px',
  '--border-width-regular': '1px',
  '--shadow-hairline': '0 0 0 1px rgb(0 0 0 / 0.06)',
  '--shadow-sm': '0 1px 2px rgb(0 0 0 / 0.08)',
  '--shadow-md': '0 2px 8px rgb(0 0 0 / 0.12)',
  '--shadow-lg': '0 8px 24px rgb(0 0 0 / 0.16)',
}

/** Type ladder offsets from --ui-font-size (px), shared by text and heading sizes. */
const FONT_SIZE_OFFSETS: Partial<Record<McpUiStyleVariableKey, number>> = {
  '--font-text-xs-size': -4,
  '--font-text-sm-size': -2,
  '--font-text-md-size': 0,
  '--font-text-lg-size': 2,
  '--font-heading-xs-size': 4,
  '--font-heading-sm-size': 6,
  '--font-heading-md-size': 8,
  '--font-heading-lg-size': 12,
  '--font-heading-xl-size': 16,
  '--font-heading-2xl-size': 22,
  '--font-heading-3xl-size': 28,
}

const HEADING_LINE_HEIGHT: Partial<Record<McpUiStyleVariableKey, string>> = {
  '--font-heading-xs-line-height': '1.4',
  '--font-heading-sm-line-height': '1.35',
  '--font-heading-md-line-height': '1.3',
  '--font-heading-lg-line-height': '1.25',
  '--font-heading-xl-line-height': '1.2',
  '--font-heading-2xl-line-height': '1.15',
  '--font-heading-3xl-line-height': '1.1',
}

/** Resolves the SDK's style-variable set against the live FengYu theme tokens. */
function collectStyleVariables(): McpUiStyles {
  const variables: Partial<Record<McpUiStyleVariableKey, string>> = {}
  if (typeof document === 'undefined') return variables as McpUiStyles
  const computed = getComputedStyle(document.documentElement)

  const baseSize = parseFloat(computed.getPropertyValue('--ui-font-size')) || 14
  for (const [key, offset] of Object.entries(FONT_SIZE_OFFSETS)) {
    variables[key as McpUiStyleVariableKey] = `${Math.round((baseSize + offset) * 100) / 100}px`
  }
  Object.assign(variables, HEADING_LINE_HEIGHT)

  variables['--font-sans'] = getComputedStyle(document.body).fontFamily
  for (const [key, source] of Object.entries(STYLE_VARIABLE_SOURCES)) {
    const value = computed.getPropertyValue(source).trim()
    // Indirect values (var()/color-mix()/url()) cannot resolve inside the page.
    if (value && !/\b(?:var|color-mix|url)\(/.test(value)) {
      variables[key as McpUiStyleVariableKey] = value
    }
  }
  // The schema allows every key to be undefined; the cast only bridges the
  // Partial→Record typing gap for the omitted keys.
  return variables as McpUiStyles
}

function currentTheme(): 'light' | 'dark' {
  return document.documentElement.classList.contains('theme-fengyu-dark') ? 'dark' : 'light'
}

/** Context fields that change with host settings (theme switch, locale switch). */
export function hostContextRefresh(): Partial<McpUiHostContext> {
  return {
    theme: currentTheme(),
    locale: i18n.language || 'en',
    styles: { variables: collectStyleVariables() },
  }
}

function buildHostContext(displayMode: McpAppDisplayMode, frame: HTMLIFrameElement): McpUiHostContext {
  let timeZone: string | undefined
  try {
    timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone
  } catch {
    timeZone = undefined
  }
  return {
    theme: currentTheme(),
    displayMode,
    availableDisplayModes: ['inline', 'fullscreen'],
    locale: i18n.language || 'en',
    ...(timeZone ? { timeZone } : {}),
    platform: getPlatform().kind === 'desktop' ? 'desktop' : 'web',
    styles: { variables: collectStyleVariables() },
    containerDimensions: { width: Math.max(0, Math.round(frame.clientWidth)) },
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

/** Flattens one SDK content block (or plain text) into the sampling wire blocks. */
function mapSamplingBlock(block: unknown, out: McpAppSamplingMessage['blocks']): void {
  if (typeof block === 'string') {
    if (block) out.push({ kind: 'text', text: block })
    return
  }
  if (!block || typeof block !== 'object') return
  const record = block as { type?: string; text?: string; id?: string; name?: string; input?: unknown; toolUseId?: string; content?: unknown; isError?: boolean }
  if (record.type === 'text' && record.text) {
    out.push({ kind: 'text', text: record.text })
  } else if (record.type === 'tool_use' && record.id && record.name) {
    out.push({ kind: 'tool_use', id: record.id, name: record.name, input: (record.input ?? {}) as Record<string, unknown> })
  } else if (record.type === 'tool_result' && record.toolUseId) {
    // A tool_result's content is text or a block array; the page loop only needs text.
    const inner = record.content
    const text = typeof inner === 'string'
      ? inner
      : Array.isArray(inner)
        ? inner.map((item) => (item && typeof item === 'object' && 'text' in item ? String((item as { text?: string }).text ?? '') : '')).join('\n')
        : ''
    out.push({ kind: 'tool_result', toolUseId: record.toolUseId, text, isError: record.isError === true })
  }
  // image/audio blocks are dropped: the page sampling channel is text + tool loop.
}

function mapSamplingMessages(messages: unknown): McpAppSamplingMessage[] {
  if (!Array.isArray(messages)) return []
  const mapped: McpAppSamplingMessage[] = []
  for (const message of messages) {
    if (!message || typeof message !== 'object') continue
    const record = message as { role?: string; content?: unknown }
    const blocks: McpAppSamplingMessage['blocks'] = []
    if (Array.isArray(record.content)) record.content.forEach((block) => mapSamplingBlock(block, blocks))
    else mapSamplingBlock(record.content, blocks)
    if (!blocks.length) continue
    mapped.push({ role: record.role === 'assistant' ? 'assistant' : 'user', blocks })
  }
  return mapped
}

export function startMcpAppSession(opts: {
  frame: HTMLIFrameElement
  ui: McpToolUiInfo
  prepared: McpAppPrepareResult
  displayMode: McpAppDisplayMode
  /** Logical page scope: `tool:<toolCallId>` for a card, `surface:<id>` for a panel. */
  scopeId: string
  /** Complete tool arguments for the `tool-input` notification (chat surfaces have them). */
  toolArgs?: Record<string, unknown>
  /** Widget state previously committed by this logical page (memory store), if any. */
  initialWidgetState?: unknown
  callbacks: McpAppSessionCallbacks
}): McpAppSession {
  const { frame, ui, prepared, scopeId, toolArgs, initialWidgetState, callbacks } = opts
  let displayMode: McpAppDisplayMode = opts.displayMode
  let hostContext: McpUiHostContext = buildHostContext(displayMode, frame)

  const bridge = new AppBridge(null, HOST_INFO, buildHostCapabilities(prepared, callbacks), {
    hostContext,
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

  // Conversation injection (`ui/message`): text blocks only — the capability is
  // declared with `message: {text: {}}` and non-conversation surfaces omit the
  // callback entirely, so those pages degrade per the spec.
  bridge.onmessage = async (params) => {
    if (!callbacks.onSendMessage) return { isError: true }
    const text = (Array.isArray(params.content) ? params.content : [])
      .map((block) => (block && typeof block === 'object' && 'text' in block ? String((block as { text?: string }).text ?? '') : ''))
      .filter(Boolean)
      .join('\n')
    if (!text) return { isError: true }
    return callbacks.onSendMessage(text) ?? {}
  }

  // Model-context parking (`ui/update-model-context`): last write wins; the
  // conversation merges it into the next outgoing turn.
  bridge.onupdatemodelcontext = async (params) => {
    const text = (params.content ?? [])
      .map((block) => (block && typeof block === 'object' && 'text' in block ? String((block as { text?: string }).text ?? '') : ''))
      .filter(Boolean)
      .join('\n')
    if (text || params.structuredContent !== undefined) {
      callbacks.onUpdateModelContext?.(text, params.structuredContent)
    }
    return {}
  }

  // Display-mode requests: the host has the final say — the card resolves the
  // mode it can actually apply (pip degrades to inline) and the session echoes
  // it into the host context.
  bridge.onrequestdisplaymode = async (params) => {
    const requested = params.mode
    const applied = callbacks.onRequestDisplayMode
      ? await callbacks.onRequestDisplayMode(requested)
      : displayMode
    displayMode = applied
    updateHostContext({ displayMode: applied })
    return { mode: applied }
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
      const result = await samplingCallback(mapSamplingMessages(params.messages), {
        systemPrompt: params.systemPrompt,
        maxTokens: params.maxTokens,
        tools: (params.tools ?? []).map((tool) => ({
          name: tool.name,
          description: tool.description,
          inputSchema: tool.inputSchema as Record<string, unknown> | undefined,
        })),
        toolChoice: params.toolChoice?.mode,
      })
      if (result.stopReason === 'toolUse' && result.toolCalls?.length) {
        // Tool-loop reply: the SDK accepts array content with tool_use blocks
        // when the request carried tools; the page executes them itself.
        return {
          role: 'assistant' as const,
          model: result.model,
          stopReason: 'toolUse' as const,
          content: result.toolCalls.map((call) => ({
            type: 'tool_use' as const,
            id: call.id,
            name: call.name,
            input: call.input,
          })),
        }
      }
      return {
        role: 'assistant' as const,
        model: result.model,
        content: { type: 'text' as const, text: result.text },
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

  const notifyToolCancelled = (reason?: string): Promise<void> =>
    bridge.sendToolCancelled(reason ? { reason } : {})

  const updateHostContext = (partial: Partial<McpUiHostContext>): void => {
    hostContext = { ...hostContext, ...partial }
    bridge.setHostContext(hostContext)
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
    // Spec data-delivery order: tool-input (when the arguments are known), then
    // tool-result, then the FengYu widget-state extension notification.
    void (async () => {
      if (toolArgs !== undefined) {
        await bridge.sendToolInput({ arguments: toolArgs }).catch(() => undefined)
      }
      await sendToolResultFromUi(ui).catch(() => undefined)
      if (initialWidgetState !== undefined || ui.widgetMeta) {
        const parsedWidgetMeta = parseJsonText(ui.widgetMeta)
        await bridge
          .notification({
            method: 'ui/notifications/widget-state',
            params: {
              ...(initialWidgetState !== undefined ? { widgetState: initialWidgetState } : {}),
              scopeId,
              ...(parsedWidgetMeta !== undefined ? { widgetMeta: parsedWidgetMeta } : {}),
            },
          })
          .catch(() => undefined)
      }
      callbacks.onInitialized()
    })()
  }

  return { bridge, sendToolResultFromUi, notifyToolCancelled, updateHostContext, notifyResourceUpdated, dispose }
}
