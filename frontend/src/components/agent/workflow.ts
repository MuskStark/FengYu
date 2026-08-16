import type { Edge } from '@vue-flow/core'
import type { AgentTool, FlowGraph, FlowGraphEdge, FlowGraphNode, FlowNodeDescriptor } from '@/api/types'

/**
 * Framework-neutral canvas primitives. They mirror the reactflow subset Flowise's
 * canvas round-trips (id/position/data/type/selected), so the React island can adopt
 * the Vue-owned arrays without an adapter layer. (The canvas itself is a port of
 * Flowise's React Flow implementation — see src/flowise/.)
 */
export interface FlowCanvasNodeBase {
  id: string
  position: { x: number; y: number }
  selected?: boolean
  dragging?: boolean
  /** Measured by the renderer (reactflow controlled-mode dimensions change). */
  width?: number
  height?: number
}

export interface WorkflowNodeData {
  tool: AgentTool
  argsText: string
  description: string
  requiresApproval: boolean
  available: boolean
  /** Flowise agentflow node color (tokens.colors.nodes.*) — drives card, badge, and edge gradients. */
  color?: string
  /** Explicit canvas declaration this node renders from (null for legacy schema-derived nodes). */
  descriptor?: FlowNodeDescriptor
}

export type WorkflowFlowNode = FlowCanvasNodeBase & {
  type: 'tool'
  data: WorkflowNodeData
}

/** Sticky-note node — canvas annotation only, never compiled into a plan step. */
export interface WorkflowNoteData {
  content: string
  color: WorkflowNoteColor
}

export type WorkflowNoteColor = 'yellow' | 'green' | 'blue' | 'pink'

export const WORKFLOW_NOTE_COLORS: WorkflowNoteColor[] = ['yellow', 'green', 'blue', 'pink']

export type WorkflowNoteNode = FlowCanvasNodeBase & {
  type: 'note'
  data: WorkflowNoteData
}

export type CanvasFlowNode = WorkflowFlowNode | WorkflowNoteNode

export function isWorkflowNoteNode(node: { type?: string | null }): node is WorkflowNoteNode {
  return node.type === 'note'
}

/** Canvas edge — vue-flow's Edge under an alias, so canvas callers stay short. */
export type FlowCanvasEdge = Edge

/** One reactflow NodeChange, normalized so the Vue side can apply it immutably. */
export interface CanvasNodeChange {
  type: 'select' | 'position' | 'dimensions' | 'remove' | 'add'
  id?: string
  selected?: boolean
  dragging?: boolean
  position?: { x: number; y: number }
  dimensions?: { width: number; height: number }
}

/** One reactflow EdgeChange, normalized. */
export interface CanvasEdgeChange {
  type: 'select' | 'remove'
  id: string
  selected?: boolean
}

export function applyCanvasNodeChanges<T extends FlowCanvasNodeBase>(nodes: T[], changes: CanvasNodeChange[]): T[] {
  let next = nodes
  for (const change of changes) {
    if (change.type === 'remove') {
      next = next.filter((node) => node.id !== change.id)
      continue
    }
    if (!change.id) continue
    next = next.map((node) => {
      if (node.id !== change.id) return node
      if (change.type === 'select') return { ...node, selected: change.selected }
      if (change.type === 'position' && change.position) {
        return { ...node, position: change.position, dragging: change.dragging }
      }
      // reactflow's controlled mode requires the parent to store measured
      // dimensions back onto the node — until then nodes render hidden and
      // edges have no anchor positions.
      if (change.type === 'dimensions' && change.dimensions) {
        return { ...node, width: change.dimensions.width, height: change.dimensions.height }
      }
      return node
    })
  }
  return next === nodes ? nodes : next
}

export function applyCanvasEdgeChanges<T extends { id: string; selected?: boolean }>(edges: T[], changes: CanvasEdgeChange[]): T[] {
  let next = edges
  for (const change of changes) {
    if (change.type === 'remove') {
      next = next.filter((edge) => edge.id !== change.id)
      continue
    }
    next = next.map((edge) =>
      edge.id === change.id ? { ...edge, selected: change.selected } : edge)
  }
  return next === edges ? edges : next
}

