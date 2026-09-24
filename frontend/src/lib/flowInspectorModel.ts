import type { AgentTool, FlowNodeDescriptor, FlowNodeInput } from '@/services/types'
import { humanizeWorkflowField } from '@/lib/flowDisplay'

/**
 * Pure model helpers behind the schema-driven node inspector (port of the Vue
 * workflow.ts helper layer): output/input trees with sensitive-field filtering,
 * the `{{node.*}}` reference grammar, descriptor display overlays, type
 * compatibility, and the dependency closure for single-step debug runs.
 */

export type FlowValueType = 'string' | 'number' | 'boolean' | 'object' | 'array' | 'file' | 'any'

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

export interface NodeReference {
  nodeId: string
  source: 'result' | 'input'
  /** Path after the source, e.g. `.files[0].name`; '' for the whole value. */
  path: string
}

export function formatNodeReference(reference: NodeReference): string {
  return `{{node.${reference.nodeId}.${reference.source}${reference.path}}}`
}

/** Parses an EXACT single reference; embedded templates return null. */
export function parseNodeReference(value: unknown): NodeReference | null {
  if (typeof value !== 'string') return null
  const exact = /^\{\{node\.([A-Za-z0-9_-]+)\.(result|input)((?:\.[A-Za-z0-9_-]+|\[\d+])*)}}$/.exec(value)
  return exact ? { nodeId: exact[1], source: exact[2] as 'result' | 'input', path: exact[3] } : null
}

/** All references (exact or embedded) inside one string value. */
export function collectNodeReferences(value: string): NodeReference[] {
  return [...value.matchAll(/\{\{node\.([A-Za-z0-9_-]+)\.(result|input)((?:\.[A-Za-z0-9_-]+|\[\d+])*)}}/g)]
    .map((match) => ({ nodeId: match[1], source: match[2] as 'result' | 'input', path: match[3] }))
}

/**
 * Order executable schema fields by the Flow descriptor's presentation order. Fields that
 * have no overlay remain visible afterwards in their original schema order. The schema
 * still owns the field set and types; this helper only applies the descriptor's UI ordering.
 */
export function orderedInputEntries<T>(
  properties: Record<string, T>,
  inputs?: Array<{ name: string }> | null,
): Array<[string, T]> {
  const entries = Object.entries(properties)
  if (!inputs?.length) return entries
  const order = new Map(inputs.map((input, index) => [input.name, index]))
  return entries
    .map((entry, schemaIndex) => ({ entry, schemaIndex }))
    .sort((left, right) => {
      const leftOrder = order.get(left.entry[0])
      const rightOrder = order.get(right.entry[0])
      if (leftOrder !== undefined && rightOrder !== undefined) return leftOrder - rightOrder
      if (leftOrder !== undefined) return -1
      if (rightOrder !== undefined) return 1
      return left.schemaIndex - right.schemaIndex
    })
    .map(({ entry }) => entry)
}

export type SchemaProperty = Record<string, unknown>

/** Applies a Flow input's display overlay without duplicating its executable schema. */
export function effectiveFlowInputSchema(schema: SchemaProperty, input?: FlowNodeInput): SchemaProperty {
  if (!input) return { ...schema }
  const base: SchemaProperty = {
    ...schema,
    title: input.title ?? schema.title,
    description: input.description ?? input.help ?? schema.description,
    examples: input.examples ?? schema.examples,
  }
  const widget = input.widget ?? (input.options?.length ? 'select' : undefined)
  if (widget === 'select' && input.options) {
    base.enum = input.options.map((option) => typeof option === 'string' ? option : option.value)
  }
  if (widget === 'textarea') base['x-fengyu-multiline'] = true
  if (widget === 'json') base['x-fengyu-json-editor'] = true
  if (widget !== 'rows' || !schema.items || typeof schema.items !== 'object') return base
  const items = schema.items as SchemaProperty
  const sourceProperties = (items.properties ?? {}) as Record<string, SchemaProperty>
  const properties = Object.fromEntries(orderedInputEntries(sourceProperties, input.fields))
  for (const field of input.fields ?? []) {
    const child = properties[field.name]
    if (!child) continue
    properties[field.name] = {
      ...child,
      title: field.title ?? child.title,
      ...(field.optionsFrom ? { 'x-fengyu-options-from': field.optionsFrom } : {}),
      ...(field.optionsFromContext ? { 'x-fengyu-options-from-context': field.optionsFromContext } : {}),
    }
  }
  return { ...base, items: { ...items, properties } }
}

/**
 * Target node plus all prerequisites needed for an isolated debug run. Structural edges cover
 * stateful chains (configure → execute); authored references cover data dependencies even when a
 * legacy graph is missing the corresponding visual edge.
 */
export function workflowDependencyClosure(
  targetNodeId: string,
  nodes: Array<{ id: string; data?: { argsText?: string } }>,
  edges: Array<{ source: string; target: string }>,
): Set<string> {
  const closure = new Set<string>([targetNodeId])
  const queue = [targetNodeId]
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const collectReferences = (value: unknown, into: Set<string>): void => {
    if (Array.isArray(value)) {
      value.forEach((item) => collectReferences(item, into))
      return
    }
    if (value && typeof value === 'object') {
      Object.values(value).forEach((item) => collectReferences(item, into))
      return
    }
    if (typeof value === 'string') {
      collectNodeReferences(value).forEach((reference) => into.add(reference.nodeId))
    }
  }
  while (queue.length) {
    const current = queue.shift()!
    const prerequisites = new Set(edges
      .filter((edge) => edge.target === current)
      .map((edge) => edge.source))
    const node = byId.get(current)
    if (node) {
      try {
        collectReferences(JSON.parse(node.data?.argsText || '{}'), prerequisites)
      } catch {
        // The caller's compiler owns invalid-JSON reporting; dependency discovery stays total.
      }
    }
    for (const prerequisite of prerequisites) {
      if (!byId.has(prerequisite) || closure.has(prerequisite)) continue
      closure.add(prerequisite)
      queue.push(prerequisite)
    }
  }
  return closure
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

/** One row of the recursive output tree (display overlay merged onto outputSchema). */
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
  'x-fengyu-sensitive'?: boolean
}

