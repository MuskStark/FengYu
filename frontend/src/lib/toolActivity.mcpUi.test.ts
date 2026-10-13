import { describe, it, expect } from 'vitest'
import { applyToolActivity, parseMcpToolUi, type ToolActivity } from './toolActivity'

describe('parseMcpToolUi', () => {
  it('accepts a well-formed projection and keeps optional fields', () => {
    const ui = parseMcpToolUi({
      serverId: 'srv-1',
      serverName: 'Server',
      tool: 'widget',
      resourceUri: 'ui://widget/main.html',
      preferredDisplayMode: 'inline',
      prefersBorder: true,
      showInline: true,
      structuredContent: '{"answer":42}',
      content: '[{"type":"text","text":"hi"}]',
      truncated: true,
      truncatedBytes: 66560,
    })
    expect(ui).not.toBeNull()
    expect(ui?.serverId).toBe('srv-1')
    expect(ui?.structuredContent).toBe('{"answer":42}')
    expect(ui?.truncated).toBe(true)
    expect(ui?.preferredDisplayMode).toBe('inline')
  })

  it('rejects non-objects, missing ids, and non-ui:// resource URIs', () => {
    expect(parseMcpToolUi(null)).toBeNull()
    expect(parseMcpToolUi('ui://x')).toBeNull()
    expect(parseMcpToolUi({ serverId: '', resourceUri: 'ui://a.html' })).toBeNull()
    expect(parseMcpToolUi({ serverId: 's', resourceUri: 'https://not-ui' })).toBeNull()
  })

  it('ignores malformed optional fields instead of failing', () => {
    const ui = parseMcpToolUi({
      serverId: 's',
      resourceUri: 'ui://a.html',
      preferredDisplayMode: 'fullscreen-max',
      truncatedBytes: 'many',
    })
    expect(ui?.preferredDisplayMode).toBeUndefined()
    expect(ui?.truncatedBytes).toBeUndefined()
    expect(ui?.isError).toBeUndefined()
  })
})

describe('applyToolActivity ui attachment', () => {
  const base = (): ToolActivity[] => [{
    id: 'call-1', name: 'mcp__srv__widget', label: 'Widget', status: 'running',
    detail: '', args: {}, output: '', startedAt: 1,
  }]

  it('stores the render descriptor from a result event', () => {
    const items = base()
    applyToolActivity(items, {
      phase: 'result', id: 'call-1', success: true, output: 'ok',
      ui: { serverId: 'srv', serverName: 'S', tool: 'widget', resourceUri: 'ui://w.html' },
    })
    expect(items[0].status).toBe('completed')
    expect(items[0].ui?.resourceUri).toBe('ui://w.html')
  })

  it('keeps the row unchanged when no ui payload is present', () => {
    const items = base()
    applyToolActivity(items, { phase: 'result', id: 'call-1', success: true, output: 'ok' })
    expect(items[0].ui).toBeUndefined()
  })

  it('ignores a malformed ui payload', () => {
    const items = base()
    applyToolActivity(items, {
      phase: 'result', id: 'call-1', success: true, output: 'ok', ui: { resourceUri: 'ui://no-server' },
    })
    expect(items[0].ui).toBeUndefined()
  })
})
