import type { FlowGraph } from '@/api/types'

const PREFIX = 'fengyu.flow-draft.v1:'
const MAX_AGE_MS = 30 * 24 * 60 * 60 * 1_000

export interface LocalFlowDraft {
  version: 1
  workflowId: string | null
  baseRevision: number | null
  savedAt: string
  name: string
  description: string
  goal: string
  inputSchemaText: string
  graph: FlowGraph
}

export type FlowDraftRecoveryMode = 'current' | 'stale-copy'

/** Never apply a draft based on an older server revision as an update to that workflow. */
export function flowDraftRecoveryMode(
  draft: LocalFlowDraft,
  currentRevision: number | null,
): FlowDraftRecoveryMode {
  if (draft.workflowId === null) return 'current'
  return draft.baseRevision === currentRevision ? 'current' : 'stale-copy'
}

function key(workflowId: string | null): string {
  return `${PREFIX}${workflowId ?? 'new'}`
}

function storageAvailable(storage?: Storage): storage is Storage {
  return !!storage
}

export function loadFlowDraft(workflowId: string | null, storage?: Storage): LocalFlowDraft | null {
  if (!storageAvailable(storage)) return null
  try {
    const raw = storage.getItem(key(workflowId))
    if (!raw) return null
    const draft = JSON.parse(raw) as Partial<LocalFlowDraft>
    const savedAt = Date.parse(draft.savedAt ?? '')
    if (draft.version !== 1 || draft.workflowId !== workflowId || !Number.isFinite(savedAt)
      || Date.now() - savedAt > MAX_AGE_MS || !draft.graph
      || !Array.isArray(draft.graph.nodes) || !Array.isArray(draft.graph.edges)) {
      storage.removeItem(key(workflowId))
      return null
    }
    return draft as LocalFlowDraft
  } catch {
    return null
  }
}

export function saveFlowDraft(draft: LocalFlowDraft, storage?: Storage): void {
  if (!storageAvailable(storage)) return
  try {
    storage.setItem(key(draft.workflowId), JSON.stringify(draft))
  } catch {
    // Private browsing, quota limits, or locked-down webviews must not break editing.
  }
}

export function removeFlowDraft(workflowId: string | null, storage?: Storage): void {
  if (!storageAvailable(storage)) return
  try { storage.removeItem(key(workflowId)) } catch { /* best effort */ }
}
