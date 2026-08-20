import type { Edge } from '@vue-flow/core'
import type {
  AgentTool,
  FlowGraph,
  FlowGraphEdge,
  FlowGraphNode,
  FlowNodeDescriptor,
  FlowOutputProperty,
  FlowValueType,
} from '@/api/types'

export type { FlowValueType, FlowOutputProperty }

/**
 * Port/field colors per flow data type (Langflow/ComfyUI convention: color IS the
 * type contract). `any` stays neutral gray — it is the v1 compatibility escape hatch.
 */
export const FLOW_TYPE_COLORS: Record<FlowValueType, string> = {
  string: '#4f46e5',
  number: '#0d9488',
  boolean: '#d97706',
  object: '#2563eb',
  array: '#9333ea',
  file: '#16a34a',
  any: '#9ca3af',
}

export function flowTypeColor(type?: string | null): string {
  return FLOW_TYPE_COLORS[normalizeFlowType(type)] ?? FLOW_TYPE_COLORS.any
}

/** JSON-Schema-ish type labels → the flow vocabulary ('integer' folds into 'number'). */
export function normalizeFlowType(type?: string | null): FlowValueType {
  switch (type) {
    case 'string': return 'string'
    case 'number':
    case 'integer': return 'number'
    case 'boolean': return 'boolean'
    case 'object': return 'object'
    case 'array': return 'array'
    case 'file': return 'file'
    default: return 'any'
  }
}

/**
 * Can a value of `sourceType` be bound into an input expecting `targetType`?
 * `any` on either side connects to everything (v1 declarations stay permissive);
 * number→string renders to text; everything else mismatched needs an adapter.
 */
export function flowTypeCompatible(targetType?: string | null, sourceType?: string | null): boolean {
  const target = normalizeFlowType(targetType)
  const source = normalizeFlowType(sourceType)
  if (target === 'any' || source === 'any' || target === source) return true
  if (target === 'string' && source === 'number') return true
  return false
}

/**
 * Reference grammar shared by the inspector, the variable tree, and the compiler.
 * Path segments accept dotted keys and [N] array indexes, mirroring the backend's
 * AgentRunner STEP_RESULT pattern: `{{node.node_2.result.files[0].name}}`.
 */
export const NODE_REFERENCE_PATTERN = /\{\{node\.([A-Za-z0-9_-]+)\.result((?:\.[A-Za-z0-9_-]+|\[\d+])*)}}/g

export interface NodeReference {
  nodeId: string
  /** Path after `.result`, e.g. `.files[0].name`; '' for the whole result. */
  path: string
}

export function formatNodeReference(reference: NodeReference): string {
  return `{{node.${reference.nodeId}.result${reference.path}}}`
}

/** Parses an EXACT single reference; embedded templates return null. */
export function parseNodeReference(value: unknown): NodeReference | null {
  if (typeof value !== 'string') return null
  const exact = /^\{\{node\.([A-Za-z0-9_-]+)\.result((?:\.[A-Za-z0-9_-]+|\[\d+])*)}}$/.exec(value)
  return exact ? { nodeId: exact[1], path: exact[2] } : null
}

/** All references (exact or embedded) inside one string value. */
export function collectNodeReferences(value: string): NodeReference[] {
  return [...value.matchAll(NODE_REFERENCE_PATTERN)]
    .map((match) => ({ nodeId: match[1], path: match[2] }))
}

/** Resolves a `.a.b[0].c` output path against a parsed result value; undefined when absent. */
export function resolveOutputPath(value: unknown, path: string): unknown {
  let current = value
  for (const segment of path.split(/[.[\]]/).filter(Boolean)) {
    if (current === null || current === undefined) return undefined
    if (Array.isArray(current)) {
      const index = Number(segment)
      current = Number.isInteger(index) ? current[index] : undefined
    } else if (typeof current === 'object') {
      current = (current as Record<string, unknown>)[segment]
    } else {
      return undefined
    }
  }
  return current
}

