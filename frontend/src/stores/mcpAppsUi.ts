import { create } from 'zustand'

/**
 * Host-side widget state for MCP Apps sandbox pages — the FengYu analog of the spec
 * extension other hosts keep under an experimental key. The page hands UI state to the
 * host (`ui/set-widget-state`); the host keeps it in MEMORY ONLY, keyed by
 * `${serverId}::${scopeId}` (scopeId is `tool:<toolCallId>` for a card or
 * `surface:<id>` for a panel), and re-delivers it when the same logical page remounts in
 * the session. Nothing persists: closing the page keeps the state for remounts, an app
 * restart starts empty, and moving a card never copies the state.
 */
/**
 * One parked `ui/update-model-context` payload. Last write wins per conversation;
 * the send path merges it into the next outgoing turn and clears the slot (spec:
 * the host defers delivery until the next user message, each call overwrites).
 */
export interface McpAppPendingModelContext {
  text?: string
  structured?: unknown
}

interface McpAppsUiState {
  widgetState: Record<string, unknown>
  setWidgetState: (key: string, state: unknown) => void
  /** conversationId → parked model context (last write wins). */
  modelContext: Record<number, McpAppPendingModelContext>
  setModelContext: (conversationId: number, context: McpAppPendingModelContext) => void
  /** Peeks without clearing (the send path clears only after the backend accepts). */
  getModelContext: (conversationId: number) => McpAppPendingModelContext | undefined
  takeModelContext: (conversationId: number) => McpAppPendingModelContext | undefined
}

export const useMcpAppsUiStore = create<McpAppsUiState>((set, get) => ({
  widgetState: {},
  setWidgetState: (key, state) =>
    set((current) => ({ widgetState: { ...current.widgetState, [key]: state } })),
  modelContext: {},
  setModelContext: (conversationId, context) =>
    set((current) => ({ modelContext: { ...current.modelContext, [conversationId]: context } })),
  getModelContext: (conversationId) => get().modelContext[conversationId],
  takeModelContext: (conversationId) => {
    const pending = get().modelContext[conversationId]
    if (pending) {
      set((current) => {
        const next = { ...current.modelContext }
        delete next[conversationId]
        return { modelContext: next }
      })
    }
    return pending
  },
}))

/** Widget-state key for one sandbox page scope. */
export function mcpWidgetStateKey(serverId: string, scopeId: string): string {
  return `${serverId}::${scopeId}`
}

/** Renders parked model context as the labeled user message that rides the next turn. */
export function formatMcpAppModelContext(pending: McpAppPendingModelContext): string | undefined {
  const parts: string[] = []
  if (pending.text) parts.push(pending.text)
  if (pending.structured !== undefined) {
    try {
      parts.push('```json\n' + JSON.stringify(pending.structured, null, 2) + '\n```')
    } catch {
      // Unserializable structured content is dropped; the text part still rides.
    }
  }
  return parts.length ? parts.join('\n\n') : undefined
}
