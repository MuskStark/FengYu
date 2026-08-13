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
  title?: string
  description?: string
  default?: unknown
  enum?: unknown[]
  items?: InputProperty
}

export interface WorkflowSchemaProperty extends InputProperty {
  properties?: Record<string, WorkflowSchemaProperty>
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
