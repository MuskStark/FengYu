import type { McpToolUiInfo } from '@/services/mcpApps'
import type { McpAppSurfaceInfo, PluginToolUiInfo } from '@/services/types'
import { McpAppCard } from '@/components/chat/McpAppCard'
import { PluginAppCard } from '@/components/chat/PluginAppCard'
import '@/styles/features/chat.css'

/**
 * Dispatcher for the additive app render descriptor: an MCP Apps `ui://` page renders
 * through the official AppBridge sandbox host; a plugin `app` view renders through the
 * standard plugin iframe bridge. Accepts both the result-event form (ToolActivity.ui) and
 * the catalog form (AgentTool.appUi — stamped `kind:'mcp'`, since result projections never
 * carry a kind), so the transcript, the flow chat panel, the run panel, and the flow
 * inspector (F-A) render from one contract. Importing chat.css here (not only in chat-page
 * modules) keeps the app-card styles alive in flow-builder surfaces reached without a chat
 * page ever mounting.
 */
export function AppCard({ ui, scopeId, defaultOpen = false, hosted = false }: {
  ui: McpToolUiInfo | PluginToolUiInfo | McpAppSurfaceInfo
  scopeId: string
  /** Dialog hosts (F-A) start expanded instead of collapsed. */
  defaultOpen?: boolean
  /** An already-modal host demotes dialog-mode plugin views to the inline stage. */
  hosted?: boolean
}) {
  if (ui.kind === 'plugin') {
    return <PluginAppCard ui={ui} scopeId={scopeId} defaultOpen={defaultOpen} hosted={hosted} />
  }
  // Catalog surfaces are stamped kind:'mcp' (result-form MCP projections carry no kind and
  // keep every result field — content, showInline, truncated — intact through the passthrough).
  const mcpUi: McpToolUiInfo = ui.kind === 'mcp'
    ? { serverId: ui.serverId, serverName: ui.serverName ?? '', tool: ui.tool ?? '', resourceUri: ui.resourceUri }
    : ui as McpToolUiInfo
  return <McpAppCard ui={mcpUi} scopeId={scopeId} defaultOpen={defaultOpen} />
}
