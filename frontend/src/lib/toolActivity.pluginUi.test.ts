import { describe, it, expect } from 'vitest'
import { applyToolActivity, parseToolUi, type ToolActivity } from './toolActivity'

describe('parseToolUi plugin descriptors', () => {
  it('accepts a well-formed plugin projection with defaults', () => {
    const ui = parseToolUi({
      kind: 'plugin',
      pluginId: 'dev.fengyu.smoke',
      path: 'ui/tool.html',
    })
    expect(ui).not.toBeNull()
    expect(ui && ui.kind === 'plugin' ? ui : null).toMatchObject({
      kind: 'plugin',
      pluginId: 'dev.fengyu.smoke',
      path: 'ui/tool.html',
      mode: 'card',
    })
  })

  it('keeps dialog mode and the optional title', () => {
    const ui = parseToolUi({ kind: 'plugin', pluginId: 'p', path: 'ui/d.html', mode: 'dialog', title: 'Review' })
    expect(ui && ui.kind === 'plugin' ? ui.mode : undefined).toBe('dialog')
    expect(ui && ui.kind === 'plugin' ? ui.title : undefined).toBe('Review')
  })

  it('rejects malformed plugin projections', () => {
    expect(parseToolUi({ kind: 'plugin', path: 'ui/tool.html' })).toBeNull()
    expect(parseToolUi({ kind: 'plugin', pluginId: 'p' })).toBeNull()
    expect(parseToolUi('plugin')).toBeNull()
  })

  it('falls through to the MCP shape for non-plugin payloads', () => {
    expect(parseToolUi({ serverId: 's', serverName: 'S', tool: 't', resourceUri: 'ui://a.html' }))
      .toMatchObject({ serverId: 's', resourceUri: 'ui://a.html' })
  })
})

describe('applyToolActivity plugin ui attachment', () => {
  const base = (): ToolActivity[] => [{
    id: 'call-9', name: 'smoke_echo', label: 'Smoke', status: 'running',
    detail: '', args: {}, output: '', startedAt: 1,
  }]

  it('stores the plugin descriptor from a result event', () => {
    const items = base()
    applyToolActivity(items, {
      phase: 'result', id: 'call-9', success: true, output: 'ok',
      ui: { kind: 'plugin', pluginId: 'dev.fengyu.smoke', path: 'ui/tool.html', mode: 'dialog' },
    })
    const ui = items[0].ui
    expect(ui && ui.kind === 'plugin' ? ui : null).toMatchObject({
      pluginId: 'dev.fengyu.smoke',
      path: 'ui/tool.html',
      mode: 'dialog',
    })
  })

  it('ignores a malformed plugin ui payload', () => {
    const items = base()
    applyToolActivity(items, { phase: 'result', id: 'call-9', success: true, output: 'ok', ui: { kind: 'plugin' } })
    expect(items[0].ui).toBeUndefined()
  })
})