/**
 * Persisted canvas graph (the Flowise flowData equivalent): the exact nodes/edges
 * the author arranged, independent of the compiled AgentPlan. Tool nodes carry
 * `toolName` instead of the full tool descriptor — the builder rehydrates the
 * descriptor from the live tool catalog (reconciling schema revisions), so the
 * stored graph stays compact and never pins a stale schema. The wire types live
 * in api/types.ts (`FlowGraph`); they are re-exported here for canvas callers.
 */
export type { FlowGraph, FlowGraphEdge, FlowGraphNode }

export function serializeFlowGraph(
  nodes: Array<{ id: string; type?: string | null; position: { x: number; y: number }; data?: unknown }>,
  edges: Array<{ id?: string; source: string; target: string }>,
): FlowGraph {
  return {
    nodes: nodes.map((node) => {
      if (isWorkflowNoteNode(node)) {
        return {
          id: node.id,
          type: 'note',
          position: { x: Math.round(node.position.x), y: Math.round(node.position.y) },
          data: { content: node.data.content, color: node.data.color },
        }
      }
      const data = node.data as WorkflowNodeData
      return {
        id: node.id,
        type: 'tool',
        position: { x: Math.round(node.position.x), y: Math.round(node.position.y) },
        data: {
          toolName: data.tool.name,
          argsText: data.argsText,
          description: data.description,
          requiresApproval: data.requiresApproval,
        },
      }
    }),
    edges: edges.map((edge) => ({
      id: edge.id ?? `edge_${edge.source}_${edge.target}`,
      source: edge.source,
      target: edge.target,
    })),
  }
}

/** Graph → canvas nodes/edges. Edge marker styling is applied by the caller. */
export function rehydrateFlowGraph(
  graph: FlowGraph | null | undefined,
  tools: AgentTool[],
): { nodes: CanvasFlowNode[]; edges: FlowGraphEdge[] } | null {
  if (!graph || !Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) return null
  const byName = new Map(tools.map((tool) => [tool.name, tool]))
  const nodes: CanvasFlowNode[] = []
  for (const node of graph.nodes) {
    if (!node || typeof node.id !== 'string') continue
    const position = {
      x: Number(node.position?.x) || 0,
      y: Number(node.position?.y) || 0,
    }
    if (node.type === 'note') {
      const data = node.data ?? {}
      nodes.push({
        id: node.id,
        type: 'note',
        position,
        data: {
          content: typeof data.content === 'string' ? data.content : '',
          color: WORKFLOW_NOTE_COLORS.includes(data.color as WorkflowNoteColor)
            ? data.color as WorkflowNoteColor
            : 'yellow',
        },
      })
      continue
    }
    const toolName = typeof node.data?.toolName === 'string' ? node.data.toolName : ''
    const tool = byName.get(toolName) ?? {
      id: `missing:${toolName}`,
      name: toolName,
      description: typeof node.data?.description === 'string' ? node.data.description : toolName,
      inputSchema: '{"type":"object","properties":{}}',
      revision: 'missing',
    }
    nodes.push({
      id: node.id,
      type: 'tool',
      position,
      data: {
        tool,
        argsText: typeof node.data?.argsText === 'string' ? node.data.argsText : '{}',
        description: typeof node.data?.description === 'string' ? node.data.description : tool.description,
        requiresApproval: Boolean(node.data?.requiresApproval),
        available: byName.has(toolName),
        color: workflowNodeColor(tool),
        descriptor: tool.flowNode ?? undefined,
      },
    })
  }
  const known = new Set(nodes.map((node) => node.id))
  const edges = graph.edges
    .filter((edge) => edge && typeof edge.source === 'string' && typeof edge.target === 'string'
      && known.has(edge.source) && known.has(edge.target))
    .map((edge) => ({
      id: edge.id ?? `edge_${edge.source}_${edge.target}`,
      source: edge.source,
      target: edge.target,
    }))
  return { nodes, edges }
}

/**
 * Highest numeric suffix carried by the persisted `node_N` / `note_N` ids. Rehydration
 * keeps authored ids verbatim, so the builder's id sequences must advance past these
 * values — otherwise `addTool`/`addStickyNote` mint an id that collides with a loaded
 * node. Ids of any other shape (template ids like `excelEmail_n1`, non-numeric
 * suffixes) cannot collide with minted ids and are ignored.
 */