/** One row of the recursive output tree (descriptor v2 `properties`/`items` merged with outputSchema). */
export interface FlowOutputField {
  /** Dotted(+indexed) path after `.result`, '' for the whole result. */
  path: string
  name: string
  title: string
  type: FlowValueType
  description?: string
  examples: unknown[]
  children?: FlowOutputField[]
}

interface OutputLikeProperty {
  type?: string
  title?: string
  description?: string
  examples?: unknown[]
  properties?: Record<string, OutputLikeProperty>
  items?: OutputLikeProperty
}

function outputFieldFrom(path: string, name: string, property: OutputLikeProperty, titleFallback?: string): FlowOutputField {
  const type = normalizeFlowType(property.type)
  const field: FlowOutputField = {
    path,
    name,
    title: property.title || titleFallback || humanizeWorkflowField(name),
    type,
    description: property.description,
    examples: Array.isArray(property.examples) ? property.examples : [],
  }
  const children = childOutputFields(path, property)
  if (children.length) field.children = children
  return field
}

function childOutputFields(parentPath: string, property: OutputLikeProperty): FlowOutputField[] {
  const fields: FlowOutputField[] = []
  for (const [name, child] of Object.entries(property.properties ?? {})) {
    if (name === 'success' || name === 'summary') continue
    const path = `${parentPath}.${name}`
    fields.push(outputFieldFrom(path, name, child))
  }
  if (property.items) {
    // Surface array elements as a sample child so [0]-style paths are discoverable.
    const item = property.items
    const name = '[0]'
    const path = `${parentPath}[0]`
    const child: FlowOutputField = {
      path,
      name,
      title: outputFieldFrom(path, name, item).title,
      type: normalizeFlowType(item.type),
      description: item.description,
      examples: item.examples ?? [],
    }
    const grandchildren = childOutputFields(path, item)
    if (grandchildren.length) child.children = grandchildren
    fields.push(child)
  }
  return fields
}

/**
 * The recursive output tree one node offers to downstream inputs: declared ports
 * (descriptor v2, with nested `properties`/`items` and `examples`) win; fields the
 * tool's outputSchema declares but the descriptor omits are appended, so upgrading
 * a declaration never loses pickable paths.
 */
export function workflowOutputTree(node: {
  data?: { descriptor?: FlowNodeDescriptor; tool: Pick<AgentTool, 'outputSchema'> }
}): FlowOutputField[] {
  const declared = node.data?.descriptor?.outputs ?? []
  let schemaProperties: Record<string, OutputLikeProperty> = {}
  try {
    const schema = JSON.parse(node.data?.tool.outputSchema || '{}') as { properties?: Record<string, OutputLikeProperty> }
    schemaProperties = schema.properties ?? {}
  } catch {
    schemaProperties = {}
  }
  const seen = new Set<string>()
  const fields: FlowOutputField[] = []
  for (const port of declared) {
    if (seen.has(port.name)) continue
    seen.add(port.name)
    const property: OutputLikeProperty = {
      type: port.type,
      title: port.title,
      description: port.description ?? port.help,
      examples: port.examples,
      properties: port.properties as Record<string, OutputLikeProperty> | undefined,
      items: port.items as OutputLikeProperty | undefined,
    }
    const schemaSibling = schemaProperties[port.name]
    if (schemaSibling) {
      // Schema enrichments fill gaps the declaration left open: nested fields
      // merge key-by-key, and a missing type/items falls back to the schema.
      property.properties = { ...schemaSibling.properties, ...property.properties }
      property.items ??= schemaSibling.items
      if (property.type === 'any' && schemaSibling.type) property.type = normalizeFlowType(schemaSibling.type)
    }
    fields.push(outputFieldFrom(port.name ? `.${port.name}` : '', port.name, property))
  }
  for (const [name, property] of Object.entries(schemaProperties)) {
    if (seen.has(name) || name === 'success' || name === 'summary') continue
    seen.add(name)
    fields.push(outputFieldFrom(`.${name}`, name, property))
  }
  return fields
}

