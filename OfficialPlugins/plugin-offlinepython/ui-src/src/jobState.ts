export type UiJobStatus = 'idle' | 'starting' | 'running' | 'done' | 'failed' | 'cancelled' | 'error'

export interface JobSnapshot {
  ok: boolean
  summary: string
  status: UiJobStatus
  logs: string[]
  done: boolean
  error?: string
}

/**
 * Structural envelope a build/deploy status snapshot carries. The generated `BuildStatusOutput` /
 * `DeployStatusOutput` types are structurally compatible, so panels pass the typed result straight
 * in; plain object literals (tests) work too. No index signature, so it accepts the named-field
 * generated types without a TS2345 error.
 */
export interface JobSnapshotResult {
  success: boolean
  summary: string
  status?: string
  logs?: unknown[]
  done?: boolean
  error?: string | null
}

export function statusKey(status: string | undefined): UiJobStatus {
  switch (status?.toUpperCase()) {
    case 'RUNNING': return 'running'
    case 'DONE': return 'done'
    case 'FAILED': return 'failed'
    case 'CANCELLED': return 'cancelled'
    case 'IDLE': return 'idle'
    case 'STARTING': return 'starting'
    default: return 'error'
  }
}

export function readJobSnapshot(result: JobSnapshotResult): JobSnapshot {
  if (!result.success) {
    return { ok: false, summary: result.summary, status: 'error', logs: [], done: true }
  }
  const error = typeof result.error === 'string' ? result.error : undefined
  const logs = Array.isArray(result.logs) ? result.logs.filter((line): line is string => typeof line === 'string') : []
  return {
    ok: true,
    summary: result.summary,
    status: statusKey(typeof result.status === 'string' ? result.status : undefined),
    logs,
    done: result.done === true,
    ...(error ? { error } : {}),
  }
}

/**
 * Panel → shell activity report for the shared T4 header/status bar. Panels with
 * running jobs push their live state up so App can render the StatusChip + stop
 * affordance (and clear it on unmount). Structural match with the kit's StatusTone.
 */
export interface PanelActivity {
  running: boolean
  tone: 'success' | 'warning' | 'danger' | 'idle'
  label: string
  cancel?: () => void
}
