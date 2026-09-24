import { describe, expect, it } from 'vitest'
import type { AgentTool } from '@/services/types'
import {
  collectNodeReferences,
  effectiveFlowInputSchema,
  flowTypeCompatible,
  formatNodeReference,
  orderedInputEntries,
  parseNodeReference,
  referencePathExists,
  resolveOutputPath,
  workflowDependencyClosure,
  workflowInputTree,
  workflowNodeTitle,
  workflowOutputTree,
} from './flowInspectorModel'

const tool: AgentTool = {
  id: 't1',
  name: 'json_format',
  description: 'format json',
  inputSchema: JSON.stringify({
    type: 'object',
    required: ['json'],
    properties: {
      json: { type: 'string', title: 'JSON' },
      secret_token: { type: 'string' },
    },
  }),
  outputSchema: JSON.stringify({
    type: 'object',
    properties: {
      result: { type: 'string', examples: ['x'] },
      user: {
        type: 'object',
        properties: { name: { type: 'string' }, password: { type: 'string' } },
      },
      items: { type: 'array', items: { type: 'string' } },
    },
  }),
  revision: '1',
  retrySafe: true,
  flowNode: {
    tool: 'json_format',
    label: 'JSON 格式化',
    inputs: [{ name: 'json', title: 'JSON 文本' }],
    outputs: [{ name: 'result', title: '格式化结果' }],
  },
}

describe('flow inspector model', () => {
  it('round-trips the node reference grammar', () => {
    const reference = { nodeId: 'node_2', source: 'result' as const, path: '.files[0].name' }
    const formatted = formatNodeReference(reference)
    expect(formatted).toBe('{{node.node_2.result.files[0].name}}')
    expect(parseNodeReference(formatted)).toEqual(reference)
    expect(parseNodeReference('prefix {{node.a.result}}')).toBeNull()
    expect(collectNodeReferences('a {{node.x.input.a}} b {{node.y.result}}')).toHaveLength(2)
  })

  it('builds the output tree with declared overlay and array samples', () => {
    const tree = workflowOutputTree(tool)
    const names = tree.map((field) => field.name)
    // The descriptor overlay limits the tree to declared ports (result) — schema-only
    // fields stay discoverable, and output trees intentionally keep every declared field.
    expect(names).toContain('result')
    const user = tree.find((field) => field.name === 'user')
    expect(user?.children?.map((child) => child.name)).toContain('name')
    const items = tree.find((field) => field.name === 'items')
    expect(items?.children?.[0]?.name).toBe('[0]')
    expect(referencePathExists(tree, '.items[0]')).toBe(true)
    expect(referencePathExists(tree, '.nope')).toBe(false)
  })

  it('filters sensitive inputs from the input tree', () => {
    const tree = workflowInputTree(tool)
    expect(tree.map((field) => field.name)).toContain('json')
    expect(tree.map((field) => field.name)).not.toContain('secret_token')
  })

  it('orders inputs by the descriptor overlay and merges display metadata', () => {
    const ordered = orderedInputEntries({ b: {}, a: {} }, [{ name: 'a' }, { name: 'b' }])
    expect(ordered.map(([name]) => name)).toEqual(['a', 'b'])
    const merged = effectiveFlowInputSchema(
      { type: 'string' },
      { name: 'json', title: 'JSON 文本', widget: 'textarea' },
    )
    expect(merged.title).toBe('JSON 文本')
    expect(merged['x-fengyu-multiline']).toBe(true)
  })

  it('resolves json paths and type compatibility', () => {
    expect(resolveOutputPath({ a: { b: [1, 2] } }, '.a.b[1]')).toBe(2)
    expect(flowTypeCompatible('string', 'number')).toBe(true)
    expect(flowTypeCompatible('number', 'string')).toBe(false)
    expect(flowTypeCompatible('any', 'object')).toBe(true)
  })

  it('computes the dependency closure over edges and authored references', () => {
    const nodes = [
      { id: 'a', data: { argsText: '{}' } },
      { id: 'b', data: { argsText: '{"x":"{{node.a.result.y}}"}' } },
      { id: 'c', data: { argsText: '{}' } },
    ]
    const edges = [{ source: 'c', target: 'b' }]
    const closure = workflowDependencyClosure('b', nodes, edges)
    expect(closure.has('a')).toBe(true) // authored reference
    expect(closure.has('c')).toBe(true) // structural edge
    expect(closure.has('b')).toBe(true)
  })

  it('derives node titles from author title, descriptor label, then tool name', () => {
    expect(workflowNodeTitle({ toolName: 'json_format' }, tool)).toBe('JSON 格式化')
    expect(workflowNodeTitle({ toolName: 'json_format', title: '我的节点' }, tool)).toBe('我的节点')
    expect(workflowNodeTitle({ toolName: 'json_format' }, null)).toBe('JSON format')
  })
})
