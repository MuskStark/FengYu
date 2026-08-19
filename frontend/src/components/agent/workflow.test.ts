import { describe, expect, it } from 'vitest'
import {
  applyCanvasEdgeChanges,
  applyCanvasNodeChanges,
  bindWorkflowInputReferences,
  canvasLayoutByStepIndex,
  flattenWorkflowOutputFields,
  flowTypeCompatible,
  formatNodeReference,
  humanizeWorkflowField,
  humanizeWorkflowToolName,
  maxCanvasIdSequences,
  missingRequiredNodeInputs,
  missingRequiredWorkflowInputs,
  parseNodeReference,
  rehydrateFlowGraph,
  reconcileWorkflowArguments,
  referencePathExists,
  serializeFlowGraph,
  serializeCanvasState,
  topologicallySortWorkflowNodes,
  undeclaredWorkflowInputReferences,
  unknownNodeReferences,
  workflowInputSummaries,
  workflowNodeTitle,
  workflowOutputSummaries,
  workflowOutputTree,
  workflowToolCategory,
  wouldCreateCycle,
  type FlowCanvasNodeBase,
  type WorkflowFlowNode,
} from './workflow'

describe('workflow graph helpers', () => {
  const nodes = [
    { id: 'b', position: { x: 300, y: 0 } },
    { id: 'a', position: { x: 0, y: 0 } },
    { id: 'c', position: { x: 600, y: 0 } },
  ]

  it('sorts connected nodes in dependency order', () => {
    const sorted = topologicallySortWorkflowNodes(nodes, [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
    ])

    expect(sorted?.map((node) => node.id)).toEqual(['a', 'b', 'c'])
  })

  it('returns null for a cyclic workflow', () => {
    const sorted = topologicallySortWorkflowNodes(nodes, [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'a' },
    ])

    expect(sorted).toBeNull()
  })

  it('rejects an edge that would close a cycle', () => {
    expect(wouldCreateCycle([
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
    ], 'c', 'a')).toBe(true)
    expect(wouldCreateCycle([
      { source: 'a', target: 'b' },
    ], 'b', 'c')).toBe(false)
  })
})

describe('reconcileWorkflowArguments', () => {
  it('preserves authored values and seeds newly required inputs', () => {
    const result = JSON.parse(reconcileWorkflowArguments(
      '{"existing":"kept","legacy":true}',
      '{"type":"object","properties":{"existing":{"type":"string"},"count":{"type":"integer","default":3}},"required":["existing","count"]}',
    ))
    expect(result).toEqual({ existing: 'kept', legacy: true, count: 3 })
  })

  it('does not destroy invalid advanced JSON during a catalog refresh', () => {
    expect(reconcileWorkflowArguments('{unfinished', '{"type":"object"}')).toBe('{unfinished')
  })
})

describe('workflow schema presentation', () => {
  const inputSchema = JSON.stringify({
    type: 'object',
    properties: {
      sourceFile: { type: 'string', description: 'Workbook path' },
      split_mode: { type: 'string', title: 'Split method', enum: ['sheet', 'column'] },
      sendEmail: { type: 'boolean' },
    },
    required: ['sourceFile', 'split_mode'],
  })

  it('turns technical names into readable labels', () => {
    expect(humanizeWorkflowField('sourceFile')).toBe('Source File')
    expect(humanizeWorkflowField('split_mode')).toBe('Split mode')
    expect(humanizeWorkflowField('apiUrl')).toBe('API URL')
    expect(humanizeWorkflowToolName('browser_navigate')).toBe('Browser Navigate')
    expect(workflowToolCategory({ name: 'browser_navigate', pluginId: null })).toBe('browser')
  })

  it('summarizes manual values, workflow inputs, and node outputs', () => {
    const summaries = workflowInputSummaries(inputSchema, JSON.stringify({
      sourceFile: '{{inputs.workbookPath}}',
      split_mode: '{{node.node_2.result.mode}}',
      sendEmail: false,
    }))
    expect(summaries).toEqual([
      expect.objectContaining({ label: 'Source File', source: 'workflow', configured: true, value: 'Workbook Path' }),
      expect.objectContaining({ label: 'Split method', source: 'node', configured: true, value: 'node_2 · Mode' }),
      expect.objectContaining({ label: 'Send Email', source: 'manual', configured: true, value: 'false' }),
    ])
  })

  it('reports missing required values without treating false or zero as empty', () => {
    expect(missingRequiredWorkflowInputs(inputSchema, '{"sourceFile":"","split_mode":"sheet","sendEmail":false}'))
      .toEqual(['sourceFile'])
  })

  it('exposes user-facing output labels while hiding protocol fields', () => {
    const outputs = workflowOutputSummaries(JSON.stringify({
      type: 'object',
      properties: {
        success: { type: 'boolean' },
        summary: { type: 'string' },
        fileCount: { type: 'integer' },
        output_path: { type: 'string', title: 'Saved file' },
      },
    }))
    expect(outputs).toEqual([
      { name: 'fileCount', label: 'File Count', type: 'integer' },
      { name: 'output_path', label: 'Saved file', type: 'string' },
    ])
  })
})

