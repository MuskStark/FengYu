import type { Edge } from '@xyflow/react'
import { NODE_REFERENCE_PATTERN, STICKY_COLORS, type FlowCanvasNode, type FlowStickyColor, type FlowToolData } from '@/lib/flowGraph'

/**
 * Canvas clipboard: serializes a tool/sticky selection into a self-contained
 * payload and re-materializes it with fresh ids. References between pasted
 * nodes are rewritten to the new ids; references pointing OUT of the pasted
 * set are preserved verbatim (they still name live upstream nodes). Payloads
 * ride the plain-text system clipboard and are gated by the `kind` marker.
 */

export interface FlowClipboardPayload {
  kind: 'fengyu-flow-clipboard'
  version: 1
  nodes: Array<{
    id: string
    type: 'tool' | 'sticky'
    position: { x: number; y: number }
    data: unknown
  }>
  /** Edges whose BOTH endpoints are inside the selection. */
  edges: Array<{ source: string; target: string; sourceHandle?: string | null }>
}

/** Serializes the selected canvas nodes (start is never copyable — it is a singleton). */
export function buildFlowClipboard(
  nodes: FlowCanvasNode[],
  edges: Edge[],
  selectedIds: Set<string>,
): FlowClipboardPayload | null {
  const selected = nodes.filter((node): node is Exclude<FlowCanvasNode, { type: 'start' }> =>
    selectedIds.has(node.id) && node.type !== 'start')
  if (!selected.length) return null
  return {
    kind: 'fengyu-flow-clipboard',
    version: 1,
    nodes: selected.map((node) => ({
      id: node.id,
      type: node.type,
      position: { x: Math.round(node.position.x), y: Math.round(node.position.y) },
      data: JSON.parse(JSON.stringify(node.data)) as unknown,
    })),
    edges: edges
      .filter((edge) => selectedIds.has(edge.source) && selectedIds.has(edge.target))
      .map((edge) => ({
        source: edge.source,
        target: edge.target,
        sourceHandle: edge.sourceHandle ?? null,
      })),
  }
}
export function parseFlowClipboard(raw: string | null | undefined): FlowClipboardPayload | null {
  if (!raw) return null
  try {
    const value = JSON.parse(raw) as Partial<FlowClipboardPayload>
    if (value.kind !== 'fengyu-flow-clipboard' || value.version !== 1 || !Array.isArray(value.nodes)) {
      return null
    }
    return value as FlowClipboardPayload
  } catch {
    return null
  }
}

export interface PastedCanvas {
  nodes: FlowCanvasNode[]
  edges: Edge[]
}

/** Rewrites `{{node.<oldId>…}}` references pointing at remapped ids. */
export function rewriteNodeReferences(value: unknown, idMap: Map<string, string>): unknown {
  if (Array.isArray(value)) return value.map((item) => rewriteNodeReferences(item, idMap))
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .map(([key, item]) => [key, rewriteNodeReferences(item, idMap)]),
    )
  }
  if (typeof value !== 'string') return value
  return value.replace(NODE_REFERENCE_PATTERN, (reference, id: string) =>
    idMap.has(id) ? reference.replace(`node.${id}.`, `node.${idMap.get(id)}.`) : reference)
}

/**
 * Materializes a clipboard payload onto the canvas: every node gets a fresh id
 * (from the caller's minter), positions shift by `offset`, and internal
 * references/edges follow the id map. Runtime captures (lastRun) and pinned
 * outputs are dropped — a duplicate starts clean. Returns null for a payload
 * whose node data does not fit the current wire shape (ignored paste).
 */
export function materializeFlowClipboard(
  payload: FlowClipboardPayload,
  mintId: (kind: 'tool' | 'sticky') => string,
  offset: { x: number; y: number },
  makeEdge: (source: string, target: string, sourceHandle?: string | null) => Edge,
): PastedCanvas | null {
  const idMap = new Map(payload.nodes.map((node) => [node.id, mintId(node.type)]))
  const nodes: FlowCanvasNode[] = []
  for (const entry of payload.nodes) {
    const id = idMap.get(entry.id)!
    const position = {
      x: Math.round(entry.position.x + offset.x),
      y: Math.round(entry.position.y + offset.y),
    }
    if (entry.type === 'sticky') {
      const data = (entry.data ?? {}) as { content?: unknown; color?: unknown }
      const color = STICKY_COLORS.includes(data.color as FlowStickyColor)
        ? data.color as FlowStickyColor
        : 'yellow'
      nodes.push({
        id,
        type: 'sticky',
        position,
        data: { content: typeof data.content === 'string' ? data.content : '', color },
      })
      continue
    }
    const data = (entry.data ?? {}) as Partial<FlowToolData>
    if (typeof data.toolName !== 'string' || !data.toolName) return null
    const toolData: FlowToolData = {
      toolName: data.toolName,
      argsText: typeof data.argsText === 'string'
        ? rewriteNodeReferences(data.argsText, idMap) as string
        : '{}',
      description: typeof data.description === 'string' ? data.description : '',
      requiresApproval: Boolean(data.requiresApproval),
      ...(typeof data.title === 'string' && data.title ? { title: data.title } : {}),
      available: data.available !== false,
      ...(data.retryPolicy && typeof data.retryPolicy === 'object'
        ? { retryPolicy: data.retryPolicy } : {}),
      ...(typeof data.color === 'string' ? { color: data.color } : {}),
    }
    nodes.push({ id, type: 'tool', position, data: toolData })
  }
  const edges = payload.edges
    .filter((edge) => idMap.has(edge.source) && idMap.has(edge.target))
    .map((edge) => makeEdge(idMap.get(edge.source)!, idMap.get(edge.target)!, edge.sourceHandle ?? null))
  return { nodes, edges }
}
