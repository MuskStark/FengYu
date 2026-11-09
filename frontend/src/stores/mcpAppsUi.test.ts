import { describe, it, expect, beforeEach } from 'vitest'
import { formatMcpAppModelContext, mcpWidgetStateKey, useMcpAppsUiStore } from './mcpAppsUi'

describe('mcpAppsUi widget state store', () => {
  beforeEach(() => {
    useMcpAppsUiStore.setState({ widgetState: {}, modelContext: {} })
  })

  it('keys state by serverId::scopeId so pages cannot collide', () => {
    expect(mcpWidgetStateKey('srv', 'tool:call-1')).toBe('srv::tool:call-1')
    expect(mcpWidgetStateKey('srv', 'surface:panel')).not.toBe(mcpWidgetStateKey('srv', 'tool:call-1'))
  })

  it('stores page-committed state and re-delivers it for remounts', () => {
    useMcpAppsUiStore.getState().setWidgetState('srv::tool:call-1', { zoom: 2 })
    const state = useMcpAppsUiStore.getState().widgetState['srv::tool:call-1']
    expect(state).toEqual({ zoom: 2 })
  })

  it('keeps other scopes untouched when one commits', () => {
    useMcpAppsUiStore.getState().setWidgetState('srv::tool:a', { a: 1 })
    useMcpAppsUiStore.getState().setWidgetState('srv::tool:b', { b: 2 })
    expect(useMcpAppsUiStore.getState().widgetState['srv::tool:a']).toEqual({ a: 1 })
    expect(useMcpAppsUiStore.getState().widgetState['srv::tool:b']).toEqual({ b: 2 })
    useMcpAppsUiStore.getState().setWidgetState('srv::tool:a', { a: 9 })
    expect(useMcpAppsUiStore.getState().widgetState['srv::tool:a']).toEqual({ a: 9 })
    expect(useMcpAppsUiStore.getState().widgetState['srv::tool:b']).toEqual({ b: 2 })
  })
})

describe('mcpAppsUi parked model context (ui/update-model-context)', () => {
  beforeEach(() => {
    useMcpAppsUiStore.setState({ widgetState: {}, modelContext: {} })
  })

  it('parks per conversation with last-write-wins semantics', () => {
    const store = useMcpAppsUiStore.getState()
    store.setModelContext(7, { text: 'first' })
    store.setModelContext(7, { text: 'second', structured: { items: 2 } })
    expect(useMcpAppsUiStore.getState().getModelContext(7)).toEqual({ text: 'second', structured: { items: 2 } })
    expect(useMcpAppsUiStore.getState().getModelContext(8)).toBeUndefined()
  })

  it('takeModelContext drains exactly once (the send path clears only after acceptance)', () => {
    const store = useMcpAppsUiStore.getState()
    store.setModelContext(7, { text: 'ctx' })
    expect(store.takeModelContext(7)).toEqual({ text: 'ctx' })
    expect(useMcpAppsUiStore.getState().takeModelContext(7)).toBeUndefined()
    expect(useMcpAppsUiStore.getState().modelContext[7]).toBeUndefined()
  })

  it('formats text plus fenced structured content, dropping nothing serializable', () => {
    expect(formatMcpAppModelContext({ text: 'cart: 3 items' })).toBe('cart: 3 items')
    const rendered = formatMcpAppModelContext({ text: 'cart', structured: { items: 3 } })
    expect(rendered).toContain('cart')
    expect(rendered).toContain('```json')
    expect(rendered).toContain('"items": 3')
    expect(formatMcpAppModelContext({})).toBeUndefined()
  })
})
