import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api/client'
import { backendUrl } from '@/api/config'
import type { AgentPlan, AgentRunDetail, AgentRunSummary, AgentStep } from '@/api/types'

export type AgentRunStatus =
  | 'idle'
  | 'planning'
  | 'awaiting-plan'
  | 'running'
  /** A persisted RUNNING run opened read-only from history — no live stream attached. */
  | 'running-remote'
  | 'awaiting-step'
  | 'complete'
  | 'error'
  | 'cancelled'

/**
 * Replay-dedup state for one agent stream session. The backend sink re-buffers
 * events while no client is attached and replays them on reconnect, tagging every
 * payload with a monotonic per-run `seq` (starting at 1) — without dedup the
 * replayed prefix would re-drive every handler after each reconnect.
 */
export interface AgentStreamSeqState {
  lastSeq: number
}

/** A fresh high-water mark; each new run's replay starts dispatching from seq 1. */
export function newAgentStreamSeqState(): AgentStreamSeqState {
  return { lastSeq: 0 }
}

/**
 * Whether a parsed event payload is a replay this session has already dispatched.
 * A payload at or below the high-water mark is a replay (true); a higher `seq`
 * advances the mark. Payloads without a numeric `seq` (older backend, payloadless
 * events) never dedup.
 */
export function isAgentEventReplayed(payload: unknown, state: AgentStreamSeqState): boolean {
  if (!payload || typeof payload !== 'object') return false
  const seq = (payload as { seq?: unknown }).seq
  if (typeof seq !== 'number') return false
  if (seq <= state.lastSeq) return true
  state.lastSeq = seq
  return false
}

/**
 * Shared engine for consuming an agent run's SSE stream
 * (/api/agent/stream?runId=…), kept identical for the AI planner page and the
 * flow builder: status/steps/results state plus the ticket-based
 * reconnection logic.
 *
 * Auth: EventSource cannot set headers, so each connection redeems a one-time
 * `?ticket=` minted by the header-authenticated POST /api/agent/stream-ticket —
 * the full token never rides in a URL that proxy/access logs can capture.
 * Reconnection is managed here (not by the EventSource): a ticket is single-use,
 * so the browser's built-in retry would replay a spent ticket and die on 401.
 * On reconnect the backend replays the events buffered while no client was
 * attached; payload `seq` dedup (see {@link isAgentEventReplayed}) skips the
 * prefix this session already dispatched.
 */
