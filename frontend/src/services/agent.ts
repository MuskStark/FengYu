/**
 * Agent domain — plan-and-execute runs, history, tasks, schedules, and the
 * agent run stream (bounded reconnect + replay dedup) plus the pure payload
 * helpers the run-state consumer needs (ported from the legacy Vue composable,
 * which mixed transport and UI state; here transport lives in impl/streams
 * and state stays with the caller).
 */
import type {
  AgentBatchResponse,
  AgentPlan,
  AgentRunDetail,
  AgentRunRequest,
  AgentRunResponse,
  AgentRunSummary,
  AgentScheduleSummary,
  AgentStep,
  AgentStepRetryEvent,
  AgentTaskCapacity,
  AgentTaskSummary,
  AgentTool,
  AiPermissionMode,
  CalendarSchedule,
} from './types'
import { http } from './impl/http'
import {
  openAgentRunStream,
  newAgentStreamSeqState,
  isAgentEventReplayed,
  type AgentRunStreamHandlers,
  type AgentStreamSeqState,
  type StreamHandle,
} from './impl/streams'

export type { AgentRunStreamHandlers, AgentStreamSeqState, StreamHandle }
export { newAgentStreamSeqState, isAgentEventReplayed }

export interface AgentService {
  run(req: AgentRunRequest): Promise<AgentRunResponse>
  /** Start 1–8 independent runs concurrently. */
  batch(goals: string[], config: AgentRunRequest['config']): Promise<AgentBatchResponse>
  /**
   * Release the run's approval gate (plan or step); an edited plan body replaces it.
   * `gateId` is the credential from the approval-request SSE event — when supplied the
   * backend verifies it against the currently armed gate and answers 409 on mismatch,
   * duplicate, or late approve (the caller refreshes run state in that case).
   */
  approve(runId: string, plan?: AgentPlan, gateId?: string): Promise<unknown>
  /** Flip the run's cancellation flag (honored cooperatively by the runner). */
  cancel(runId: string): Promise<unknown>
  /**
   * The orchestrable tool list (name/description/inputSchema). The backend
   * serializes the flow-node descriptor as a JSON string — parsed here so
   * callers always see the object form.
   */
  tools(): Promise<AgentTool[]>
  runs(): Promise<AgentRunSummary[]>
  runDetail(runId: string): Promise<AgentRunDetail>
  /** Resume only the unfinished portion of a failed/cancelled run, after plan review. */
  resume(runId: string): Promise<AgentRunResponse>
  runsQuery(q: string, limit?: number): Promise<AgentRunSummary[]>
  forkRun(runId: string): Promise<{ runId: string }>
  rewindRun(runId: string, keepSteps: number): Promise<{ runId: string }>
  tasks(): Promise<AgentTaskSummary[]>
  taskCapacity(): Promise<AgentTaskCapacity>
  killTask(taskId: string): Promise<{ ok: boolean }>
  schedules(): Promise<AgentScheduleSummary[]>
  createSchedule(request: {
    workflowId: string
    inputs: Record<string, unknown>
    intervalSeconds: number
    recurring: boolean
    fireImmediately: boolean
    calendar?: CalendarSchedule
    permissionMode?: AiPermissionMode
  }): Promise<AgentScheduleSummary>
  deleteSchedule(scheduleId: string): Promise<{ ok: boolean }>
  openRunStream(runId: string, handlers: AgentRunStreamHandlers): StreamHandle
}