export function maxCanvasIdSequences(nodes: Array<{ id: string }>): { node: number; note: number } {
  let node = 0
  let note = 0
  for (const canvasNode of nodes) {
    const tool = /^node_(\d+)$/.exec(canvasNode.id)
    if (tool) node = Math.max(node, Number.parseInt(tool[1], 10))
    const stickyNote = /^note_(\d+)$/.exec(canvasNode.id)
    if (stickyNote) note = Math.max(note, Number.parseInt(stickyNote[1], 10))
  }
  return { node, note }
}

interface InputProperty {
  type?: string
  title?: string
  description?: string
  default?: unknown
  enum?: unknown[]
  items?: InputProperty
}

/** Live option source for a run-form input: options fetched from a plugin list tool at run time. */
export interface WorkflowEnumSource {
  plugin: string
  method: string
  items: string
  value: string
  label: string
  labelSecondary?: string
  multiple?: boolean
}

export interface WorkflowSchemaProperty extends InputProperty {
  properties?: Record<string, WorkflowSchemaProperty>
  /** Narrowed so nested row-editor fields carry the full annotation surface. */
  items?: WorkflowSchemaProperty
  /** `fengyu-file` / `fengyu-directory` — rendered as a picker in the run form. */
  format?: string
  /** `shared-directory` — the run mints a host-managed cross-plugin scratch directory. */
  'x-fengyu-auto'?: string
  /** `excel` — analyze the picked workbook to source sheet/column dropdown candidates. */
  'x-fengyu-analyze'?: string
  /** `workbook-sheets` / `workbook-columns` — datalist candidates from the analyzed workbook. */
  'x-fengyu-options-from'?: string
  /** Canvas-only: fold this input into the node's "Advanced settings" section. */
  'x-fengyu-advanced'?: boolean
  'x-fengyu-enum'?: WorkflowEnumSource
}

export interface WorkflowSchema {
  type?: string
  properties?: Record<string, WorkflowSchemaProperty>
  required?: string[]
}

export interface WorkflowFieldSummary {
  name: string
  label: string
  type: string
  required: boolean
  configured: boolean
  source: 'manual' | 'node' | 'workflow'
  value: string
}

export function parseWorkflowSchema(schemaText?: string | null): WorkflowSchema {
  try {
    const parsed = JSON.parse(schemaText || '{}')
    return parsed && !Array.isArray(parsed) && typeof parsed === 'object' ? parsed as WorkflowSchema : {}
  } catch {
    return {}
  }
}

export function parseWorkflowArguments(argsText?: string | null): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(argsText || '{}')
    return parsed && !Array.isArray(parsed) && typeof parsed === 'object'
      ? parsed as Record<string, unknown>
      : null
  } catch {
    return null
  }
}

/** Turn manifest-oriented camelCase/snake_case names into labels a non-technical user can scan. */
export function humanizeWorkflowField(name: string): string {
  const spaced = name
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
  if (!spaced) return name
  const acronyms = new Set(['id', 'url', 'uri', 'html', 'json', 'api', 'http', 'https', 'sql', 'csv', 'pdf'])
  return spaced.split(' ').map((word, index) => {
    if (acronyms.has(word.toLocaleLowerCase())) return word.toLocaleUpperCase()
    return index === 0 ? word.charAt(0).toLocaleUpperCase() + word.slice(1) : word
  }).join(' ')
}

export function humanizeWorkflowToolName(name: string): string {
  return humanizeWorkflowField(name)
    .split(' ')
    .map((word) => word ? word.charAt(0).toLocaleUpperCase() + word.slice(1) : word)
    .join(' ')
}

/**
 * Flowise agentflow node-type colors (verbatim from packages/agentflow/src/core/theme/tokens.ts),
 * assigned per tool category. Drives the node card tint, the rounded-square icon badge, and the
 * gradient edges — exactly the roles data.color plays in Flowise's AgentFlowNode.
 */
export const WORKFLOW_TOOL_NODE_COLORS: Record<string, string> = {
  browser: '#FF7F7F', // nodeHttp
  email: '#4DDBBB', // nodeDirectReply
  excel: '#d4a373', // nodeTool
  python: '#E4B7FF', // nodeCustomFunction
  skills: '#FFB938', // nodeCondition
  content: '#b8bedd', // nodeRetriever
  other: '#4DD0E1', // nodeAgent
}

/** useNodeColors' default when no color applies. */
export const WORKFLOW_NODE_DEFAULT_COLOR = '#666666'