/** Matches one path segment against a tree field — array-sample children are named `[0]`. */
function fieldMatchesSegment(field: FlowOutputField, segment: string): boolean {
  return field.name === segment || (/^\d+$/.test(segment) && field.name === `[${segment}]`)
}

/** Resolves the declared type at a reference path ('' = whole result = object envelope). */
export function referencePathType(tree: FlowOutputField[], path: string): FlowValueType {
  if (!path) return 'object'
  let segments = path.split(/[.[\]]/).filter(Boolean)
  let fields = tree
  let type: FlowValueType = 'object'
  while (segments.length) {
    const segment = segments[0]
    segments = segments.slice(1)
    const match = fields.find((field) => fieldMatchesSegment(field, segment))
    if (!match) return 'any'
    type = match.type
    fields = match.children ?? []
  }
  return type
}

/** Whether a reference path resolves inside the tree (unknown fields fail save-time validation). */
export function referencePathExists(tree: FlowOutputField[], path: string): boolean {
  if (!path) return true
  let segments = path.split(/[.[\]]/).filter(Boolean)
  let fields = tree
  while (segments.length) {
    const segment = segments[0]
    segments = segments.slice(1)
    const match = fields.find((field) => fieldMatchesSegment(field, segment))
    if (!match) return false
    fields = match.children ?? []
  }
  return true
}

export interface UnknownNodeReference {
  fromNodeId: string
  nodeId: string
  path: string
  reason: 'unknown-node' | 'unknown-field'
}

/**
 * Save-time validation for `{{node.<id>.result.path}}` references: every reference
 * must target a canvas node that exists, and its path must resolve against that
 * node's declared output tree (the frontend mirror of the backend's runtime
 * "Tool result has no output field" error — surfaced before the run, not during).
 */
export function unknownNodeReferences(
  nodes: Array<{ id: string; data?: { descriptor?: FlowNodeDescriptor; tool: Pick<AgentTool, 'outputSchema'>; argsText: string } }>,
): UnknownNodeReference[] {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const unknown: UnknownNodeReference[] = []
  for (const node of nodes) {
    let args: unknown
    try {
      args = JSON.parse(node.data?.argsText || '{}')
    } catch {
      continue // invalid JSON is reported separately by the compiler
    }
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
      for (const reference of collectNodeReferences(value)) {
        const target = byId.get(reference.nodeId)
        if (!target) {
          unknown.push({ fromNodeId: node.id, nodeId: reference.nodeId, path: reference.path, reason: 'unknown-node' })
          continue
        }
        // A node with no declared tree (no descriptor outputs AND no output schema)
        // cannot be validated — its shape is unknown, not invalid.
        const tree = workflowOutputTree(target)
        if (tree.length && !referencePathExists(tree, reference.path)) {
          unknown.push({ fromNodeId: node.id, nodeId: reference.nodeId, path: reference.path, reason: 'unknown-field' })
        }
      }
    }
    visit(args)
  }
  return unknown
}


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
  /** Total attempts and initial exponential-backoff delay for retry-safe tools. */
  retryPolicy?: { maxAttempts: number; backoffMs: number }
  available: boolean
  /** Flowise agentflow node color (tokens.colors.nodes.*) — drives card, badge, and edge gradients. */
  color?: string
  /** Explicit canvas declaration this node renders from (null for legacy schema-derived nodes). */
  descriptor?: FlowNodeDescriptor
  /** Author-given node title; displayed everywhere instead of the tool label. */
  title?: string
  /** Truncated result of this node's last run (preview-only; excluded from dirty snapshots). */
  lastRun?: string
  lastRunAt?: string
  /** Canvas-authored fixed result; the compiled step serves it without executing the tool. */
  pinnedOutput?: string
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