describe('save-time input reference checks', () => {
  const schema = JSON.stringify({
    type: 'object',
    properties: { workbookPath: { type: 'string' }, options: { type: 'object' } },
  })

  it('flags references to inputs the schema never declares', () => {
    const missing = undeclaredWorkflowInputReferences(schema, 'Format {{inputs.reportTitle}}', [
      { args: { path: '{{inputs.workbookPath}}', nested: { mode: '{{inputs.mode}}' } } },
      { args: null },
    ])
    expect(missing).toEqual(['mode', 'reportTitle'])
  })

  it('accepts dotted paths into declared object inputs', () => {
    expect(undeclaredWorkflowInputReferences(schema, '{{inputs.options.depth}}', [])).toEqual([])
  })
})

describe('canvas dirty snapshots', () => {
  const node = (id: string, name: string, x = 0, y = 0): WorkflowFlowNode => ({
    id,
    type: 'tool',
    position: { x, y },
    data: {
      tool: { id: name, name, description: '', inputSchema: '{}', revision: '1' },
      argsText: '{}',
      description: '',
      requiresApproval: false,
      available: true,
    },
  })

  const state = () => ({
    name: 'Daily report',
    description: 'sums things',
    goal: 'sum',
    inputSchemaText: '{"type":"object","properties":{}}',
    nodes: [node('node_1', 'json_format', 12, 34), node('node_2', 'json_format', 300, 34)],
    edges: [{ source: 'node_1', target: 'node_2' }],
  })

  it('is stable across node id regeneration but reacts to edits', () => {
    const before = serializeCanvasState(state())
    // Reload regenerates ids (node_3/node_4) but keeps graph shape and positions.
    const reloaded = state()
    reloaded.nodes = [node('node_3', 'json_format', 12, 34), node('node_4', 'json_format', 300, 34)]
    reloaded.edges = [{ source: 'node_3', target: 'node_4' }]
    expect(serializeCanvasState(reloaded)).toBe(before)

    const edited = state()
    edited.nodes[1].position = { x: 400, y: 34 }
    expect(serializeCanvasState(edited)).not.toBe(before)
  })

  it('ignores schema formatting noise but not schema changes', () => {
    const before = serializeCanvasState(state())
    const reformatted = state()
    reformatted.inputSchemaText = '{\n  "type": "object",\n  "properties": {}\n}'
    expect(serializeCanvasState(reformatted)).toBe(before)
    const changed = state()
    changed.inputSchemaText = '{"type":"object","properties":{"q":{"type":"string"}}}'
    expect(serializeCanvasState(changed)).not.toBe(before)
  })
})

describe('canvas layout persistence', () => {
  it('keys rounded positions by compiled step index', () => {
    expect(canvasLayoutByStepIndex([
      { position: { x: 12.4, y: -8.6 } },
      { position: { x: 300, y: 48 } },
    ])).toEqual({
      0: { x: 12, y: -9 },
      1: { x: 300, y: 48 },
    })
  })
})

describe('bindWorkflowInputReferences', () => {
  it('binds exact references to typed values and keeps numbers typed', () => {
    const { value, missing } = bindWorkflowInputReferences(
      {
        action: 'add',
        accountId: '{{inputs.accountId}}',
        entries: [{ sheetName: '{{inputs.sheetName}}', headerIndex: '{{inputs.headerRow}}' }],
      },
      { accountId: 3, sheetName: 'Sales', headerRow: 1 },
    )

    expect(missing).toEqual([])
    expect(value).toEqual({
      action: 'add',
      accountId: 3,
      entries: [{ sheetName: 'Sales', headerIndex: 1 }],
    })
  })

  it('renders embedded references into longer strings', () => {
    const { value } = bindWorkflowInputReferences(
      { subject: 'Report for {{inputs.month}}', note: 'ids: {{inputs.tags}}' },
      { month: 'March', tags: [1, 2] },
    )

    expect(value).toEqual({ subject: 'Report for March', note: 'ids: [1,2]' })
  })

  it('reports referenced inputs missing from the values and leaves placeholders', () => {
    const { value, missing } = bindWorkflowInputReferences(
      { filePath: '{{inputs.workbook}}' },
      {},
    )

    expect(missing).toEqual(['workbook'])
    expect(value).toEqual({ filePath: '{{inputs.workbook}}' })
  })
})

