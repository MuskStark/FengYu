import { describe, expect, it } from 'vitest'
import type { AgentTool } from '@/services/types'
import {
  buildInputSchemaText,
  canConnect,
  compileFlowPlan,
  defaultArgsText,
  ensureStartNode,
  isStickyNode,
  isToolNode,
  makeFlowEdge,
  maxCanvasIdSequences,
  parseInputSchemaFields,
  rehydrateFlowGraph,
  serializeCanvasSnapshot,
  serializeFlowGraph,
  bindWorkflowInputReferences,
  unknownNodeReferences,
  type FlowCanvasNode,
  type FlowToolData,
  type ToolCanvasNode,
} from './flowGraph'

function tool(name: string, inputSchema = '{"type":"object","properties":{}}'): AgentTool {
  return {
    id: `t:${name}`,
    name,
    description: `${name} tool`,
    inputSchema,
    revision: 'r1',
  }
}

function toolNode(id: string, toolName: string, position: { x: number; y: number }, extra?: Partial<FlowToolData>): ToolCanvasNode {
  return {
    id,
    type: 'tool',
    position,
    data: {
      toolName,
      argsText: '{}',
      description: `${toolName} step`,
      requiresApproval: false,
      available: true,
      ...extra,
    },
  }
}

describe('serializeFlowGraph ↔ rehydrateFlowGraph round-trip', () => {
  const canvasNodes: FlowCanvasNode[] = [
    { id: 'start_1', type: 'start', position: { x: 0, y: 90 }, data: {} },
    toolNode('node_1', 'excel_read', { x: 300, y: 90 }),
    { id: 'note_1', type: 'sticky', position: { x: 600, y: 20 }, data: { content: 'check', color: 'green' } },
  ]
  const canvasEdges = [
    makeFlowEdge('start_1', 'node_1'),
    makeFlowEdge('node_1', 'note_1', 'true'),
  ]
  const catalog = [tool('excel_read')]

  it('round-trips nodes, edges, and position rounding through the wire graph', () => {
    const graph = serializeFlowGraph(
      canvasNodes.map((node) => ({ ...node, position: { x: node.position.x + 0.4, y: node.position.y - 0.4 } })),
      canvasEdges,
    )
    // Wire shape: sticky persists as the legacy `note` type, positions rounded.
    expect(graph.nodes.map((node) => node.type)).toEqual(['start', 'tool', 'note'])
    expect(graph.nodes[0].position).toEqual({ x: 0, y: 90 })
    expect(graph.edges).toEqual([
      { id: 'edge_start_1_node_1', source: 'start_1', target: 'node_1' },
      { id: 'edge_node_1_note_1_true', source: 'node_1', target: 'note_1', sourceHandle: 'true' },
    ])

    const restored = rehydrateFlowGraph(graph, catalog)
    expect(restored).not.toBeNull()
    expect(restored!.nodes.map((node) => node.type)).toEqual(['start', 'tool', 'sticky'])
    const tool = restored!.nodes[1] as ToolCanvasNode
    expect(isToolNode(tool)).toBe(true)
    expect(tool.data.toolName).toBe('excel_read')
    expect(tool.data.available).toBe(true)
    const sticky = restored!.nodes[2] as Extract<FlowCanvasNode, { type: 'sticky' }>
    expect(isStickyNode(sticky)).toBe(true)
    expect(sticky.data).toEqual({ content: 'check', color: 'green' })
    expect(restored!.edges.map((edge) => edge.id)).toEqual(['edge_start_1_node_1', 'edge_node_1_note_1_true'])

    // …and serializing the restored canvas reproduces the same wire graph.
    expect(serializeFlowGraph(restored!.nodes, restored!.edges)).toEqual(graph)
  })

  it('keeps unknown tools as unavailable placeholder nodes', () => {
    const graph = serializeFlowGraph([toolNode('node_1', 'gone_tool', { x: 0, y: 0 })], [])
    const restored = rehydrateFlowGraph(graph, [])
    expect(restored).not.toBeNull()
    const node = restored!.nodes[0] as ToolCanvasNode
    expect(isToolNode(node)).toBe(true)
    expect(node.data.available).toBe(false)
    expect(node.data.toolName).toBe('gone_tool')
  })

  it('drops edges whose endpoints are unknown', () => {
    const graph = {
      nodes: [{ id: 'node_1', type: 'tool', position: { x: 0, y: 0 }, data: { toolName: 'excel_read' } }],
      edges: [
        { id: 'e1', source: 'node_1', target: 'ghost' },
        { id: 'e2', source: 'ghost', target: 'node_1' },
      ],
    }
    const restored = rehydrateFlowGraph(graph, [])
    expect(restored!.edges).toEqual([])
  })

  it('rejects graphs without valid arrays or with duplicate ids', () => {
    expect(rehydrateFlowGraph(null, [])).toBeNull()
    expect(rehydrateFlowGraph({ nodes: [], edges: [] }, [])).not.toBeNull()
    expect(rehydrateFlowGraph({
      nodes: [
        { id: 'node_1', type: 'tool', position: { x: 0, y: 0 } },
        { id: 'node_1', type: 'tool', position: { x: 9, y: 9 } },
      ],
      edges: [],
    }, [])).toBeNull()
  })
})

