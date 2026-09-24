import type { Edge, Node } from '@xyflow/react'
import { MarkerType } from '@xyflow/react'
import { i18n } from '@/i18n'
import { humanizeWorkflowField, workflowNodeColor } from '@/lib/flowDisplay'
import type {
  AgentPlan,
  AgentStep,
  AgentTool,
  FlowGraph,
  WorkflowNodeLayout,
} from '@/services/types'

/**
 * Framework-neutral flow-graph model shared by the flow builder pages: the pure
 * conversions between @xyflow/react canvas state and the persisted
 * `WorkflowDefinition.graph` (FlowGraph) wire shape, plus the canvas → AgentPlan
 * compiler the builder saves and runs. Ported from the Vue shell's
 * `components/agent/workflow.ts`, reduced to the P3 rewrite surface (start /
 * tool / sticky nodes). Node data here is deliberately wire-compatible with the
 * Vue builder: tool nodes persist `toolName` + `argsText`, sticky notes persist
 * under the legacy `note` type string, so graphs round-trip between shells.
 */

export type FlowStickyColor = 'yellow' | 'green' | 'blue' | 'pink'
export const STICKY_COLORS: FlowStickyColor[] = ['yellow', 'green', 'blue', 'pink']

export type FlowStartData = {
  title?: string
}
export type FlowToolData = {
  toolName: string
  argsText: string
  description: string
  requiresApproval: boolean
  title?: string
  /** Resolved against the live tool catalog (false → the plugin is gone/disabled). */
  available?: boolean
  /** Display tint; derived from the tool descriptor/category at rehydrate time. */
  color?: string
  /** Raw JSON of the most recent step_complete result (runtime capture, ≤16 KB). */
  lastRun?: string
  lastRunAt?: number
  /** Authored fixed result: the runner serves it downstream without executing the node. */
  pinnedOutput?: string
  /** Bounded retries for retry-safe tools (compiled into the step contract). */
  retryPolicy?: { maxAttempts: number; backoffMs: number }
}
export type FlowStickyData = {
  content: string
  color: FlowStickyColor
}

export type FlowCanvasNode =
  | (Node<FlowStartData> & { type: 'start'; data: FlowStartData })
  | (Node<FlowToolData> & { type: 'tool'; data: FlowToolData })
  | (Node<FlowStickyData> & { type: 'sticky'; data: FlowStickyData })

export function isStartNode(node: Node): node is Node<FlowStartData> & { type: 'start'; data: FlowStartData } {
  return node.type === 'start'
}

export function isToolNode(node: Node): node is Node<FlowToolData> & { type: 'tool'; data: FlowToolData } {
  return node.type === 'tool'
}

/** The tool member of the canvas node union (the executable graph). */
export type ToolCanvasNode = Extract<FlowCanvasNode, { type: 'tool' }>

export function isStickyNode(node: Node): node is Node<FlowStickyData> & { type: 'sticky'; data: FlowStickyData } {
  return node.type === 'sticky'
}

function roundPosition(position: { x: number; y: number }): { x: number; y: number } {
  return { x: Math.round(Number(position.x) || 0), y: Math.round(Number(position.y) || 0) }
}

/** Canvas edge factory: arrow-capped smoothstep links like the Vue canvas. */
export function makeFlowEdge(source: string, target: string, sourceHandle?: string | null): Edge {
  return {
    id: `edge_${source}_${target}${sourceHandle ? `_${sourceHandle}` : ''}`,
    source,
    target,
    ...(sourceHandle ? { sourceHandle } : {}),
    type: 'smoothstep',
    markerEnd: { type: MarkerType.ArrowClosed },
  }
}

// ── canvas → persisted graph ──────────────────────────────────────────────

