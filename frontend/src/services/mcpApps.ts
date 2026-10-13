/**
 * MCP Apps domain — the browser side of the host API: sandbox page preparation and teardown,
 * page-initiated resource reads and tool calls, resource subscriptions, and the page sampling
 * forwarder. Every page-scoped call is server-scoped on the backend; the confirm dialog for
 * page-initiated tool calls lives in the UI layer (see McpAppCard).
 */
import { http } from './impl/http'

/** UI metadata the backend attaches to a tool-result SSE event (additive `ui` field). */
export interface McpToolUiInfo {
  serverId: string
  serverName: string
  tool: string
  resourceUri: string
  preferredDisplayMode?: 'inline' | 'fullscreen' | 'pip'
  prefersBorder?: boolean
  surface?: string
  showInline?: boolean
  isError?: true
  /** JSON text of the MCP result's structured content (byte-capped server-side). */
  structuredContent?: string
  /** JSON text of the MCP result's `_meta`. */
  widgetMeta?: string
  /** JSON text of the MCP result's content block array. */
  content?: string
  truncated?: boolean
  truncatedBytes?: number
}

export interface McpAppPrepareRequest {
  serverId: string
  resourceUri: string
  scopeId?: string
}

export interface McpAppResourceMetaInfo {
  prefersBorder?: boolean
  heightHint?: number
  minFrameHeight?: number
  showInline?: boolean
  permissions?: string[]
}

export interface McpAppPrepareResult {
  sandboxId: string
  /** Absolute sandbox page URL on the dedicated sandbox origin; null when unavailable. */
  sandboxUrl: string | null
  serverName: string
  resourceUri: string
  csp?: {
    connectDomains?: string[]
    resourceDomains?: string[]
    frameDomains?: string[]
    baseUriDomains?: string[]
  }
  resourceMeta?: McpAppResourceMetaInfo
  preferredDisplayMode?: 'inline' | 'fullscreen' | 'pip'
  showInline?: boolean
  surface?: string
  samplingEnabled?: boolean
}

export interface McpAppResourceContents {
  uri: string
  mimeType?: string
  text?: string
  blob?: string
}

export interface McpAppCallResult {
  isError: boolean
  content: unknown[]
  structuredContent?: unknown
  meta?: Record<string, unknown>
}

export interface McpAppSamplingMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface McpAppSamplingResult {
  text: string
  model: string
}

export interface McpAppResourceDescriptor {
  uri: string
  name?: string
  description?: string
  mimeType?: string
}

export interface McpAppResourceList {
  resources: McpAppResourceDescriptor[]
  nextCursor: string
}

export interface McpAppResourceTemplateList {
  resourceTemplates: Array<{ uriTemplate: string; name?: string; description?: string; mimeType?: string }>
}

export interface McpAppToolView {
  tool: string
  wireName: string
  resourceUri: string
  visibility?: string[]
  preferredDisplayMode?: 'inline' | 'fullscreen' | 'pip'
  surface?: string
}

export interface McpAppsService {
  prepare(request: McpAppPrepareRequest): Promise<McpAppPrepareResult>
  dispose(sandboxId: string): Promise<void>
  read(serverId: string, uri: string): Promise<{ contents: McpAppResourceContents[] }>
  listResources(serverId: string, cursor?: string): Promise<McpAppResourceList>
  listResourceTemplates(serverId: string): Promise<McpAppResourceTemplateList>
  uiTools(serverId: string): Promise<{ tools: McpAppToolView[] }>
  call(serverId: string, tool: string, args?: Record<string, unknown>): Promise<McpAppCallResult>
  subscribe(serverId: string, uri: string): Promise<void>
  unsubscribe(serverId: string, uri: string): Promise<void>
  sampling(messages: McpAppSamplingMessage[], systemPrompt?: string, maxTokens?: number): Promise<McpAppSamplingResult>
}

export const mcpAppsService: McpAppsService = {
  prepare: (request) => http.post<McpAppPrepareResult>('/api/mcp/ui/prepare', request).then((r) => r.data),
  dispose: async (sandboxId) => {
    await http.post('/api/mcp/ui/dispose', { sandboxId })
  },
  read: (serverId, uri) =>
    http.post<{ contents: McpAppResourceContents[] }>('/api/mcp/ui/read', { serverId, uri }).then((r) => r.data),
  listResources: (serverId, cursor) =>
    http
      .get<McpAppResourceList>(
        `/api/mcp/ui/resources?serverId=${encodeURIComponent(serverId)}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
      )
      .then((r) => r.data),
  listResourceTemplates: (serverId) =>
    http
      .get<McpAppResourceTemplateList>(
        `/api/mcp/ui/resource-templates?serverId=${encodeURIComponent(serverId)}`,
      )
      .then((r) => r.data),
  uiTools: (serverId) =>
    http
      .get<{ tools: McpAppToolView[] }>(`/api/mcp/ui/tools?serverId=${encodeURIComponent(serverId)}`)
      .then((r) => r.data),
  call: (serverId, tool, args = {}) =>
    http
      .post<McpAppCallResult>('/api/mcp/ui/call', { serverId, tool, arguments: args })
      .then((r) => r.data),
  subscribe: (serverId, uri) => http.post('/api/mcp/ui/subscribe', { serverId, uri }).then(() => undefined),
  unsubscribe: (serverId, uri) => http.post('/api/mcp/ui/unsubscribe', { serverId, uri }).then(() => undefined),
  sampling: (messages, systemPrompt, maxTokens) =>
    http
      .post<McpAppSamplingResult>('/api/mcp/ui/sampling', { messages, systemPrompt, maxTokens })
      .then((r) => r.data),
}