describe('ensureStartNode (default start node)', () => {
  it('adds a default start node left of the executable graph', () => {
    const nodes = [toolNode('node_1', 'excel_read', { x: 300, y: 90 })]
    const withStart = ensureStartNode(nodes)
    expect(withStart).toHaveLength(2)
    expect(withStart[0].type).toBe('start')
    expect(withStart[0].id).toBe('start_1')
    expect(withStart[0].position).toEqual({ x: 0, y: 90 })
  })

  it('keeps exactly one start node (dedupes duplicates, skips when present)', () => {
    const start = { id: 'start_1', type: 'start' as const, position: { x: 0, y: 0 }, data: {} }
    expect(ensureStartNode([start])).toHaveLength(1)
    const duplicate = { id: 'start_2', type: 'start' as const, position: { x: 5, y: 5 }, data: {} }
    const deduped = ensureStartNode([start, duplicate])
    expect(deduped.filter((node) => node.type === 'start')).toHaveLength(1)
    expect(deduped[0].id).toBe('start_1')
  })

  it('avoids id collisions with authored start ids', () => {
    const start = { id: 'start_1', type: 'start' as const, position: { x: 0, y: 0 }, data: {} }
    const next = ensureStartNode([toolNode('node_1', 'excel_read', { x: 0, y: 0 }), start])
    expect(next.filter((node) => node.type === 'start').map((node) => node.id)).toEqual(['start_1'])
  })
})

describe('maxCanvasIdSequences', () => {
  it('advances past persisted node_N / note_N ids', () => {
    expect(maxCanvasIdSequences([
      { id: 'node_3' }, { id: 'node_10' }, { id: 'note_2' }, { id: 'excelEmail_n1' },
    ])).toEqual({ node: 10, note: 2 })
    expect(maxCanvasIdSequences([])).toEqual({ node: 0, note: 0 })
  })
})

describe('canConnect', () => {
  const edges = [{ id: 'e1', source: 'a', target: 'b' }]

  it('rejects self connections, duplicates, and cycles', () => {
    expect(canConnect({ source: 'a', target: 'a' }, edges)).toBe(false)
    expect(canConnect({ source: 'a', target: 'b' }, edges)).toBe(false)
    expect(canConnect({ source: 'b', target: 'a' }, edges)).toBe(false)
    expect(canConnect({ source: 'b', target: 'a' }, [])).toBe(true)
  })

  it('passes re-validations of an already stored edge and honors busy', () => {
    expect(canConnect({ id: 'e1', source: 'a', target: 'b' }, edges)).toBe(true)
    expect(canConnect({ source: 'b', target: 'c' }, edges, { busy: true })).toBe(false)
  })
})

describe('defaultArgsText', () => {
  it('seeds required inputs from schema defaults and types', () => {
    const t = tool('email_send', JSON.stringify({
      type: 'object',
      properties: {
        to: { type: 'string', default: 'a@b.c' },
        count: { type: 'integer' },
        flags: { type: 'object' },
      },
      required: ['to', 'count', 'flags', 'dryRun'],
    }))
    expect(JSON.parse(defaultArgsText(t))).toEqual({
      to: 'a@b.c',
      count: 0,
      flags: {},
      dryRun: '',
    })
  })
})

describe('compileFlowPlan', () => {
  it('compiles edges into ordered steps with dependsOn and rewritten references', () => {
    const first = toolNode('node_1', 'excel_read', { x: 0, y: 0 }, {
      argsText: JSON.stringify({ sourceFile: '{{inputs.sheet}}' }),
    })
    const second = toolNode('node_2', 'email_send', { x: 320, y: 0 }, {
      argsText: JSON.stringify({ body: '{{node.node_1.result.files[0].name}}' }),
      requiresApproval: true,
    })
    const compiled = compileFlowPlan([first, second], [{ source: 'node_1', target: 'node_2' }], {
      goal: 'ship the sheet',
    })
    expect(compiled.orderedIds).toEqual(['node_1', 'node_2'])
    expect(compiled.plan.steps.map((step) => step.toolName)).toEqual(['excel_read', 'email_send'])
    expect(compiled.plan.steps[1].dependsOn).toEqual([0])
    expect(compiled.plan.steps[1].requiresApproval).toBe(true)
    // {{inputs.x}} placeholders survive saving…
    expect((compiled.plan.steps[0].args as { sourceFile: string }).sourceFile).toBe('{{inputs.sheet}}')
    // …while node references are rewritten into step references.
    expect((compiled.plan.steps[1].args as { body: string }).body)
      .toBe('{{steps.0.result.files[0].name}}')
    expect(compiled.layout['1']).toEqual({ x: 320, y: 0 })
  })

  it('binds run inputs when requested and reports missing ones', () => {
    const node = toolNode('node_1', 'excel_read', { x: 0, y: 0 }, {
      argsText: JSON.stringify({ sourceFile: '{{inputs.sheet}}' }),
    })
    const bound = compileFlowPlan([node], [], {
      goal: 'goal {{inputs.sheet}}',
      bindInputs: true,
      inputs: { sheet: 'book.xlsx' },
    })
    expect((bound.plan.steps[0].args as { sourceFile: string }).sourceFile).toBe('book.xlsx')
    expect(bound.plan.goal).toBe('goal book.xlsx')

    expect(() => compileFlowPlan([node], [], {
      goal: 'g',
      bindInputs: true,
      inputs: {},
    })).toThrow()
  })

  it('throws on an empty canvas, a cycle, and unavailable tools', () => {
    expect(() => compileFlowPlan([], [], { goal: 'g' })).toThrow()
    const a = toolNode('node_1', 'a', { x: 0, y: 0 })
    const b = toolNode('node_2', 'b', { x: 9, y: 9 })
    expect(() => compileFlowPlan([a, b], [
      { source: 'node_1', target: 'node_2' },
      { source: 'node_2', target: 'node_1' },
    ], { goal: 'g' })).toThrow()
    expect(() => compileFlowPlan([
      toolNode('node_1', 'gone', { x: 0, y: 0 }, { available: false }),
    ], [], { goal: 'g' })).toThrow()
  })
})