/** Persists the exact canvas the author arranged. Sticky notes keep the wire `note` type. */
export function serializeFlowGraph(
  nodes: Array<Pick<Node, 'id' | 'type' | 'position'> & { data?: unknown }>,
  edges: Array<{ id?: string; source: string; target: string; sourceHandle?: string | null }>,
): FlowGraph {
  return {
    nodes: nodes.map((node) => {
      const position = roundPosition(node.position)
      if (node.type === 'sticky') {
        const data = (node.data ?? {}) as Partial<FlowStickyData>
        return {
          id: node.id,
          type: 'note',
          position,
          data: {
            content: typeof data.content === 'string' ? data.content : '',
            color: STICKY_COLORS.includes(data.color as FlowStickyColor)
              ? data.color as FlowStickyColor
              : 'yellow',
          },
        }
      }
      if (node.type === 'start') {
        const data = (node.data ?? {}) as Partial<FlowStartData>
        return {
          id: node.id,
          type: 'start',
          position,
          data: typeof data.title === 'string' && data.title ? { title: data.title } : {},
        }
      }
      const data = (node.data ?? {}) as Partial<FlowToolData>
      return {
        id: node.id,
        type: 'tool',
        position,
        data: {
          toolName: typeof data.toolName === 'string' ? data.toolName : '',
          argsText: typeof data.argsText === 'string' ? data.argsText : '{}',
          description: typeof data.description === 'string' ? data.description : '',
          requiresApproval: Boolean(data.requiresApproval),
          ...(data.title ? { title: data.title } : {}),
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

// ── persisted graph → canvas ──────────────────────────────────────────────

/** Placeholder for a persisted tool the live catalog no longer offers. */
export function missingTool(toolName: string, description?: string): AgentTool {
  return {
    id: `missing:${toolName}`,
    name: toolName,
    description: description || toolName,
    inputSchema: '{"type":"object","properties":{}}',
    revision: 'missing',
  }
}

/**
 * Graph → canvas nodes/edges. Tool metadata is rehydrated from the live catalog;
 * unknown tools survive as unavailable placeholder nodes. Returns null for a graph
 * without valid nodes/edges arrays (or colliding ids — React Flow keys by id).
 */
export function rehydrateFlowGraph(
  graph: FlowGraph | null | undefined,
  tools: AgentTool[],
): { nodes: FlowCanvasNode[]; edges: Edge[] } | null {
  if (!graph || !Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) return null
  const byName = new Map(tools.map((tool) => [tool.name, tool]))
  const nodes: FlowCanvasNode[] = []
  const seenIds = new Set<string>()
  for (const node of graph.nodes) {
    if (!node || typeof node.id !== 'string') continue
    // React Flow keys nodes by id; a graph carrying duplicates cannot be mounted.
    if (seenIds.has(node.id)) return null
    seenIds.add(node.id)
    const position = {
      x: Number(node.position?.x) || 0,
      y: Number(node.position?.y) || 0,
    }
    if (node.type === 'note') {
      const data = (node.data ?? {}) as Partial<FlowStickyData>
      nodes.push({
        id: node.id,
        type: 'sticky',
        position,
        data: {
          content: typeof data.content === 'string' ? data.content : '',
          color: STICKY_COLORS.includes(data.color as FlowStickyColor)
            ? data.color as FlowStickyColor
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
        data: typeof (node.data as Partial<FlowStartData> | undefined)?.title === 'string'
          && (node.data as Partial<FlowStartData>).title
          ? { title: (node.data as Partial<FlowStartData>).title }
          : {},
      })
      continue
    }
    const raw = (node.data ?? {}) as Partial<FlowToolData>
    const toolName = typeof raw.toolName === 'string' ? raw.toolName : ''
    const tool = byName.get(toolName) ?? missingTool(toolName, raw.description)
    nodes.push({
      id: node.id,
      type: 'tool',
      position,
      data: {
        toolName,
        argsText: typeof raw.argsText === 'string' ? raw.argsText : '{}',
        description: typeof raw.description === 'string' ? raw.description : tool.description,
        requiresApproval: Boolean(raw.requiresApproval),
        available: byName.has(toolName),
        color: workflowNodeColor(tool),
        ...(typeof raw.title === 'string' && raw.title ? { title: raw.title } : {}),
        ...(raw.retryPolicy && typeof raw.retryPolicy === 'object'
          && Number.isFinite(raw.retryPolicy.maxAttempts)
          ? { retryPolicy: { maxAttempts: raw.retryPolicy.maxAttempts, backoffMs: raw.retryPolicy.backoffMs } }
          : {}),
        ...(typeof raw.pinnedOutput === 'string' ? { pinnedOutput: raw.pinnedOutput } : {}),
      },
    })
  }
  const known = new Set(nodes.map((node) => node.id))
  const edges = graph.edges
    .filter((edge) => edge && typeof edge.source === 'string' && typeof edge.target === 'string'
      && known.has(edge.source) && known.has(edge.target))
    .map((edge) => makeFlowEdge(edge.source, edge.target,
      typeof edge.sourceHandle === 'string' && edge.sourceHandle ? edge.sourceHandle : null))
  return { nodes, edges }
}

// ── start node + id sequencing ────────────────────────────────────────────

/**
 * Adds/deduplicates Start (at most one per canvas; anchor sits left of the
 * leftmost executable node, mirroring the Vue placement).
 */
export function ensureStartNode(nodes: FlowCanvasNode[]): FlowCanvasNode[] {
  const firstStart = nodes.findIndex(isStartNode)
  if (firstStart >= 0) {
    const startCount = nodes.filter(isStartNode).length
    return startCount === 1
      ? nodes
      : nodes.filter((node, index) => !isStartNode(node) || index === firstStart)
  }
  const ids = new Set(nodes.map((node) => node.id))
  let suffix = 1
  while (ids.has(`start_${suffix}`)) suffix += 1
  // Sticky notes are free-form annotations often far outside the executable graph;
  // anchor on positioned executable nodes so Start never lands off-screen.
  const positioned = nodes.filter((node) => !isStickyNode(node)
    && Number.isFinite(node.position.x) && Number.isFinite(node.position.y))
  const anchorX = positioned.length ? Math.min(...positioned.map((node) => node.position.x)) : 0
  const anchorY = positioned.length ? Math.min(...positioned.map((node) => node.position.y)) : 90
  return [{
    id: `start_${suffix}`,
    type: 'start',
    position: { x: anchorX - 300, y: anchorY },
    data: {},
  }, ...nodes]
}

/**
 * Highest numeric suffix carried by the persisted `node_N` / `note_N` ids, so the
 * builder's id sequences advance past authored ids and never mint a collision.
 */
export function maxCanvasIdSequences(nodes: Array<{ id: string }>): { node: number; note: number } {
  let node = 0
  let note = 0
  for (const canvasNode of nodes) {
    const tool = /^node_(\d+)$/.exec(canvasNode.id)
    if (tool) node = Math.max(node, Number.parseInt(tool[1], 10))
    const sticky = /^note_(\d+)$/.exec(canvasNode.id)
    if (sticky) note = Math.max(note, Number.parseInt(sticky[1], 10))
  }
  return { node, note }
}

// ── connection gating ─────────────────────────────────────────────────────

export interface DirectedEdge {
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

/**
 * Duplicate/cycle gate for one canvas connection, checked against a PRE-UPDATE
 * edge list. Re-validations of an already-stored edge (same id) pass as-is.
 */
export function canConnect(
  connection: { id?: string | null; source: string; target: string },
  edgeList: Array<{ id?: string | null; source: string; target: string }>,
  options?: { busy?: boolean },
): boolean {
  if (connection.id != null && edgeList.some((edge) => edge.id === connection.id)) return true
  if (options?.busy || connection.source === connection.target) return false
  if (edgeList.some((edge) => edge.source === connection.source
    && edge.target === connection.target)) return false
  return !wouldCreateCycle(edgeList, connection.source, connection.target)
}

/** Position-ordered topological sort of executable nodes; null when cyclic. */
export function topologicallySortNodes<T extends { id: string; position: { x: number; y: number } }>(
  nodes: T[],
  edges: DirectedEdge[],
): T[] | null {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const indegree = new Map(nodes.map((node) => [node.id, 0]))
  const outgoing = new Map(nodes.map((node) => [node.id, [] as string[]]))
  for (const edge of edges) {
    if (!byId.has(edge.source) || !byId.has(edge.target)) continue
    outgoing.get(edge.source)?.push(edge.target)
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1)
  }
  const positionSort = (a: T, b: T) => a.position.x - b.position.x || a.position.y - b.position.y
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

// ── node references + workflow input binding ──────────────────────────────

/**
 * Reference grammar shared by the inspector and the compiler:
 * `{{node.node_2.result.files[0].name}}` — the backend runner's step-reference pattern.
 */
export const NODE_REFERENCE_PATTERN = /\{\{node\.([A-Za-z0-9_-]+)\.(result|input)((?:\.[A-Za-z0-9_-]+|\[\d+])*)}}/g

/** Rewrites `{{node.<id>…}}` canvas references into `{{steps.<index>…}}` plan references. */
export function replaceNodeReferences(
  value: unknown,
  indexes: Map<string, number>,
  currentIndex: number,
): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => replaceNodeReferences(item, indexes, currentIndex))
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [
        key,
        replaceNodeReferences(item, indexes, currentIndex),
      ]),
    )
  }
  if (typeof value !== 'string') return value
  return value.replace(NODE_REFERENCE_PATTERN,
    (_match, id: string, source: 'input' | 'result', path: string) => {
      const index = indexes.get(id)
      if (index === undefined) {
        throw new Error(i18n.global.t('agent.canvasUnknownReference', { id }))
      }
      if (index >= currentIndex) {
        throw new Error(i18n.global.t('agent.canvasFutureReference', { id }))
      }
      return `{{steps.${index}.${source}${path}}}`
    })
}

export interface WorkflowInputBinding {
  value: unknown
  /** Input names referenced through {{inputs.x}} but absent from the provided values. */
  missing: string[]
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
 * Binds `{{inputs.x}}` references exactly like the backend compiler: a string that
 * is EXACTLY one reference takes the input's typed value; references embedded in
 * longer strings render to text (JSON for non-strings).
 */
export function bindWorkflowInputReferences(
  value: unknown,
  inputs: Record<string, unknown> | null,
): WorkflowInputBinding {
  const missing = new Set<string>()
  const bound = bindValue(value, inputs ?? {}, missing)
  return { value: bound, missing: [...missing].sort() }
}

// ── tool argument helpers ─────────────────────────────────────────────────

interface ToolSchemaProperty {
  type?: string
  default?: unknown
}
interface ToolSchema {
  properties?: Record<string, ToolSchemaProperty>
  required?: string[]
}

/** Seeds args for a newly added tool: every required input starts at a typed default. */
export function defaultArgsText(tool: AgentTool): string {
  let schema: ToolSchema
  try {
    schema = JSON.parse(tool.inputSchema) as ToolSchema
  } catch {
    return '{}'
  }
  const args: Record<string, unknown> = {}
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

export function parseJsonObject(text: string | null | undefined): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(text || '{}')
    return parsed && !Array.isArray(parsed) && typeof parsed === 'object'
      ? parsed as Record<string, unknown>
      : null
  } catch {
    return null
  }
}

/** Missing required inputs of one tool node, per its RPC schema. */
export function missingRequiredNodeInputs(tool: AgentTool, argsText: string): string[] {
  let schema: ToolSchema
  try {
    schema = JSON.parse(tool.inputSchema) as ToolSchema
  } catch {
    return []
  }
  const args = parseJsonObject(argsText)
  if (!args) return schema.required ?? []
  const configured = (name: string): boolean => {
    const value = args[name]
    if (value === undefined || value === null) return false
    if (typeof value === 'string') return value.trim().length > 0
    if (Array.isArray(value)) return value.length > 0
    if (typeof value === 'object') return Object.keys(value as Record<string, unknown>).length > 0
    return true
  }
  return (schema.required ?? []).filter((name) => !configured(name))
}

/** Names referenced through `{{node.x…}}` that point at a node not on the canvas. */
export function unknownNodeReferences(
  toolNodes: Array<{ id: string; data: { argsText: string } }>,
  allNodeIds: Set<string>,
): string[] {
  const unknown = new Set<string>()
  for (const node of toolNodes) {
    const args = parseJsonObject(node.data.argsText)
    if (!args) continue
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
      for (const match of value.matchAll(NODE_REFERENCE_PATTERN)) {
        if (!allNodeIds.has(match[1])) {
          unknown.add(`${match[1]}.${match[2]}${match[3]}`)
        }
      }
    }
    visit(args)
  }
  return [...unknown].sort()
}

// ── canvas → AgentPlan compilation ────────────────────────────────────────

export interface CompileFlowPlanOptions {
  /** Workflow goal (saved with placeholders; a direct run may pre-bind inputs). */
  goal: string
  /** Goal fallback when the authored goal is blank. */
  defaultGoal?: string
  /** Run-form values; supply with bindInputs for a directly posted plan. */
  bindInputs?: boolean
  inputs?: Record<string, unknown>
}

export interface CompiledFlowPlan {
  plan: AgentPlan
  /** Layout keyed by compiled step index (the backend `layout` contract). */
  layout: Record<string, WorkflowNodeLayout>
  /** Compiled step index → canvas node id (drives on-node run badges). */
  orderedIds: string[]
}

/**
 * Compiles the executable canvas (tool nodes + edges) into the AgentPlan contract
 * the backend executes: position-ordered topological steps, `dependsOn` from
 * edges, `{{node.*}}` rewritten to `{{steps.*}}`. Saving keeps `{{inputs.x}}`
 * placeholders; a direct run re-compiles with the run-form values bound in.
 */
export function compileFlowPlan(
  toolNodes: ToolCanvasNode[],
  edges: DirectedEdge[],
  options: CompileFlowPlanOptions,
): CompiledFlowPlan {
  if (!toolNodes.length) throw new Error(i18n.global.t('agent.canvasEmpty'))
  const ordered = topologicallySortNodes(toolNodes, edges)
  if (!ordered) throw new Error(i18n.global.t('agent.canvasCycle'))
  const unavailable = ordered.filter((node) => node.data.available === false)
  if (unavailable.length) {
    throw new Error(i18n.global.t('agent.canvasUnavailableTools', {
      names: unavailable.map((node) => node.data.toolName).join(', '),
    }))
  }
  const indexes = new Map(ordered.map((node, index) => [node.id, index]))
  const incoming = new Map<string, number[]>()
  for (const edge of edges) {
    const source = indexes.get(edge.source)
    if (source === undefined || !indexes.has(edge.target)) continue
    const prerequisites = incoming.get(edge.target) ?? []
    prerequisites.push(source)
    incoming.set(edge.target, prerequisites)
  }
  const runInputs = options.bindInputs ? (options.inputs ?? {}) : {}
  const workflowGoal = String(bindWorkflowInputReferences(
    options.goal.trim() || options.defaultGoal || i18n.global.t('agent.canvasDefaultGoal'),
    runInputs,
  ).value)
  const steps: AgentStep[] = ordered.map((node, index) => {
    const data = node.data
    const args = parseJsonObject(data.argsText)
    if (!args) {
      throw new Error(i18n.global.t('agent.canvasInvalidArgs', { name: data.toolName }))
    }
    const bound = options.bindInputs ? bindWorkflowInputReferences(args, runInputs) : null
    if (bound?.missing.length) {
      throw new Error(i18n.global.t('agent.canvasMissingInputs', {
        names: bound.missing.join(', '),
      }))
    }
    return {
      index,
      toolName: data.toolName,
      args: replaceNodeReferences(bound ? bound.value : args, indexes, index) as Record<string, unknown>,
      description: data.description || data.toolName,
      requiresApproval: data.requiresApproval,
      dependsOn: [...new Set(incoming.get(node.id) ?? [])].sort((a, b) => a - b),
      status: 'pending',
      ...(data.retryPolicy ? { retryPolicy: data.retryPolicy } : {}),
      ...(data.pinnedOutput !== undefined ? { pinnedResult: data.pinnedOutput } : {}),
    }
  })
  const layout: Record<string, WorkflowNodeLayout> = {}
  ordered.forEach((node, index) => {
    layout[String(index)] = roundPosition(node.position)
  })
  return {
    plan: {
      goal: workflowGoal,
      steps,
      reasoning: i18n.global.t('agent.canvasReasoning'),
    },
    layout,
    orderedIds: ordered.map((node) => node.id),
  }
}

// ── dirty tracking snapshot ───────────────────────────────────────────────

/**
 * Stable serialization of the editable canvas state (metadata + executable nodes
 * + edges + notes + start) used as the unsaved-changes snapshot.
 */
export function serializeCanvasSnapshot(input: {
  name: string
  description: string
  goal: string
  inputSchemaText: string
  nodes: FlowCanvasNode[]
  edges: Array<{ source: string; target: string }>
}): string {
  const idIndex = new Map(input.nodes.map((node, index) => [node.id, index]))
  const tools = input.nodes.filter(isToolNode)
  const notes = input.nodes.filter(isStickyNode)
  const start = input.nodes.find(isStartNode)
  return JSON.stringify({
    name: input.name.trim(),
    description: input.description.trim(),
    goal: input.goal.trim(),
    inputSchema: parseJsonObject(input.inputSchemaText) ?? {},
    nodes: tools.map((node) => ({
      toolName: node.data.toolName,
      argsText: node.data.argsText,
      description: node.data.description,
      requiresApproval: node.data.requiresApproval,
      ...(node.data.title ? { title: node.data.title } : {}),
      // retryPolicy + pinnedOutput are authoring state (compiled into the plan);
      // lastRun/lastRunAt are runtime capture — excluded so a finished run never
      // trips the unsaved-changes guard.
      ...(node.data.retryPolicy ? { retryPolicy: node.data.retryPolicy } : {}),
      ...(node.data.pinnedOutput !== undefined ? { pinnedOutput: node.data.pinnedOutput } : {}),
      x: Math.round(node.position.x),
      y: Math.round(node.position.y),
    })),
    edges: input.edges
      .map((edge) => {
        const source = idIndex.get(edge.source)
        const target = idIndex.get(edge.target)
        return source === undefined || target === undefined ? null : [source, target] as const
      })
      .filter((edge): edge is readonly [number, number] => edge !== null)
      .sort((a, b) => a[0] - b[0] || a[1] - b[1]),
    notes: notes.map((node) => ({
      content: node.data.content,
      color: node.data.color,
      x: Math.round(node.position.x),
      y: Math.round(node.position.y),
    })),
    ...(start ? {
      start: {
        title: start.data.title ?? '',
        x: Math.round(start.position.x),
        y: Math.round(start.position.y),
      },
    } : {}),
  })
}

/** FNV-1a fingerprint of the canvas snapshot — the flow-chat context snapshot id. */
export function flowSnapshotId(serializedCanvas: string): string {
  let hash = 0x811c9dc5
  for (let index = 0; index < serializedCanvas.length; index += 1) {
    hash ^= serializedCanvas.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193)
  }
  return `v1-${(hash >>> 0).toString(16).padStart(8, '0')}`
}

// ── run-form input schema (Start node key-value editor) ───────────────────

export type FlowInputFieldType = 'string' | 'number' | 'boolean' | 'object' | 'array'

export interface FlowInputField {
  name: string
  title: string
  type: FlowInputFieldType
  required: boolean
  defaultValue: string
}

const FIELD_TYPES: FlowInputFieldType[] = ['string', 'number', 'boolean', 'object', 'array']

/** Turns the workflow input_schema JSON into editable key-value rows. */
export function parseInputSchemaFields(schemaText: string): FlowInputField[] {
  const schema = parseJsonObject(schemaText)
  const properties = (schema?.properties ?? {}) as Record<string, Record<string, unknown>>
  const required = new Set(Array.isArray(schema?.required)
    ? (schema.required as string[])
    : [])
  return Object.entries(properties).map(([name, property]) => {
    const type = FIELD_TYPES.includes(property.type as FlowInputFieldType)
      ? property.type as FlowInputFieldType
      : 'string'
    const rawDefault = property.default
    return {
      name,
      title: typeof property.title === 'string' && property.title ? property.title : humanizeWorkflowField(name),
      type,
      required: required.has(name),
      defaultValue: rawDefault === undefined || rawDefault === null
        ? ''
        : typeof rawDefault === 'string' ? rawDefault : JSON.stringify(rawDefault),
    }
  })
}

/** Rebuilds the schema JSON from the Start inspector's key-value rows. */
export function buildInputSchemaText(fields: FlowInputField[]): string {
  const properties: Record<string, Record<string, unknown>> = {}
  const required: string[] = []
  for (const field of fields) {
    const name = field.name.trim()
    if (!name) continue
    const property: Record<string, unknown> = { type: field.type }
    const title = field.title.trim()
    // Only authored titles persist — the humanized field name is the display default.
    if (title && title !== humanizeWorkflowField(name)) property.title = title
    const rawDefault = field.defaultValue.trim()
    if (rawDefault) {
      if (field.type === 'number') {
        const parsed = Number(rawDefault)
        property.default = Number.isFinite(parsed) ? parsed : rawDefault
      } else if (field.type === 'boolean') {
        property.default = rawDefault === 'true'
      } else if (field.type === 'object' || field.type === 'array') {
        try {
          property.default = JSON.parse(rawDefault)
        } catch {
          // An unparseable structured default is dropped rather than persisted as text.
        }
      } else {
        property.default = rawDefault
      }
    }
    properties[name] = property
    if (field.required) required.push(name)
  }
  return JSON.stringify({
    type: 'object',
    properties,
    ...(required.length ? { required } : {}),
  }, null, 2)
}