export function useAgentRunStream(hooks?: {
  /** Called when a run reaches a terminal state (complete / error / cancel). */
  onSettled?: () => void
}) {
  const { t } = useI18n()

  const runId = ref<string | null>(null)
  const status = ref<AgentRunStatus>('idle')
  const planTokens = ref('')
  const plan = ref<AgentPlan | null>(null)
  /** Per-index step bookkeeping. Keyed by step.index (from step_start/step_complete). */
  const steps = ref<Map<number, AgentStep>>(new Map())
  /** Per-step execution output (step_complete / persisted run detail). */
  const stepResults = ref<Map<number, string>>(new Map())
  const summary = ref<string | null>(null)
  const errorMsg = ref<string | null>(null)
  const selectedHistoryId = ref<string | null>(null)
  /** Whether the NEXT plan_ready pauses for approval (canvas runs skip plan review). */
  const requirePlanApproval = ref(true)

  const busy = computed(() =>
    status.value === 'planning'
    || status.value === 'awaiting-plan'
    || status.value === 'running'
    || status.value === 'awaiting-step',
  )
  /** Ordered step list (steps Map → array for templates). */
  const stepList = computed(() =>
    Array.from(steps.value.values()).sort((a, b) => a.index - b.index))

  const STREAM_RETRY_LIMIT = 5
  const STREAM_RETRY_DELAY_MS = 800
  let streamRetries = 0
  // Incremented on every open/close so a ticket minted for an old stream is
  // discarded when a newer stream took over while the request was in flight.
  let streamEpoch = 0
  // Replay dedup for the CURRENT stream session. Survives reconnects (a reconnect
  // replays already-seen events), resets only when openStream starts a NEW run.
  let seqState = newAgentStreamSeqState()
  let es: EventSource | null = null

  function resetRunState() {
    plan.value = null
    planTokens.value = ''
    steps.value = new Map()
    stepResults.value = new Map()
    summary.value = null
    errorMsg.value = null
  }

  function openStream(id: string) {
    closeStream()
    streamRetries = 0
    // A NEW run replays from seq 1 and must dispatch everything; a mere reconnect
    // (connectStream from the drop path) keeps the session's high-water mark.
    seqState = newAgentStreamSeqState()
    const epoch = ++streamEpoch
    void connectStream(id, epoch)
  }

  function connectStream(id: string, epoch: number): Promise<void> {
    return api.issueStreamTicket('agent').then((ticket) => {
      if (epoch !== streamEpoch) return // a newer stream took over while minting
      const params = new URLSearchParams({ runId: id })
      params.set('ticket', ticket)
      es = new EventSource(backendUrl(`/api/agent/stream?${params.toString()}`))
      bindStreamHandlers(es, id, epoch)
    }).catch(() => {
      errorMsg.value = t('agent.failed')
      status.value = 'error'
    })
  }

  function bindStreamHandlers(source: EventSource, currentRunId: string, epoch: number) {
    const parse = <T>(ev: Event): T | null => {
      try {
        return JSON.parse((ev as MessageEvent).data) as T
      } catch {
        return null
      }
    }
    /** parse + replay dedup: null for unparseable payloads AND replayed ones. */
    const parseLive = <T>(ev: Event): T | null => {
      const d = parse<T>(ev)
      return d !== null && isAgentEventReplayed(d, seqState) ? null : d
    }

    // plan_token: a streamed planner delta — append to the live plan preview.
    source.addEventListener('plan_token', (ev) => {
      const d = parseLive<{ delta: string }>(ev)
      if (d) planTokens.value += d.delta
    })

    // plan_ready: the structured plan arrives; clear the token preview.
    source.addEventListener('plan_ready', (ev) => {
      const d = parseLive<{ goal: string; steps?: AgentStep[]; reasoning: string }>(ev)
      if (!d) return
      const ps = Array.isArray(d.steps) ? d.steps : []
      plan.value = { goal: d.goal, steps: ps, reasoning: d.reasoning ?? '' }
      // Seed step bookkeeping so the UI can show pending steps immediately.
      for (const s of ps) steps.value.set(s.index, { ...s, status: s.status || 'pending' })
      planTokens.value = ''
      if (requirePlanApproval.value) status.value = 'awaiting-plan'
      else status.value = 'running'
    })

    source.addEventListener('plan_approval_requested', (ev) => {
      // The payload is only read for replay dedup — a payloadless (older backend)
      // event still dispatches, matching the pre-dedup behavior.
      if (!isAgentEventReplayed(parse<unknown>(ev), seqState)) status.value = 'awaiting-plan'
    })

    source.addEventListener('step_start', (ev) => {
      const d = parseLive<{ index: number }>(ev)
      if (!d) return
      const existing = steps.value.get(d.index)
      if (existing) existing.status = 'running'
      else steps.value.set(d.index, { index: d.index, toolName: '', description: '', status: 'running' })
      status.value = 'running'
    })

    source.addEventListener('step_complete', (ev) => {
      const d = parseLive<{ index: number; result: string }>(ev)
      if (!d) return
      const existing = steps.value.get(d.index)
      if (existing) existing.status = 'complete'
      else steps.value.set(d.index, { index: d.index, toolName: '', description: d.result ?? '', status: 'complete' })
      stepResults.value.set(d.index, d.result ?? '')
      status.value = 'running'
    })

    source.addEventListener('step_approval_requested', (ev) => {
      const d = parseLive<{ index: number }>(ev)
      if (d) status.value = 'awaiting-step'
    })

    source.addEventListener('complete', (ev) => {
      const d = parse<{ summary: string }>(ev)
      // A replayed terminal event must not wipe the already-settled run; an
      // unparseable payload (null → no dedup) still completes, as before.
      if (isAgentEventReplayed(d, seqState)) return
      summary.value = d?.summary ?? ''
      status.value = 'complete'
      closeStream()
      hooks?.onSettled?.()
    })

    // Named "error" event from the backend carries a JSON message; the native
    // EventSource error (connection drop) has no parseable data.
    source.addEventListener('error', (ev) => {
      const d = parse<{ message: string }>(ev)
      if (d?.message) {
        errorMsg.value = d.message
        status.value = 'error'
        closeStream()
        hooks?.onSettled?.()
        return
      }
      // Native drop: the browser's built-in retry would replay the spent ticket (401),
      // so take over — close, mint a fresh ticket, reconnect, up to STREAM_RETRY_LIMIT.
      if (status.value === 'complete' || status.value === 'cancelled' || status.value === 'error') return
      source.close()
      if (es === source) es = null
      streamRetries += 1
      if (streamRetries >= STREAM_RETRY_LIMIT) {
        errorMsg.value = t('agent.failed')
        status.value = 'error'
        closeStream()
        hooks?.onSettled?.()
        return
      }
      window.setTimeout(() => {
        if (epoch === streamEpoch) void connectStream(currentRunId, epoch)
      }, STREAM_RETRY_DELAY_MS)
    })

    source.addEventListener('open', () => {
      streamRetries = 0
      if (status.value === 'idle') status.value = 'planning'
    })
  }

  function closeStream() {
    streamEpoch += 1
    if (es) {
      es.close()
      es = null
    }
  }

  async function approve() {
    if (!runId.value) return
    try {
      // Release the current plan/step gate without replacing the workflow.
      await api.agentApprove(runId.value)
      if (status.value === 'awaiting-plan' || status.value === 'awaiting-step') status.value = 'running'
    } catch (e) {
      errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
    }
  }

  async function cancel() {
    if (!runId.value) {
      status.value = 'cancelled'
      return
    }
    try {
      await api.agentCancel(runId.value)
    } finally {
      status.value = 'cancelled'
      closeStream()
      hooks?.onSettled?.()
    }
  }

  /** Backend execution status string → the shared step-status vocabulary. */
  function executionStatus(statusValue: string): string {
    if (statusValue === 'COMPLETED') return 'complete'
    if (statusValue === 'FAILED') return 'failed'
    if (statusValue === 'RUNNING') return 'running'
    return statusValue.toLowerCase().replaceAll('_', '-')
  }

  /** Restores a persisted run into the live panels; returns the detail (or null). */
  async function showPersistedRun(item: AgentRunSummary): Promise<AgentRunDetail | null> {
    try {
      const detail = await api.agentRunDetail(item.id)
      selectedHistoryId.value = detail.id
      runId.value = detail.id
      resetRunState()
      plan.value = detail.plan ?? null
      summary.value = detail.summary ?? null
      errorMsg.value = detail.error ?? null
      const restored = new Map<number, AgentStep>()
      for (const step of detail.plan?.steps ?? []) restored.set(step.index, { ...step })
      const restoredResults = new Map<number, string>()
      for (const execution of detail.executions) {
        const step = restored.get(execution.index)
        if (step) step.status = executionStatus(execution.status)
        if (execution.result) restoredResults.set(execution.index, execution.result)
      }
      steps.value = restored
      stepResults.value = restoredResults
      if (detail.status === 'COMPLETED') status.value = 'complete'
      else if (detail.status === 'CANCELLED') status.value = 'cancelled'
      else if (detail.status === 'FAILED') status.value = 'error'
      // A still-running persisted run is shown read-only (no stream attached): the
      // remote status is displayed, but it never counts as a busy local run.
      else if (detail.status === 'RUNNING') status.value = 'running-remote'
      return detail
    } catch (e) {
      errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
      return null
    }
  }

  /** Resumes a terminal run under plan review on a fresh peer. */
  async function resumePersisted(item: AgentRunSummary) {
    const detail = await showPersistedRun(item)
    if (!detail || !detail.plan || busy.value) return
    errorMsg.value = null
    summary.value = null
    requirePlanApproval.value = true
    status.value = 'planning'
    try {
      const { runId: id } = await api.agentResume(detail.id)
      runId.value = id
      selectedHistoryId.value = id
      openStream(id)
      hooks?.onSettled?.()
    } catch (e) {
      errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
      status.value = 'error'
    }
  }

  /** Forks a terminal run into a fresh peer executing the same plan. */
  async function forkRun(id: string) {
    if (busy.value) return
    try {
      const { runId: forked } = await api.agentForkRun(id)
      selectedHistoryId.value = forked
      runId.value = forked
      status.value = 'planning'
      openStream(forked)
    } catch (e) {
      errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
    }
  }

  /** Rewinds to just before step N (keeps steps < N) and resumes under plan review. */
  async function rewindToStep(index: number) {
    if (!runId.value || busy.value) return
    try {
      const { runId: rewound } = await api.agentRewindRun(runId.value, index)
      selectedHistoryId.value = rewound
      runId.value = rewound
      status.value = 'planning'
      summary.value = null
      errorMsg.value = null
      openStream(rewound)
    } catch (e) {
      errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
    }
  }

  return {
    runId,
    status,
    plan,
    planTokens,
    steps,
    stepResults,
    summary,
    errorMsg,
    selectedHistoryId,
    requirePlanApproval,
    busy,
    stepList,
    resetRunState,
    openStream,
    closeStream,
    approve,
    cancel,
    executionStatus,
    showPersistedRun,
    resumePersisted,
    forkRun,
    rewindToStep,
  }
}