export const agentService: AgentService = {
  run: (req) => http.post<AgentRunResponse>('/api/agent/run', req).then((r) => r.data),
  batch: (goals, config) =>
    http.post<AgentBatchResponse>('/api/agent/batch', { goals, config }).then((r) => r.data),
  approve: (runId, plan, gateId) =>
    http
      .post(`/api/agent/${encodeURIComponent(runId)}/approve`, plan
        ? { goal: plan.goal, steps: plan.steps, reasoning: plan.reasoning, gateId }
        : gateId
          ? { gateId }
          : undefined)
      .then((r) => r.data),
  cancel: (runId) =>
    http.post(`/api/agent/${encodeURIComponent(runId)}/cancel`).then((r) => r.data),
  tools: () =>
    http.get<AgentTool[]>('/api/agent/tools').then((r) => r.data.map((tool) => ({
      ...tool,
      flowNode: typeof tool.flowNode === 'string'
        ? (JSON.parse(tool.flowNode) as AgentTool['flowNode'])
        : tool.flowNode ?? null,
    }))),
  runs: () => http.get<AgentRunSummary[]>('/api/agent/runs').then((r) => r.data),
  runDetail: (runId) =>
    http.get<AgentRunDetail>(`/api/agent/runs/${encodeURIComponent(runId)}`).then((r) => r.data),
  resume: (runId) =>
    http.post<AgentRunResponse>(`/api/agent/runs/${encodeURIComponent(runId)}/resume`).then((r) => r.data),
  runsQuery: (q, limit = 50) =>
    http.get<AgentRunSummary[]>(`/api/agent/runs?q=${encodeURIComponent(q)}&limit=${limit}`).then((r) => r.data),
  forkRun: (runId) =>
    http.post<{ runId: string }>(`/api/agent/runs/${encodeURIComponent(runId)}/fork`).then((r) => r.data),
  rewindRun: (runId, keepSteps) =>
    http.post<{ runId: string }>(`/api/agent/runs/${encodeURIComponent(runId)}/rewind`,
      { keepSteps }).then((r) => r.data),
  tasks: () => http.get<AgentTaskSummary[]>('/api/agent/tasks').then((r) => r.data),
  taskCapacity: () => http.get<AgentTaskCapacity>('/api/agent/tasks/capacity').then((r) => r.data),
  killTask: (taskId) =>
    http.delete<{ ok: boolean }>(`/api/agent/tasks/${encodeURIComponent(taskId)}`).then((r) => r.data),
  schedules: () => http.get<AgentScheduleSummary[]>('/api/agent/schedules').then((r) => r.data),
  createSchedule: (request) =>
    http.post<AgentScheduleSummary>('/api/agent/schedules', request).then((r) => r.data),
  deleteSchedule: (scheduleId) =>
    http.delete<{ ok: boolean }>(`/api/agent/schedules/${encodeURIComponent(scheduleId)}`).then((r) => r.data),
  openRunStream: (runId, handlers) => openAgentRunStream(runId, handlers),
}

/** Strictly normalize a live or persisted `step_retry` payload. */
export function agentStepRetryFromData(
  data: Record<string, unknown>,
  createdAt?: string,
): { index: number; retry: AgentStepRetryEvent } | null {
  const index = Number(data.index)
  const nextAttempt = Number(data.nextAttempt)
  const maxAttempts = Number(data.maxAttempts)
  const delayMs = Number(data.delayMs)
  if (!Number.isInteger(index) || index < 0
    || !Number.isInteger(nextAttempt) || nextAttempt < 2
    || !Number.isInteger(maxAttempts) || maxAttempts < nextAttempt
    || !Number.isFinite(delayMs) || delayMs < 0) return null
  return {
    index,
    retry: {
      nextAttempt,
      maxAttempts,
      delayMs,
      error: typeof data.error === 'string' ? data.error : '',
      ...(createdAt ? { createdAt } : {}),
    },
  }
}

/**
 * Strictly extract the approval-gate credential from a `plan_approval_requested` /
 * `step_approval_requested` payload. A missing or empty gateId (older backend)
 * yields null — the caller then falls back to the credential-less legacy approve.
 */
export function agentGateIdFromData(data: Record<string, unknown> | null | undefined): string | null {
  const gateId = data?.gateId
  return typeof gateId === 'string' && gateId ? gateId : null
}

/**
 * A terminal stream error has no step index, but execution is sequential and
 * any running/retrying step is necessarily the one that failed. Returns a fresh
 * map so observers immediately replace spinner badges with failure badges.
 */
export function failActiveAgentSteps(current: Map<number, AgentStep>): Map<number, AgentStep> {
  let changed = false
  const next = new Map<number, AgentStep>()
  for (const [index, step] of current) {
    if (step.status === 'running' || step.status === 'retrying') {
      next.set(index, { ...step, status: 'failed' })
      changed = true
    } else {
      next.set(index, step)
    }
  }
  return changed ? next : current
}