export function workflowNodeColor(tool: Pick<AgentTool, 'name' | 'pluginId'> &
  { flowNode?: FlowNodeDescriptor | null }): string {
  // Explicit declaration wins; category colors remain the fallback for legacy nodes.
  return tool.flowNode?.color
    ?? WORKFLOW_TOOL_NODE_COLORS[workflowToolCategory(tool)]
    ?? WORKFLOW_NODE_DEFAULT_COLOR
}

export function workflowToolCategory(tool: Pick<AgentTool, 'name' | 'pluginId'>): string {
  const id = `${tool.pluginId || ''} ${tool.name}`.toLocaleLowerCase()
  if (id.includes('browser')) return 'browser'
  if (id.includes('email')) return 'email'
  if (id.includes('excel')) return 'excel'
  if (id.includes('python')) return 'python'
  if (id.includes('skill')) return 'skills'
  if (id.includes('markdown')) return 'content'
  return 'other'
}

function fieldSource(value: unknown): WorkflowFieldSummary['source'] {
  if (typeof value !== 'string') return 'manual'
  if (/^\{\{node\.[A-Za-z0-9_-]+\.result/.test(value)) return 'node'
  if (/^\{\{inputs\.[A-Za-z0-9_-]+}}$/.test(value)) return 'workflow'
  return 'manual'
}

export function isWorkflowValueConfigured(value: unknown): boolean {
  if (value === undefined || value === null) return false
  if (typeof value === 'string') return value.trim().length > 0
  if (Array.isArray(value)) return value.length > 0
  if (typeof value === 'object') return Object.keys(value as Record<string, unknown>).length > 0
  return true
}

export function summarizeWorkflowValue(value: unknown): string {
  if (!isWorkflowValueConfigured(value)) return ''
  if (typeof value === 'string') {
    const node = /^\{\{node\.([A-Za-z0-9_-]+)\.result(?:\.([A-Za-z0-9_-]+))?}}$/.exec(value)
    if (node) return node[2] ? `${node[1]} · ${humanizeWorkflowField(node[2])}` : node[1]
    const input = /^\{\{inputs\.([A-Za-z0-9_-]+)}}$/.exec(value)
    if (input) return humanizeWorkflowField(input[1])
    return value.length > 34 ? `${value.slice(0, 31)}…` : value
  }
  if (Array.isArray(value)) return `${value.length} items`
  if (typeof value === 'object') return `${Object.keys(value as Record<string, unknown>).length} fields`
  return String(value)
}

export function workflowInputSummaries(schemaText: string, argsText: string): WorkflowFieldSummary[] {
  const schema = parseWorkflowSchema(schemaText)
  const args = parseWorkflowArguments(argsText) ?? {}
  const required = new Set(schema.required ?? [])
  return Object.entries(schema.properties ?? {}).map(([name, property]) => {
    const value = args[name]
    return {
      name,
      label: property.title || humanizeWorkflowField(name),
      type: property.type || 'string',
      required: required.has(name),
      configured: isWorkflowValueConfigured(value),
      source: fieldSource(value),
      value: summarizeWorkflowValue(value),
    }
  })
}

export function workflowOutputSummaries(schemaText?: string | null): Array<Pick<WorkflowFieldSummary, 'name' | 'label' | 'type'>> {
  const schema = parseWorkflowSchema(schemaText)
  return Object.entries(schema.properties ?? {})
    .filter(([name]) => name !== 'success' && name !== 'summary')
    .map(([name, property]) => ({
      name,
      label: property.title || humanizeWorkflowField(name),
      type: property.type || 'value',
    }))
}

export function missingRequiredWorkflowInputs(schemaText: string, argsText: string): string[] {
  const schema = parseWorkflowSchema(schemaText)
  const args = parseWorkflowArguments(argsText)
  if (!args) return schema.required ?? []
  return (schema.required ?? []).filter((name) => !isWorkflowValueConfigured(args[name]))
}

/**
 * Names referenced through `{{inputs.x}}` in the goal or step args but absent from the
 * schema's properties — the save-time mirror of the backend's compile-time binding check.
 */
export function undeclaredWorkflowInputReferences(
  schemaText: string,
  goal: string,
  steps: Array<{ args?: Record<string, unknown> | null }>,
): string[] {
  const schema = parseWorkflowSchema(schemaText)
  const declared = new Set(Object.keys(schema.properties ?? {}))
  const referenced = new Set<string>()
  const visit = (value: unknown): void => {
    if (Array.isArray(value)) {
      value.forEach(visit)
      return
    }
    if (value && typeof value === 'object') {
      Object.values(value).forEach(visit)
      return
    }
    if (typeof value !== 'string') return
    for (const match of value.matchAll(/\{\{inputs\.([A-Za-z0-9_.-]+)}}/g)) {
      referenced.add(match[1].split('.')[0])
    }
  }
  visit(goal)
  for (const step of steps) visit(step.args)
  return [...referenced].filter((name) => !declared.has(name)).sort()
}

export interface WorkflowInputBinding {
  value: unknown
  /** Input names referenced through {{inputs.x}} but absent from the provided values. */
  missing: string[]
}

/**
 * Binds `{{inputs.x}}` references exactly like the backend compiler: a string that is
 * EXACTLY one reference takes the input's typed value (numbers stay numbers); references
 * embedded in longer strings render to text (JSON for non-strings). The canvas test-run
 * path compiles client-side, so this mirrors WorkflowService.bindValue semantics.
 */
export function bindWorkflowInputReferences(
  value: unknown,
  inputs: Record<string, unknown> | null,
): WorkflowInputBinding {
  const missing = new Set<string>()
  const bound = bindValue(value, inputs ?? {}, missing)
  return { value: bound, missing: [...missing].sort() }
}

function bindValue(value: unknown, inputs: Record<string, unknown>, missing: Set<string>): unknown {
  if (Array.isArray(value)) return value.map((item) => bindValue(item, inputs, missing))
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, bindValue(item, inputs, missing)]),
    )
  }
  if (typeof value !== 'string') return value
  const exact = /^{{inputs\.([A-Za-z0-9_.-]+)}}$/.exec(value)
  if (exact) {
    const name = exact[1].split('.')[0]
    if (!(name in inputs)) {
      missing.add(name)
      return value
    }
    return inputs[name]
  }
  return value.replace(/{{inputs\.([A-Za-z0-9_.-]+)}}/g, (reference, path: string) => {
    const name = path.split('.')[0]
    if (!(name in inputs)) {
      missing.add(name)
      return reference
    }
    const input = inputs[name]
    return typeof input === 'string' ? input : JSON.stringify(input)
  })
}

