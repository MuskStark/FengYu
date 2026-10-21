/**
 * The `.fyf` FengYu Flow interchange document (client half of
 * `WorkflowDslService`): parse/validate a picked-up file, trigger a browser
 * download of an export, and hand a graph-only document to the builder (which
 * owns graph→plan compilation — a hand-edited file must never ship a plan that
 * disagrees with its graph, so the file itself carries no derived plan).
 */
import type { AgentPlan, FlowGraph } from '@/services/types'

export const FYF_FORMAT = 'fengyu-flow'
export const FYF_SCHEMA_VERSION = 1

/** Loose on purpose: unknown keys from newer writers survive the round-trip. */
export interface FyfDocument {
  format?: unknown
  schemaVersion?: unknown
  generator?: unknown
  name?: unknown
  description?: unknown
  goal?: unknown
  inputSchema?: unknown
  graph?: unknown
  plan?: unknown
  layout?: unknown
  [key: string]: unknown
}

export type FyfParseResult =
  | { ok: true; doc: FyfDocument }
  | { ok: false; error: 'not-json' | 'not-fyf' | 'bad-version' }

/** Parses and envelope-validates a `.fyf` file body; plan/graph shape is validated later. */
export function parseFyfDocument(text: string): FyfParseResult {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return { ok: false, error: 'not-json' }
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { ok: false, error: 'not-fyf' }
  }
  const document = parsed as FyfDocument
  if (document.format !== FYF_FORMAT) return { ok: false, error: 'not-fyf' }
  if (document.schemaVersion !== FYF_SCHEMA_VERSION) return { ok: false, error: 'bad-version' }
  return { ok: true, doc: document }
}

/** A document the builder can mount directly (graph is the canvas source of truth). */
export function fyfHasGraph(doc: FyfDocument): doc is FyfDocument & { graph: FlowGraph } {
  const graph = doc.graph
  return typeof graph === 'object' && graph !== null && !Array.isArray(graph)
    && Array.isArray((graph as FlowGraph).nodes)
}

/** A document the import API accepts (carries the compiled plan). */
export function fyfHasPlan(doc: FyfDocument): doc is FyfDocument & { plan: AgentPlan } {
  const plan = doc.plan
  return typeof plan === 'object' && plan !== null && !Array.isArray(plan)
    && Array.isArray((plan as AgentPlan).steps)
}

/** Filesystem-safe `<slug>.fyf` download name from the flow's display name. */
export function fyfFilename(name: string): string {
  const slug = name
    .replace(/[\s_]+/g, '-')
    .replace(/[^A-Za-z0-9-]/g, '')
    .replace(/-{2,}/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase()
  return `${slug || 'flow'}.fyf`
}

/** Triggers the browser download of an exported document. */
export function downloadFyf(doc: FyfDocument, flowName: string): void {
  const blob = new Blob([JSON.stringify(doc, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fyfFilename(flowName)
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1_000)
}

/** Template tools the live catalog lacks (missing plugin or disabled server). */
export function missingTemplateTools(requiredTools: string[],
  tools: Array<{ name: string }>): string[] {
  return requiredTools.filter((name) => !tools.some((tool) => tool.name === name))
}

// ── graph-only hand-off into the builder ──────────────────────────────────

/** sessionStorage channel FlowHome → FlowBuilder for files the API cannot import. */
export const FLOW_FYF_IMPORT_KEY = 'fengyu:flow-fyf-import:v1'

export function stashPendingFyfImport(doc: FyfDocument): void {
  try {
    sessionStorage.setItem(FLOW_FYF_IMPORT_KEY, JSON.stringify({ at: Date.now(), doc }))
  } catch {
    /* private-browsing — the FlowHome import error surfaces instead */
  }
}

/** Consumes a stashed graph-only document exactly once (null when none/invalid). */
export function takePendingFyfImport(): FyfDocument | null {
  try {
    const raw = sessionStorage.getItem(FLOW_FYF_IMPORT_KEY)
    if (!raw) return null
    sessionStorage.removeItem(FLOW_FYF_IMPORT_KEY)
    const stash = JSON.parse(raw) as { doc?: unknown }
    const doc = stash?.doc as FyfDocument | undefined
    if (!doc || doc.format !== FYF_FORMAT || !fyfHasGraph(doc)) return null
    return doc
  } catch {
    return null
  }
}
