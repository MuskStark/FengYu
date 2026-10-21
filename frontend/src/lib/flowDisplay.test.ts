import { describe, expect, it } from 'vitest'
import {
  humanizeToolName,
  humanizeWorkflowField,
  workflowNodeColor,
  workflowToolCategory,
  WORKFLOW_TOOL_NODE_COLORS,
} from './flowDisplay'

describe('workflowToolCategory (task-verb regrouping)', () => {
  const cases: Array<[name: string, pluginId: string | undefined, expected: string]> = [
    // Host flow nodes.
    ['flow_llm', undefined, 'generate'],
    ['flow_if', undefined, 'judge'],
    // Plugin families resolve by pluginId even when the tool name carries no keyword.
    ['email_send_batch', 'fan.summer.email', 'send'],
    ['prepare', 'fan.summer.email', 'send'],
    ['page_read', 'fan.summer.browser', 'fetch'],
    ['excel_complex_config', 'fan.summer.excel', 'files'],
    ['run', 'fan.summer.python', 'generate'],
    ['skill_search', 'fan.summer.skills', 'generate'],
    // Name keywords decide everything else — including MCP server tools (pluginId "mcp").
    ['weather_search', 'mcp', 'fetch'],
    ['send_message', 'mcp', 'send'],
    ['read_file', 'mcp', 'fetch'],
    ['json_format', undefined, 'files'],
    ['translate_text', 'mcp', 'generate'],
    // Unknown verbs fall through to other.
    ['teleport_widget', 'mcp', 'other'],
  ]

  it.each(cases)('%s (%s) → %s', (name, pluginId, expected) => {
    expect(workflowToolCategory({ name, pluginId })).toBe(expected)
  })

  it('classifies word boundaries, not substrings', () => {
    // "sender" must not trip the send rule on a bare substring; word-boundary regex holds.
    expect(workflowToolCategory({ name: 'keyword_sender_check', pluginId: undefined })).not.toBe('send')
    expect(workflowToolCategory({ name: 'send_it', pluginId: undefined })).toBe('send')
  })

  it('keeps every verb category colorable', () => {
    for (const category of ['generate', 'judge', 'fetch', 'send', 'files', 'other']) {
      expect(WORKFLOW_TOOL_NODE_COLORS[category]).toBeTruthy()
    }
  })
})

describe('workflowNodeColor', () => {
  it('prefers the flowNode descriptor color', () => {
    expect(workflowNodeColor({ name: 'x', pluginId: undefined, flowNode: { color: '#123456' } }))
      .toBe('#123456')
  })

  it('falls back to the task-verb color then the default', () => {
    expect(workflowNodeColor({ name: 'flow_llm', pluginId: undefined }))
      .toBe(WORKFLOW_TOOL_NODE_COLORS.generate)
    expect(workflowNodeColor({ name: 'weird', pluginId: undefined, flowNode: {} }))
      .toBe(WORKFLOW_TOOL_NODE_COLORS.other)
  })
})

describe('humanize helpers', () => {
  it('splits camelCase and snake_case into words', () => {
    expect(humanizeWorkflowField('recipientTagIds')).toBe('Recipient Tag Ids')
    expect(humanizeWorkflowField('output_dir')).toBe('Output dir')
  })

  it('title-cases tool names', () => {
    expect(humanizeToolName('flow_llm')).toBe('Flow Llm')
  })
})