/**
 * Output fields of a tool schema flattened for the node-output picker, one nesting level
 * deep with dotted paths — so `email_send_batch`'s `confirmation.confirmationId` is one
 * pickable option instead of forcing users to map the whole result object.
 */
export function flattenWorkflowOutputFields(
  schemaText?: string | null,
): Array<[string, InputProperty]> {
  const schema = parseWorkflowSchema(schemaText)
  const fields: Array<[string, InputProperty]> = []
  for (const [name, property] of Object.entries(schema.properties ?? {})) {
    if (name === 'success' || name === 'summary') continue
    fields.push([name, property])
    if (property.properties) {
      for (const [childName, child] of Object.entries(property.properties)) {
        if (childName === 'success' || childName === 'summary') continue
        fields.push([`${name}.${childName}`, child])
      }
    }
  }
  return fields
}

/**
 * Stable serialization of the editable canvas state (workflow metadata + nodes + edges),
 * used as a dirty-check snapshot. Node identity uses tool name + content, not view ids,
 * so a snapshot survives the id regeneration that happens when a definition is reloaded.
 */
export interface CanvasSnapshotNode {
  tool: string
  argsText: string
  description: string
  requiresApproval: boolean
  x: number
  y: number
}

export function serializeCanvasState(input: {
  name: string
  description: string
  goal: string
  inputSchemaText: string
  nodes: Array<{ id?: string; data: WorkflowNodeData; position: { x: number; y: number } }>
  edges: Array<{ source: string; target: string }>
  notes?: Array<{ content: string; color?: string; position: { x: number; y: number } }>
}): string {
  // vue-flow nodes carry their runtime id outside `data`; edges address nodes by that id,
  // so edges are re-keyed by node index to keep the snapshot stable across reloads.
  const idIndex = new Map(input.nodes.map((node, index) => [node.id ?? `__idx_${index}`, index]))
  const nodes: CanvasSnapshotNode[] = input.nodes.map((node) => ({
    tool: node.data.tool.name,
    argsText: node.data.argsText,
    description: node.data.description,
    requiresApproval: node.data.requiresApproval,
    x: Math.round(node.position.x),
    y: Math.round(node.position.y),
  }))
  const edges = input.edges
    .map((edge) => {
      const source = idIndex.get(edge.source)
      const target = idIndex.get(edge.target)
      return source === undefined || target === undefined ? null : [source, target] as const
    })
    .filter((edge): edge is readonly [number, number] => edge !== null)
    .sort((a, b) => a[0] - b[0] || a[1] - b[1])
  return JSON.stringify({
    name: input.name.trim(),
    description: input.description.trim(),
    goal: input.goal.trim(),
    inputSchema: parseWorkflowSchema(input.inputSchemaText),
    nodes,
    edges,
    notes: (input.notes ?? []).map((note) => ({
      content: note.content,
      color: note.color ?? 'yellow',
      x: Math.round(note.position.x),
      y: Math.round(note.position.y),
    })),
  })
}