describe('flattenWorkflowOutputFields', () => {
  it('flattens one nesting level into dotted paths for the output picker', () => {
    const fields = flattenWorkflowOutputFields(JSON.stringify({
      type: 'object',
      properties: {
        success: { type: 'boolean' },
        summary: { type: 'string' },
        confirmation: {
          type: 'object',
          properties: { confirmationId: { type: 'string' }, expiresAt: { type: 'string' } },
        },
        jobId: { type: 'string' },
      },
    }))

    expect(fields.map(([name]) => name)).toEqual([
      'confirmation',
      'confirmation.confirmationId',
      'confirmation.expiresAt',
      'jobId',
    ])
  })

  it('survives malformed schema text', () => {
    expect(flattenWorkflowOutputFields('not json')).toEqual([])
  })
})

// ── descriptor v2: types, references, recursive output tree ────────────────

describe('flow type system (descriptor v2)', () => {
  it('treats any as compatible with everything and folds integer into number', () => {
    expect(flowTypeCompatible('string', 'string')).toBe(true)
    expect(flowTypeCompatible(undefined, 'object')).toBe(true)
    expect(flowTypeCompatible('object', undefined)).toBe(true)
    expect(flowTypeCompatible('integer', 'number')).toBe(true)
    expect(flowTypeCompatible('string', 'number')).toBe(true) // renders to text
    expect(flowTypeCompatible('number', 'string')).toBe(false)
    expect(flowTypeCompatible('string', 'object')).toBe(false)
  })
})

describe('node references with array indexes', () => {
  it('parses exact references including [N] segments', () => {
    expect(parseNodeReference('{{node.node_2.result}}')).toEqual({ nodeId: 'node_2', path: '' })
    expect(parseNodeReference('{{node.node_2.result.files[0].name}}'))
      .toEqual({ nodeId: 'node_2', path: '.files[0].name' })
    expect(parseNodeReference('{{inputs.sheet}}')).toBeNull()
    expect(parseNodeReference('prefix {{node.n.result}} suffix')).toBeNull()
  })

  it('round-trips through formatNodeReference', () => {
    expect(formatNodeReference({ nodeId: 'a_1', path: '.files[2]' }))
      .toBe('{{node.a_1.result.files[2]}}')
  })
})

describe('workflowOutputTree', () => {
  const node = {
    data: {
      descriptor: {
        tool: 'excel_execute',
        outputs: [
          { name: 'summary', title: '汇总', type: 'string' as const, examples: ['3 files'] },
          {
            name: 'files', title: '输出文件', type: 'array' as const, items: { type: 'string' as const },
            examples: [['/share/a.xlsx']],
          },
          {
            name: 'confirmation', title: '确认单', type: 'object' as const,
            properties: {
              confirmationId: { type: 'string' as const, title: '凭据 ID' },
            },
          },
        ],
      },
      tool: {
        outputSchema: JSON.stringify({
          type: 'object',
          properties: {
            summary: { type: 'string' },
            confirmation: {
              type: 'object',
              properties: { confirmationId: { type: 'string' }, expiresAt: { type: 'string' } },
            },
            extra: { type: 'number', title: 'Schema-only' },
          },
        }),
      },
    },
  }

  it('builds a recursive tree: declared outputs win, schema adds undeclared fields', () => {
    const tree = workflowOutputTree(node)
    const names = tree.map((field) => field.path)
    expect(names).toEqual(['.summary', '.files', '.confirmation', '.extra'])

    const files = tree.find((field) => field.name === 'files')!
    expect(files.type).toBe('array')
    expect(files.examples).toEqual([['/share/a.xlsx']])
    // array items surface as an [0] child so index paths are discoverable
    expect(files.children?.map((child) => child.path)).toEqual(['.files[0]'])

    const confirmation = tree.find((field) => field.name === 'confirmation')!
    // declared nested field + schema-only sibling both present
    expect(confirmation.children?.map((child) => child.name).sort())
      .toEqual(['confirmationId', 'expiresAt'])
  })

  it('validates reference paths against the tree', () => {
    const tree = workflowOutputTree(node)
    expect(referencePathExists(tree, '.files[0]')).toBe(true)
    expect(referencePathExists(tree, '.confirmation.confirmationId')).toBe(true)
    expect(referencePathExists(tree, '')).toBe(true)
    expect(referencePathExists(tree, '.fil')).toBe(false)
    expect(referencePathExists(tree, '.files[0].name')).toBe(false) // items are strings
  })
})