const SENSITIVE_FIELD = /(?:password|passwd|secret|token|credential)/i

function inputPropertyIsSensitive(name: string, property: OutputLikeProperty): boolean {
  if (property['x-fengyu-sensitive'] === true) return true
  return property['x-fengyu-sensitive'] !== false && SENSITIVE_FIELD.test(name)
}

function outputFieldFrom(path: string, name: string, property: OutputLikeProperty): FlowOutputField {
  const field: FlowOutputField = {
    path,
    name,
    title: property.title || humanizeWorkflowField(name),
    type: normalizeFlowType(property.type),
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

function mergeOutputDisplay(
  schema: OutputLikeProperty | undefined,
  overlay?: {
    title?: string
    description?: string
    help?: string
    examples?: unknown[]
    properties?: Record<string, unknown>
    items?: unknown
  },
): OutputLikeProperty {
  const merged: OutputLikeProperty = { ...(schema ?? {}) }
  if (!overlay) return merged
  if (overlay.title !== undefined) merged.title = overlay.title
  if (overlay.description !== undefined || overlay.help !== undefined) {
    merged.description = overlay.description ?? overlay.help
  }
  if (overlay.examples !== undefined) merged.examples = overlay.examples
  if (overlay.properties) {
    const schemaProperties = schema?.properties ?? {}
    merged.properties = { ...schemaProperties }
    for (const [name, child] of Object.entries(overlay.properties)) {
      if (!schemaProperties[name]) continue
      merged.properties[name] = mergeOutputDisplay(
        schemaProperties[name], child as Parameters<typeof mergeOutputDisplay>[1])
    }
  }
  if (overlay.items && schema?.items) {
    merged.items = mergeOutputDisplay(schema?.items,
      overlay.items as Parameters<typeof mergeOutputDisplay>[1])
  }
  return merged
}

/**
 * The recursive output tree one node offers downstream. The RPC outputSchema owns
 * fields and types; the descriptor contributes display metadata only. Schema fields
 * omitted by the overlay remain discoverable.
 */
export function workflowOutputTree(tool: AgentTool): FlowOutputField[] {
  const declared = tool.flowNode?.outputs ?? []
  let schemaProperties: Record<string, OutputLikeProperty> = {}
  try {
    const schema = JSON.parse(tool.outputSchema || '{}') as { properties?: Record<string, OutputLikeProperty> }
    schemaProperties = schema.properties ?? {}
  } catch {
    schemaProperties = {}
  }
  const seen = new Set<string>()
  const fields: FlowOutputField[] = []
  for (const port of declared) {
    if (seen.has(port.name)) continue
    seen.add(port.name)
    const schemaSibling = schemaProperties[port.name]
    const property = mergeOutputDisplay(schemaSibling, port)
    fields.push(outputFieldFrom(port.name ? `.${port.name}` : '', port.name, property))
  }
  for (const [name, property] of Object.entries(schemaProperties)) {
    if (seen.has(name) || name === 'success' || name === 'summary') continue
    seen.add(name)
    fields.push(outputFieldFrom(`.${name}`, name, property))
  }
  return fields
}

/**
 * Safe effective inputs an upstream node offers to downstream nodes. Inputs are a
 * first-class Flow channel, so plugins no longer need to duplicate them as
 * output declarations. Sensitive fields are omitted at every nesting level; the
 * runner applies the same filter before retaining effective inputs in memory.
 */
export function workflowInputTree(tool: AgentTool): FlowOutputField[] {
  let schema: { properties?: Record<string, OutputLikeProperty> }
  try {
    schema = JSON.parse(tool.inputSchema || '{}') as typeof schema
  } catch {
    return []
  }
  const sanitize = (property: OutputLikeProperty): OutputLikeProperty => {
    const safe: OutputLikeProperty = { ...property }
    if (property.properties) {
      safe.properties = Object.fromEntries(Object.entries(property.properties)
        .filter(([name, child]) => !inputPropertyIsSensitive(name, child))
        .map(([name, child]) => [name, sanitize(child)]))
    }
    if (property.items) safe.items = sanitize(property.items)
    return safe
  }
  return Object.entries(schema.properties ?? {})
    .filter(([name, property]) => !inputPropertyIsSensitive(name, property))
    .map(([name, property]) => outputFieldFrom(`.${name}`, name, sanitize(property)))
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

/** Human display title of a tool node: author title > descriptor label > humanized tool name. */
export function workflowNodeTitle(
  data: { toolName: string; title?: string },
  tool?: AgentTool | null,
): string {
  return data.title || tool?.flowNode?.label || humanizeWorkflowField(tool?.flowNode?.tool || data.toolName)
}

/** The tool's flow descriptor, when the catalog declares one for this node. */
export function nodeDescriptor(tool?: AgentTool | null): FlowNodeDescriptor | null {
  return tool?.flowNode ?? null
}
