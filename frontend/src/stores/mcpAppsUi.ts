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
interface McpAppsUiState {
  widgetState: Record<string, unknown>
  setWidgetState: (key: string, state: unknown) => void
}

export const useMcpAppsUiStore = create<McpAppsUiState>((set) => ({
  widgetState: {},
  setWidgetState: (key, state) =>
    set((current) => ({ widgetState: { ...current.widgetState, [key]: state } })),
}))

/** Widget-state key for one sandbox page scope. */
export function mcpWidgetStateKey(serverId: string, scopeId: string): string {
  return `${serverId}::${scopeId}`
}