describe('unknownNodeReferences (save-time validation)', () => {
  const node = (id: string, argsText: string): WorkflowFlowNode => ({
    id,
    type: 'tool',
    position: { x: 0, y: 0 },
    data: {
      tool: { id: 't', name: 'tool_a', description: '', inputSchema: '{}', revision: '1' },
      descriptor: {
        tool: 'tool_a',
        outputs: [{ name: 'files', title: 'Files', type: 'array', items: { type: 'string' } }],
      },
      argsText,
      description: '',
      requiresApproval: false,
      available: true,
    },
  })

  it('flags references to missing nodes and unknown output fields', () => {
    const a = node('node_1', '{}')
    const b = node('node_2', JSON.stringify({
      good: '{{node.node_1.result.files[0]}}',
      badField: '{{node.node_1.result.nope}}',
      badNode: '{{node.node_9.result}}',
    }))
    const unknown = unknownNodeReferences([a, b])
    expect(unknown).toEqual([
      { fromNodeId: 'node_2', nodeId: 'node_1', path: '.nope', reason: 'unknown-field' },
      { fromNodeId: 'node_2', nodeId: 'node_9', path: '', reason: 'unknown-node' },
    ])
  })
})

describe('descriptor v2 node metadata round-trip', () => {
  const tool = {
    id: 'builtin:excel_execute',
    pluginId: 'fan.summer.excel',
    name: 'excel_execute',
    description: 'Execute an Excel operation',
    inputSchema: '{"type":"object","properties":{"filePath":{"type":"string"}},"required":["filePath"]}',
    outputSchema: '{"type":"object","properties":{"summary":{"type":"string"}}}',
    revision: 'r1',
  }

  it('persists title/pin/lastRun through serialize → rehydrate', () => {
    const node: WorkflowFlowNode = {
      id: 'node_1',
      type: 'tool',
      position: { x: 10, y: 20 },
      data: {
        tool,
        argsText: '{}',
        description: '',
        requiresApproval: false,
        available: true,
        title: '拆分月报',
        pinnedOutput: '{"summary":"pinned"}',
        lastRun: '{"summary":"ran"}',
        lastRunAt: '2026-08-19T10:00:00Z',
      },
    }
    const restored = rehydrateFlowGraph(serializeFlowGraph([node], []), [tool])
    const data = restored?.nodes[0]
    expect(data?.type).toBe('tool')
    if (data?.type === 'tool') {
      expect(data.data.title).toBe('拆分月报')
      expect(data.data.pinnedOutput).toBe('{"summary":"pinned"}')
      expect(data.data.lastRun).toBe('{"summary":"ran"}')
      expect(data.data.lastRunAt).toBe('2026-08-19T10:00:00Z')
    }
  })

  it('counts title and pins as edits but NOT automatic last-run captures', () => {
    const base = {
      name: 'F', description: '', goal: 'g',
      inputSchemaText: '{}', nodes: [], edges: [],
    }
    const node = (extra: Record<string, unknown>) => ({
      id: 'node_1', type: 'tool' as const, position: { x: 0, y: 0 },
      data: {
        tool, argsText: '{}', description: '', requiresApproval: false, available: true,
        ...extra,
      },
    })
    const before = serializeCanvasState({ ...base, nodes: [node({})] })
    // A finished run writing lastRun must not trip the unsaved-changes guard.
    expect(serializeCanvasState({ ...base, nodes: [node({ lastRun: '{"x":1}' })] })).toBe(before)
    expect(serializeCanvasState({ ...base, nodes: [node({ title: '改名' })] })).not.toBe(before)
    expect(serializeCanvasState({ ...base, nodes: [node({ pinnedOutput: '{}' })] })).not.toBe(before)
  })
})

