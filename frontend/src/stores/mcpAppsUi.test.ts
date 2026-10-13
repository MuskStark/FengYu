import { describe, it, expect, beforeEach } from 'vitest'
import { mcpWidgetStateKey, useMcpAppsUiStore } from './mcpAppsUi'

describe('mcpAppsUi widget state store', () => {
  beforeEach(() => {
    useMcpAppsUiStore.setState({ widgetState: {} })
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