describe('unknownNodeReferences', () => {
  it('lists references pointing at nodes missing from the canvas', () => {
    const node = toolNode('node_1', 'email_send', { x: 0, y: 0 }, {
      argsText: JSON.stringify({ body: '{{node.node_9.result.x}} and {{node.node_1.result.y}}' }),
    })
    expect(unknownNodeReferences([node], new Set(['node_1']))).toEqual(['node_9.result.x'])
  })
})

describe('bindWorkflowInputReferences', () => {
  it('keeps typed values for exact references and renders embedded ones to text', () => {
    const exact = bindWorkflowInputReferences('{{inputs.n}}', { n: 5 })
    expect(exact.value).toBe(5)
    expect(exact.missing).toEqual([])
    const embedded = bindWorkflowInputReferences('total {{inputs.n}} items', { n: 5 })
    expect(embedded.value).toBe('total 5 items')
    const missing = bindWorkflowInputReferences('{{inputs.gone}}', {})
    expect(missing.value).toBe('{{inputs.gone}}')
    expect(missing.missing).toEqual(['gone'])
  })
})

describe('start input schema key-value editor', () => {
  it('parses schema JSON into fields and rebuilds an equivalent schema', () => {
    const text = JSON.stringify({
      type: 'object',
      properties: {
        sheet: { type: 'string', title: 'Sheet name', default: 'A' },
        limit: { type: 'number', default: 3 },
      },
      required: ['sheet'],
    }, null, 2)
    const fields = parseInputSchemaFields(text)
    expect(fields).toEqual([
      { name: 'sheet', title: 'Sheet name', type: 'string', required: true, defaultValue: 'A' },
      { name: 'limit', title: 'Limit', type: 'number', required: false, defaultValue: '3' },
    ])
    const rebuilt = buildInputSchemaText(fields)
    expect(JSON.parse(rebuilt)).toEqual({
      type: 'object',
      properties: {
        sheet: { type: 'string', title: 'Sheet name', default: 'A' },
        limit: { type: 'number', default: 3 },
      },
      required: ['sheet'],
    })
  })

  it('drops blank rows and invalid structured defaults', () => {
    const rebuilt = buildInputSchemaText([
      { name: ' ', title: '', type: 'string', required: false, defaultValue: '' },
      { name: 'cfg', title: '', type: 'object', required: false, defaultValue: '{oops' },
      { name: 'flag', title: '', type: 'boolean', required: true, defaultValue: 'true' },
    ])
    expect(JSON.parse(rebuilt)).toEqual({
      type: 'object',
      properties: {
        cfg: { type: 'object' },
        flag: { type: 'boolean', default: true },
      },
      required: ['flag'],
    })
  })
})

describe('serializeCanvasSnapshot (dirty tracking)', () => {
  it('is stable for identical content and sensitive to edits', () => {
    const nodes: FlowCanvasNode[] = [
      { id: 'start_1', type: 'start', position: { x: 0, y: 0 }, data: {} },
      toolNode('node_1', 'excel_read', { x: 300, y: 0 }),
      { id: 'note_1', type: 'sticky', position: { x: 0, y: 200 }, data: { content: 'x', color: 'yellow' } },
    ]
    const base = serializeCanvasSnapshot({
      name: 'f', description: 'd', goal: 'g', inputSchemaText: '{}',
      nodes, edges: [{ source: 'start_1', target: 'node_1' }],
    })
    expect(serializeCanvasSnapshot({
      name: 'f', description: 'd', goal: 'g', inputSchemaText: '{}',
      nodes: nodes.map((node) => ({ ...node, position: { ...node.position } })),
      edges: [{ source: 'start_1', target: 'node_1' }],
    })).toBe(base)
    expect(serializeCanvasSnapshot({
      name: 'f2', description: 'd', goal: 'g', inputSchemaText: '{}',
      nodes, edges: [{ source: 'start_1', target: 'node_1' }],
    })).not.toBe(base)
  })
})
