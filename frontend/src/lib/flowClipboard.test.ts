import { describe, expect, it } from 'vitest'
import { MarkerType } from '@xyflow/react'
import {
  buildFlowClipboard,
  materializeFlowClipboard,
  parseFlowClipboard,
  rewriteNodeReferences,
} from './flowClipboard'
import { makeFlowEdge, type FlowCanvasNode } from './flowGraph'

const toolNode = (id: string, argsText: string): FlowCanvasNode => ({
  id,
  type: 'tool',
  position: { x: 10, y: 20 },
  data: {
    toolName: 'excel_read',
    argsText,
    description: '',
    requiresApproval: false,
    available: true,
  },
})

const sticky: FlowCanvasNode = {
  id: 'note_1',
  type: 'sticky',
  position: { x: 0, y: 0 },
  data: { content: 'note', color: 'blue' },
}

const makeEdge = (source: string, target: string) => makeFlowEdge(source, target)

describe('buildFlowClipboard / parseFlowClipboard', () => {
  it('serializes tool+sticky selections and internal edges only', () => {
    const nodes = [toolNode('node_1', '{}'), toolNode('node_2', '{"a":"{{node.node_1.result.x}}"}'), sticky]
    const edges = [
      makeEdge('node_1', 'node_2'),
      makeEdge('node_2', 'node_9'), // leaves the selection — excluded
    ]
    const payload = buildFlowClipboard(nodes, edges, new Set(['node_1', 'node_2', 'note_1']))
    expect(payload?.nodes.map((node) => node.id)).toEqual(['node_1', 'node_2', 'note_1'])
    expect(payload?.edges).toEqual([{ source: 'node_1', target: 'node_2', sourceHandle: null }])
    expect(parseFlowClipboard(JSON.stringify(payload))).toEqual(payload)
  })

  it('refuses start nodes and empty selections', () => {
    const start: FlowCanvasNode = { id: 'start_1', type: 'start', position: { x: 0, y: 0 }, data: {} }
    expect(buildFlowClipboard([start], [], new Set(['start_1']))).toBeNull()
    expect(buildFlowClipboard([toolNode('node_1', '{}')], [], new Set())).toBeNull()
  })

  it('rejects foreign payloads', () => {
    expect(parseFlowClipboard('{"kind":"other"}')).toBeNull()
    expect(parseFlowClipboard('not json')).toBeNull()
    expect(parseFlowClipboard(null)).toBeNull()
  })
})

describe('rewriteNodeReferences', () => {
  it('rewrites only mapped ids, leaving external references intact', () => {
    const idMap = new Map([['node_1', 'node_7']])
    expect(rewriteNodeReferences('{"a":"{{node.node_1.result.x}}","b":"{{node.node_5.result.y}}"}', idMap))
      .toBe('{"a":"{{node.node_7.result.x}}","b":"{{node.node_5.result.y}}"}')
  })
})

describe('materializeFlowClipboard', () => {
  it('mints fresh ids, shifts positions, rewrites internal references and edges', () => {
    const payload = {
      kind: 'fengyu-flow-clipboard' as const,
      version: 1 as const,
      nodes: [
        { id: 'node_1', type: 'tool' as const, position: { x: 10, y: 20 }, data: toolNode('node_1', '{}').data },
        { id: 'node_2', type: 'tool' as const, position: { x: 300, y: 20 }, data: toolNode('node_2', '{"a":"{{node.node_1.result.x}}","ext":"{{node.node_9.result.y}}"}').data },
      ],
      edges: [{ source: 'node_1', target: 'node_2', sourceHandle: null }],
    }
    let sequence = 0
    const pasted = materializeFlowClipboard(payload, () => `node_${++sequence + 10}`, { x: 40, y: 40 }, makeEdge)
    expect(pasted?.nodes.map((node) => node.id)).toEqual(['node_11', 'node_12'])
    expect(pasted?.nodes[0].position).toEqual({ x: 50, y: 60 })
    const second = pasted?.nodes[1]
    expect(second && second.type === 'tool' && JSON.parse(second.data.argsText)).toEqual({
      a: '{{node.node_11.result.x}}',
      ext: '{{node.node_9.result.y}}',
    })
    expect(pasted?.edges).toEqual([expect.objectContaining({ source: 'node_11', target: 'node_12', markerEnd: { type: MarkerType.ArrowClosed } })])
  })

  it('drops runtime captures from pasted tool nodes', () => {
    const payload = {
      kind: 'fengyu-flow-clipboard' as const,
      version: 1 as const,
      nodes: [{
        id: 'node_1', type: 'tool' as const, position: { x: 0, y: 0 },
        data: { ...toolNode('node_1', '{}').data, lastRun: '{"x":1}', pinnedOutput: 'pinned' },
      }],
      edges: [],
    }
    const pasted = materializeFlowClipboard(payload, () => 'node_2', { x: 0, y: 0 }, makeEdge)
    const node = pasted?.nodes[0]
    expect(node && node.type === 'tool' && node.data.lastRun).toBeUndefined()
    expect(node && node.type === 'tool' && node.data.pinnedOutput).toBeUndefined()
  })

  it('rejects payloads whose tool data lost its toolName', () => {
    const payload = {
      kind: 'fengyu-flow-clipboard' as const,
      version: 1 as const,
      nodes: [{ id: 'n', type: 'tool' as const, position: { x: 0, y: 0 }, data: {} }],
      edges: [],
    }
    expect(materializeFlowClipboard(payload, () => 'node_2', { x: 0, y: 0 }, makeEdge)).toBeNull()
  })
})
