import type { Node } from '@vue-flow/core'
import type { AgentTool } from '@/api/types'

export interface WorkflowNodeData {
  tool: AgentTool
  argsText: string
  description: string
  requiresApproval: boolean
  available: boolean
}

export type WorkflowFlowNode = Node<WorkflowNodeData> & {
  type: 'tool'
  data: WorkflowNodeData
}

interface InputProperty {
  type?: string
  default?: unknown
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