/** Layout keyed by compiled step index — matches the backend's `layout` contract. */
export function canvasLayoutByStepIndex(
  orderedNodes: Array<{ position: { x: number; y: number } }>,
): Record<string, { x: number; y: number }> {
  const layout: Record<string, { x: number; y: number }> = {}
  orderedNodes.forEach((node, index) => {
    if (Number.isFinite(node.position.x) && Number.isFinite(node.position.y)) {
      layout[String(index)] = {
        x: Math.round(node.position.x),
        y: Math.round(node.position.y),
      }
    }
  })
  return layout
}

/** Preserve authored values across a compatible tool update and seed newly required fields. */
export function reconcileWorkflowArguments(argsText: string, nextSchemaText: string): string {
  let args: Record<string, unknown>
  let schema: { properties?: Record<string, InputProperty>; required?: string[] }
  try {
    const parsed = JSON.parse(argsText || '{}')
    args = parsed && !Array.isArray(parsed) && typeof parsed === 'object' ? parsed : {}
  } catch {
    return argsText
  }
  try {
    schema = JSON.parse(nextSchemaText || '{}')
  } catch {
    return argsText
  }
  for (const name of schema.required ?? []) {
    if (args[name] !== undefined) continue
    const property = schema.properties?.[name]
    if (property && 'default' in property) args[name] = property.default
    else if (property?.type === 'array') args[name] = []
    else if (property?.type === 'object') args[name] = {}
    else if (property?.type === 'boolean') args[name] = false
    else if (property?.type === 'number' || property?.type === 'integer') args[name] = 0
    else args[name] = ''
  }
  return JSON.stringify(args, null, 2)
}

interface DirectedEdge {
  source: string
  target: string
}

export function wouldCreateCycle(edges: DirectedEdge[], source: string, target: string): boolean {
  if (source === target) return true
  const outgoing = new Map<string, string[]>()
  for (const edge of edges) {
    const targets = outgoing.get(edge.source) ?? []
    targets.push(edge.target)
    outgoing.set(edge.source, targets)
  }
  const pending = [target]
  const visited = new Set<string>()
  while (pending.length) {
    const nodeId = pending.pop()!
    if (nodeId === source) return true
    if (visited.has(nodeId)) continue
    visited.add(nodeId)
    pending.push(...(outgoing.get(nodeId) ?? []))
  }
  return false
}

export function topologicallySortWorkflowNodes<
  T extends { id: string; position: { x: number; y: number } },
>(nodes: T[], edges: DirectedEdge[]): T[] | null {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const indegree = new Map(nodes.map((node) => [node.id, 0]))
  const outgoing = new Map(nodes.map((node) => [node.id, [] as string[]]))
  for (const edge of edges) {
    if (!byId.has(edge.source) || !byId.has(edge.target)) continue
    outgoing.get(edge.source)?.push(edge.target)
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1)
  }
  const positionSort = (a: T, b: T) =>
    a.position.x - b.position.x || a.position.y - b.position.y
  const queue = nodes.filter((node) => indegree.get(node.id) === 0).sort(positionSort)
  const ordered: T[] = []
  while (queue.length) {
    const node = queue.shift()!
    ordered.push(node)
    for (const target of outgoing.get(node.id) ?? []) {
      indegree.set(target, (indegree.get(target) ?? 1) - 1)
      if (indegree.get(target) === 0) {
        queue.push(byId.get(target)!)
        queue.sort(positionSort)
      }
    }
  }
  return ordered.length === nodes.length ? ordered : null
}
