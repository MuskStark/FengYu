import { collectNodeReferences } from '@/lib/flowInspectorModel'
import type { FlowOutputField } from '@/lib/flowInspectorModel'

/**
 * Resolved-args preview: substitutes `{{node.*}}` / `{{inputs.*}}` references
 * in one node's args with the values the tool would actually receive — upstream
 * last-run results first, declared output examples second — so the author can
 * confirm the wiring before running. Display-only; the compiler stays the
 * authority for execution.
 */

export interface PreviewUpstreamNode {
  id: string
  /** The upstream node's configured args — the effective-inputs channel. */
  args: Record<string, unknown>
  /** Parsed last-run result (undefined when the node never ran). */
  lastRun: unknown
  inputs: FlowOutputField[]
  outputs: FlowOutputField[]
}

export interface ResolvedPreview {
  /** Args with references replaced by resolved values (display tree). */
  value: unknown
  /** References (exact form) that no upstream value or example resolved. */
  unresolved: string[]
}

interface ExampleAt {
  field?: FlowOutputField
}

function findField(fields: FlowOutputField[], path: string): ExampleAt {
  let segments = path.split(/[.[\]]/).filter(Boolean)
  let current = fields
  let match: FlowOutputField | undefined
  while (segments.length) {
    const segment = segments[0]
    segments = segments.slice(1)
    match = current.find((field) => field.name === segment
      || (/^\d+$/.test(segment) && field.name === `[${segment}]`))
    if (!match) return {}
    current = match.children ?? []
  }
  return { field: match }
}

/**
 * Resolves one reference against an upstream node. `result` reads the last-run
 * output (declared example as fallback); `input` reads the upstream node's
 * CONFIGURED ARGS — the effective-inputs channel the runtime serves — falling
 * back to the declared input tree's example.
 */
function resolveReference(
  reference: { nodeId: string; source: 'result' | 'input'; path: string },
  upstreamById: Map<string, PreviewUpstreamNode>,
): { resolved: true; value: unknown } | { resolved: false } {
  const upstream = upstreamById.get(reference.nodeId)
  if (!upstream) return { resolved: false }
  if (reference.source === 'input') {
    if (upstream.args !== undefined) {
      const value = reference.path === '' ? upstream.args : resolvePath(upstream.args, reference.path)
      if (value !== undefined) return { resolved: true, value: value }
    }
  } else if (upstream.lastRun !== undefined) {
    const value = reference.path === ''
      ? upstream.lastRun
      : resolvePath(upstream.lastRun, reference.path)
    if (value !== undefined) return { resolved: true, value: value }
  }
  const tree = reference.source === 'input' ? upstream.inputs : upstream.outputs
  const { field } = findField(tree, reference.path)
  if (field && field.examples.length && field.examples[0] !== undefined) {
    return { resolved: true, value: field.examples[0] }
  }
  return { resolved: false }
}

function resolvePath(value: unknown, path: string): unknown {
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

const INPUT_REFERENCE = /^\{\{inputs\.([A-Za-z0-9_.-]+)}}$/
const EMBEDDED_INPUT_REFERENCE = /\{\{inputs\.([A-Za-z0-9_.-]+)}}/g

export interface PreviewWorkflowInput {
  name: string
  default?: unknown
  example?: unknown
}

function resolveWorkflowInput(
  name: string,
  inputs: PreviewWorkflowInput[],
): { resolved: true; value: unknown } | { resolved: false } {
  const input = inputs.find((candidate) => candidate.name === name)
  if (!input) return { resolved: false }
  if (input.default !== undefined && input.default !== null && input.default !== '') {
    return { resolved: true, value: input.default }
  }
  if (input.example !== undefined && input.example !== null && input.example !== '') {
    return { resolved: true, value: input.example }
  }
  return { resolved: false }
}

/**
 * Deep-resolves every reference in an args tree. Unresolved references keep
 * their exact source text (the preview shows what is still pending) and are
 * collected into `unresolved`.
 */
export function resolveArgsPreview(
  args: Record<string, unknown>,
  upstream: PreviewUpstreamNode[],
  workflowInputs: PreviewWorkflowInput[],
): ResolvedPreview {
  const upstreamById = new Map(upstream.map((node) => [node.id, node]))
  const unresolved = new Set<string>()

  const visit = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(visit)
    if (value && typeof value === 'object') {
      return Object.fromEntries(
        Object.entries(value as Record<string, unknown>).map(([key, item]) => [key, visit(item)]),
      )
    }
    if (typeof value !== 'string') return value

    const inputMatch = INPUT_REFERENCE.exec(value)
    if (inputMatch) {
      const bound = resolveWorkflowInput(inputMatch[1].split('.')[0], workflowInputs)
      return bound.resolved ? bound.value : value
    }

    const references = collectNodeReferences(value)
    if (references.length === 1 && references[0] && `{{node.${references[0].nodeId}.${references[0].source}${references[0].path}}}` === value) {
      const bound = resolveReference(references[0], upstreamById)
      if (bound.resolved) return bound.value
      unresolved.add(value)
      return value
    }

    let sawNodeReference = false
    const substituted = value
      .replace(EMBEDDED_INPUT_REFERENCE, (reference, name: string) => {
        const bound = resolveWorkflowInput(name.split('.')[0], workflowInputs)
        if (!bound.resolved) return reference
        return typeof bound.value === 'string' ? bound.value : JSON.stringify(bound.value)
      })
      .replace(/\{\{node\.[A-Za-z0-9_-]+\.(?:result|input)(?:\.[A-Za-z0-9_-]+|\[\d+])*\}\}/g, (reference) => {
        sawNodeReference = true
        const parsed = collectNodeReferences(reference)[0]
        if (!parsed) return reference
        const bound = resolveReference(parsed, upstreamById)
        if (!bound.resolved) {
          unresolved.add(reference)
          return reference
        }
        return typeof bound.value === 'string' ? bound.value : JSON.stringify(bound.value)
      })
    return sawNodeReference || substituted !== value ? substituted : value
  }

  return { value: visit(args), unresolved: [...unresolved].sort() }
}
