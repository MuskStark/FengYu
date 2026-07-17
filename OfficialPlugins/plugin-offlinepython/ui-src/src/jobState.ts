import type { RpcResult } from './rpc'

export type UiJobStatus = 'idle' | 'starting' | 'running' | 'done' | 'failed' | 'cancelled' | 'error'

export interface JobSnapshot {
  ok: boolean
  summary: string
  status: UiJobStatus
  logs: string[]
  done: boolean
  error?: string
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

export function readJobSnapshot(result: RpcResult): JobSnapshot {
  if (!result.success) {
    return { ok: false, summary: result.summary, status: 'error', logs: [], done: true }
  }
  const error = typeof result.error === 'string' ? result.error : undefined
  return {
    ok: true,
    summary: result.summary,
    status: statusKey(typeof result.status === 'string' ? result.status : undefined),
    logs: Array.isArray(result.logs) ? result.logs.filter((line): line is string => typeof line === 'string') : [],
    done: result.done === true,
    ...(error ? { error } : {}),
  }
}