describe('missingRequiredNodeInputs (schema + descriptor required)', () => {
  it('unions the tool schema required list with descriptor required flags', () => {
    const node = {
      data: {
        tool: {
          name: 'email_send_batch',
          inputSchema: '{"type":"object","properties":{"accountId":{"type":"string"}},"required":["accountId"]}',
        },
        descriptor: {
          tool: 'email_send_batch',
          inputs: [
            { name: 'accountId', widget: 'text' as const },
            { name: 'inputDirectory', widget: 'text' as const, required: true },
          ],
        },
        argsText: '{"accountId":"acc_1"}',
      },
    }
    expect(missingRequiredNodeInputs(node)).toEqual(['inputDirectory'])
  })
})

describe('workflowNodeTitle', () => {
  it('prefers author title, then declared label, then humanized tool name', () => {
    expect(workflowNodeTitle({ data: { title: '改名', tool: { name: 'json_format' } } })).toBe('改名')
    expect(workflowNodeTitle({
      data: { descriptor: { tool: 'json_format', label: 'JSON 格式化' }, tool: { name: 'json_format' } },
    })).toBe('JSON 格式化')
    expect(workflowNodeTitle({ data: { tool: { name: 'json_format' } } })).toBe('JSON Format')
  })
})

describe('flow graph round-trip (Flowise-style persistence)', () => {
  const tool = {
    id: 'builtin:excel_execute',
    pluginId: 'fan.summer.excel',
    name: 'excel_execute',
    description: 'Execute an Excel operation',
    inputSchema: '{"type":"object","properties":{"filePath":{"type":"string"}},"required":["filePath"]}',
    outputSchema: '{"type":"object","properties":{"summary":{"type":"string"},"rows":{"type":"integer"}}}',
    revision: 'r1',
  }
  const canvasNodes = [
    {
      id: 'node_1',
      type: 'tool' as const,
      position: { x: 10.6, y: 20.2 },
      data: {
        tool,
        argsText: '{"filePath":"{{inputs.file}}"}',
        description: 'Split the workbook',
        requiresApproval: false,
        available: true,
      },
    },
    {
      id: 'note_1',
      type: 'note' as const,
      position: { x: 300, y: 90 },
      data: { content: 'check recipients', color: 'green' as const },
    },
  ]
  const edges = [{ id: 'e1', source: 'node_1', target: 'node_1' }]

  it('serializes tool nodes by name and notes by content', () => {
    const graph = serializeFlowGraph(canvasNodes, edges)

    expect(graph.nodes).toHaveLength(2)
    expect(graph.nodes[0]).toEqual({
      id: 'node_1',
      type: 'tool',
      position: { x: 11, y: 20 },
      data: {
        toolName: 'excel_execute',
        argsText: '{"filePath":"{{inputs.file}}"}',
        description: 'Split the workbook',
        requiresApproval: false,
      },
    })
    expect(graph.nodes[1]).toEqual({
      id: 'note_1',
      type: 'note',
      position: { x: 300, y: 90 },
      data: { content: 'check recipients', color: 'green' },
    })
    expect(graph.edges).toEqual([{ id: 'e1', source: 'node_1', target: 'node_1' }])
  })

  it('rehydrates tools from the live catalog and keeps node ids stable', () => {
    const graph = serializeFlowGraph(canvasNodes, edges)
    const restored = rehydrateFlowGraph(graph, [tool])

    expect(restored?.nodes.map((node) => node.id)).toEqual(['node_1', 'note_1'])
    const toolNode = restored?.nodes[0]
    expect(toolNode?.type).toBe('tool')
    if (toolNode?.type === 'tool') {
      expect(toolNode.data.tool.name).toBe('excel_execute')
      expect(toolNode.data.available).toBe(true)
      expect(toolNode.data.argsText).toBe('{"filePath":"{{inputs.file}}"}')
    }
    const noteNode = restored?.nodes[1]
    if (noteNode?.type === 'note') {
      expect(noteNode.data.content).toBe('check recipients')
      expect(noteNode.data.color).toBe('green')
    }
  })

  it('marks nodes whose tool vanished from the catalog as unavailable placeholders', () => {
    const graph = serializeFlowGraph(canvasNodes, edges)
    const restored = rehydrateFlowGraph(graph, [])
    const toolNode = restored?.nodes[0]
    expect(toolNode?.type).toBe('tool')
    if (toolNode?.type === 'tool') {
      expect(toolNode.data.available).toBe(false)
      expect(toolNode.data.tool.id).toBe('missing:excel_execute')
    }
  })

  it('advances id sequences past persisted numeric suffixes (no collision after reload)', () => {
    // A saved flow keeps its authored node_3/note_2 ids; the next minted ids must
    // continue past them instead of restarting at node_1/note_1.
    const restored = rehydrateFlowGraph({
      nodes: [
        { ...canvasNodes[0], id: 'node_3' },
        { ...canvasNodes[1], id: 'note_2' },
      ],
      edges: [],
    }, [tool])
    const sequences = maxCanvasIdSequences(restored!.nodes)
    expect(`node_${sequences.node + 1}`).toBe('node_4')
    expect(`note_${sequences.note + 1}`).toBe('note_3')
  })

  it('ignores ids that cannot collide with freshly minted node_N/note_N ids', () => {
    expect(maxCanvasIdSequences([
      { id: 'node_copy' },
      { id: 'node_10x' },
      { id: 'excelEmail_n1' },
    ])).toEqual({ node: 0, note: 0 })
    expect(maxCanvasIdSequences([{ id: 'node_2' }, { id: 'node_10' }, { id: 'note_1' }]))
      .toEqual({ node: 10, note: 1 })
  })

  it('drops edges that reference unknown nodes', () => {
    const restored = rehydrateFlowGraph({
      nodes: [{ id: 'n1', type: 'tool', position: { x: 0, y: 0 }, data: { toolName: 't' } }],
      edges: [{ id: 'e', source: 'n1', target: 'ghost' }],
    }, [tool])
    expect(restored?.edges).toEqual([])
  })

  it('rejects malformed graphs', () => {
    expect(rehydrateFlowGraph(null, [])).toBeNull()
    expect(rehydrateFlowGraph({} as never, [])).toBeNull()
  })
})