/**
 * Start node — the visual editor for the workflow's run-time input schema. Exactly
 * one per canvas; selecting it opens the input designer. Never compiled into a
 * plan step (the schema itself persists through input_schema_json as before).
 */
export type WorkflowStartNode = FlowCanvasNodeBase & {
  type: 'start'
  data: { title?: string }
}

export function isWorkflowStartNode(node: { type?: string | null }): node is WorkflowStartNode {
  return node.type === 'start'
}

export type CanvasFlowNode = WorkflowFlowNode | WorkflowNoteNode | WorkflowStartNode

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
  edges: Array<{ id?: string; source: string; target: string; sourceHandle?: string | null }>,
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
      if (isWorkflowStartNode(node)) {
        return {
          id: node.id,
          type: 'start',
          position: { x: Math.round(node.position.x), y: Math.round(node.position.y) },
          data: node.data.title ? { title: node.data.title } : {},
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
          ...(data.retryPolicy && data.retryPolicy.maxAttempts > 1
            ? { retryPolicy: data.retryPolicy } : {}),
          ...(data.title ? { title: data.title } : {}),
          ...(data.pinnedOutput !== undefined ? { pinnedOutput: data.pinnedOutput } : {}),
          ...(data.lastRun !== undefined
            ? { lastRun: data.lastRun, lastRunAt: data.lastRunAt } : {}),
        },
      }
    }),
    edges: edges.map((edge) => ({
      id: edge.id ?? `edge_${edge.source}_${edge.target}`,
      source: edge.source,
      target: edge.target,
      ...(edge.sourceHandle ? { sourceHandle: edge.sourceHandle } : {}),
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
    if (node.type === 'start') {
      nodes.push({
        id: node.id,
        type: 'start',
        position,
        data: typeof node.data?.title === 'string' && node.data.title ? { title: node.data.title } : {},
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
    const rawRetry = node.data?.retryPolicy
    const retryPolicy = rawRetry && typeof rawRetry === 'object'
      ? {
          maxAttempts: Number((rawRetry as Record<string, unknown>).maxAttempts) || 1,
          backoffMs: Number((rawRetry as Record<string, unknown>).backoffMs) || 0,
        }
      : undefined
    nodes.push({
      id: node.id,
      type: 'tool',
      position,
      data: {
        tool,
        argsText: typeof node.data?.argsText === 'string' ? node.data.argsText : '{}',
        description: typeof node.data?.description === 'string' ? node.data.description : tool.description,
        requiresApproval: Boolean(node.data?.requiresApproval),
        ...(retryPolicy && retryPolicy.maxAttempts > 1 ? { retryPolicy } : {}),
        available: byName.has(toolName),
        color: workflowNodeColor(tool),
        descriptor: tool.flowNode ?? undefined,
        ...(typeof node.data?.title === 'string' && node.data.title ? { title: node.data.title } : {}),
        ...(typeof node.data?.pinnedOutput === 'string' ? { pinnedOutput: node.data.pinnedOutput } : {}),
        ...(typeof node.data?.lastRun === 'string' ? { lastRun: node.data.lastRun } : {}),
        ...(typeof node.data?.lastRunAt === 'string' ? { lastRunAt: node.data.lastRunAt } : {}),
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
      ...(typeof edge.sourceHandle === 'string' && edge.sourceHandle
        ? { sourceHandle: edge.sourceHandle }
        : {}),
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
  examples?: unknown[]
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
  /** Canvas-only: render as a plain multiline string textarea. */
  'x-fengyu-multiline'?: boolean
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
  source: 'manual' | 'node' | 'workflow' | 'expression'
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
  if (id.includes('flow_llm')) return 'ai'
  if (id.includes('flow_if')) return 'control'
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
  if (/\{\{node\.[A-Za-z0-9_-]+\.result/.test(value)) return 'expression'
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
    const reference = parseNodeReference(value)
    if (reference) {
      const path = reference.path.replace(/^\./, '')
      return path
        ? `${reference.nodeId} · ${humanizeWorkflowField(path)}`
        : reference.nodeId
    }
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

/** Display title: author rename > declared label > humanized tool name. */
export function workflowNodeTitle(node: {
  data: { title?: string; descriptor?: FlowNodeDescriptor; tool: Pick<AgentTool, 'name'> }
}): string {
  return node.data.title
    || node.data.descriptor?.label
    || humanizeWorkflowToolName(node.data.tool.name)
}

/**
 * Missing required inputs of one canvas node: the tool schema's required list plus
 * descriptor-v2 `required` flags (a declaration can require a field the schema
 * doesn't mark, e.g. on-canvas-only binding fields).
 */
export function missingRequiredNodeInputs(node: {
  data: { tool: Pick<AgentTool, 'inputSchema'>; argsText: string; descriptor?: FlowNodeDescriptor }
}): string[] {
  const missing = new Set(missingRequiredWorkflowInputs(node.data.tool.inputSchema, node.data.argsText))
  const args = parseWorkflowArguments(node.data.argsText) ?? {}
  for (const input of node.data.descriptor?.inputs ?? []) {
    if (input.required && !isWorkflowValueConfigured(args[input.name])) missing.add(input.name)
  }
  return [...missing]
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
  retryPolicy?: { maxAttempts: number; backoffMs: number }
  title?: string
  pinnedOutput?: string
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
  start?: { title?: string; position: { x: number; y: number } } | null
}): string {
  // vue-flow nodes carry their runtime id outside `data`; edges address nodes by that id,
  // so edges are re-keyed by node index to keep the snapshot stable across reloads.
  const idIndex = new Map(input.nodes.map((node, index) => [node.id ?? `__idx_${index}`, index]))
  const nodes: CanvasSnapshotNode[] = input.nodes.map((node) => ({
    tool: node.data.tool.name,
    argsText: node.data.argsText,
    description: node.data.description,
    requiresApproval: node.data.requiresApproval,
    ...(node.data.retryPolicy && node.data.retryPolicy.maxAttempts > 1
      ? { retryPolicy: node.data.retryPolicy } : {}),
    // lastRun is captured automatically by runs, not user edits — excluded so a
    // finished run never trips the unsaved-changes guard. Pins and titles are
    // deliberate authoring actions and DO count as changes.
    ...(node.data.title ? { title: node.data.title } : {}),
    ...(node.data.pinnedOutput !== undefined ? { pinnedOutput: node.data.pinnedOutput } : {}),
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
    ...(input.start ? {
      start: {
        title: input.start.title ?? '',
        x: Math.round(input.start.position.x),
        y: Math.round(input.start.position.y),
      },
    } : {}),
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

/**
 * Duplicate/cycle gate for one canvas connection, checked against a PRE-UPDATE
 * edge list. `connection.id` is set only when vue-flow re-validates an already
 * stored edge through `isValidConnection` — the v-model reassigns the whole
 * array, so every preserved edge passes through this gate too. An entry whose
 * id is already in the list is that echo, not a new connection: it must be
 * accepted as-is (even mid-run), otherwise each reassignment silently drops
 * all previously connected links and only the newest edge survives.
 */
export function canConnect(
  connection: { id?: string | null; source: string; target: string },
  edgeList: Array<{ id?: string | null; source: string; target: string }>,
  options?: { busy?: boolean },
): boolean {
  if (connection.id != null && edgeList.some((edge) => edge.id === connection.id)) return true
  if (options?.busy || connection.source === connection.target) return false
  if (edgeList.some(
    (edge) => edge.source === connection.source && edge.target === connection.target,
  )) return false
  return !wouldCreateCycle(edgeList, connection.source, connection.target)
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