describe('serializeCanvasState with sticky notes', () => {
  it('includes note content in the dirty-check snapshot', () => {
    const base = {
      name: 'Flow',
      description: '',
      goal: 'g',
      inputSchemaText: '{}',
      nodes: [],
      edges: [],
    }
    const without = serializeCanvasState(base)
    const withNote = serializeCanvasState({
      ...base,
      notes: [{ content: 'remember this', color: 'yellow', position: { x: 5, y: 6 } }],
    })
    expect(without).not.toBe(withNote)
    expect(JSON.parse(withNote).notes).toEqual([
      { content: 'remember this', color: 'yellow', x: 5, y: 6 },
    ])
  })
})

describe('canvas change appliers (reactflow controlled mode)', () => {
  const nodes: FlowCanvasNodeBase[] = [
    { id: 'n1', position: { x: 0, y: 0 } },
    { id: 'n2', position: { x: 300, y: 0 }, selected: true },
  ]
  const edges: Array<{ id: string; source: string; target: string; selected?: boolean }> = [
    { id: 'e1', source: 'n1', target: 'n2' },
  ]

  it('stores measured dimensions back — the controlled-mode visibility contract', () => {
    const measured = applyCanvasNodeChanges(nodes, [
      { type: 'dimensions', id: 'n1', dimensions: { width: 300, height: 180 } },
    ])
    expect(measured[0].width).toBe(300)
    expect(measured[0].height).toBe(180)
    // untouched nodes keep identity
    expect(measured[1]).toBe(nodes[1])
  })

  it('applies selection and position changes immutably', () => {
    const moved = applyCanvasNodeChanges(nodes, [
      { type: 'select', id: 'n1', selected: true },
      { type: 'position', id: 'n2', position: { x: 10, y: 20 }, dragging: true },
    ])
    expect(moved[0].selected).toBe(true)
    expect(moved[1].position).toEqual({ x: 10, y: 20 })
    expect(moved[1].dragging).toBe(true)
    expect(nodes[0].selected).toBeUndefined()
    expect(nodes[1].position).toEqual({ x: 300, y: 0 })
  })

  it('removes nodes and edges by id', () => {
    expect(applyCanvasNodeChanges(nodes, [{ type: 'remove', id: 'n2' }])).toHaveLength(1)
    expect(applyCanvasEdgeChanges(edges, [{ type: 'remove', id: 'e1' }])).toHaveLength(0)
  })

  it('toggles edge selection', () => {
    const selected = applyCanvasEdgeChanges(edges, [{ type: 'select', id: 'e1', selected: true }])
    expect(selected[0].selected).toBe(true)
  })
})
