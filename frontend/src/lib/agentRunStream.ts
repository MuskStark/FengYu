import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { i18n } from '@/i18n'
import { services } from '@/services'
import {
  agentGateIdFromData,
  failActiveAgentSteps,
  type AgentRunStreamHandlers,
  type StreamHandle,
} from '@/services/agent'
import type { AgentPlan, AgentStep } from '@/services/types'

/**
 * React run-state machine for one agent run (the flow run dialog's engine).
 * Transport (ticket mint, bounded reconnect, replay dedup) is owned by
 * `services.agent.openRunStream` — this hook only folds the raw event
 * vocabulary into status/step/result state, using the pure payload helpers
 * re-exported from the services layer.
 */

export type AgentRunStatus =
  | 'idle'
  | 'planning'
  | 'awaiting-plan'
  | 'running'
  | 'awaiting-step'
  | 'complete'
  | 'error'
  | 'cancelled'

/** Client-clock per-step timing (start/end wall times, ms since epoch). */
export interface StepTiming {
  startedAt: number
  endedAt?: number
}

export interface AgentRunStreamState {
  runId: string | null
  status: AgentRunStatus
  plan: AgentPlan | null
  planTokens: string
  /** Ordered step list (steps map → array for render). */
  stepList: AgentStep[]
  /** Per-step execution output (step_complete events). */
  stepResults: Map<number, string>
  /** Per-step start/end wall times — drives on-node elapsed/duration chips. */
  stepTimings: Map<number, StepTiming>
  summary: string | null
  errorMsg: string | null
  busy: boolean
  /** Approval gate pending: 'plan' | 'step' | null. */
  awaitingApproval: 'plan' | 'step' | null
}

const INITIAL_STATE: AgentRunStreamState = {
  runId: null,
  status: 'idle',
  plan: null,
  planTokens: '',
  stepList: [],
  stepResults: new Map<number, string>(),
  stepTimings: new Map<number, StepTiming>(),
  summary: null,
  errorMsg: null,
  busy: false,
  awaitingApproval: null,
}

/**
 * Swap the tracked stream handle to {@code next}, closing any live predecessor first.
 * Every handle transition (explicit close, attachRun's fresh run, the approve-409
 * re-attach, unmount teardown) goes through here so a superseded EventSource can
 * never be orphaned while a newer one is installed.
 */
export function swapStreamHandle(
  ref: { current: StreamHandle | null },
  next: StreamHandle | null,
): void {
  ref.current?.close()
  ref.current = next
}

function isBusyStatus(status: AgentRunStatus): boolean {
  return status === 'planning' || status === 'awaiting-plan' || status === 'running'
    || status === 'awaiting-step'
}

export function useAgentRunStream(hooks?: {
  /** Called when a run reaches a terminal state (complete / error / cancel). */
  onSettled?: () => void
}) {
  const [state, setState] = useState<AgentRunStreamState>(INITIAL_STATE)

  // Steps live in a ref so a burst of SSE events can update individual entries
  // without stale closures; published to state for render.
  const stepsRef = useRef<Map<number, AgentStep>>(new Map())
  const gateIdRef = useRef<string | null>(null)
  const requirePlanApprovalRef = useRef(false)
  const statusRef = useRef<AgentRunStatus>('idle')
  const runIdRef = useRef<string | null>(null)
  const handleRef = useRef<StreamHandle | null>(null)
  const onSettledRef = useRef(hooks?.onSettled)
  onSettledRef.current = hooks?.onSettled

  const publishSteps = useCallback(() => {
    const stepList = Array.from(stepsRef.current.values()).sort((a, b) => a.index - b.index)
    setState((current) => ({ ...current, stepList }))
  }, [])

  const patchStatus = useCallback((status: AgentRunStatus) => {
    statusRef.current = status
    setState((current) => ({
      ...current,
      status,
      busy: isBusyStatus(status),
      awaitingApproval: status === 'awaiting-plan' ? 'plan' : status === 'awaiting-step' ? 'step' : null,
    }))
  }, [])

  const failActiveSteps = useCallback(() => {
    stepsRef.current = failActiveAgentSteps(stepsRef.current)
    setState((current) => {
      let changed = false
      const stepTimings = new Map(current.stepTimings)
      for (const [index, timing] of stepTimings) {
        if (timing.endedAt === undefined) {
          stepTimings.set(index, { ...timing, endedAt: Date.now() })
          changed = true
        }
      }
      return changed ? { ...current, stepTimings } : current
    })
    publishSteps()
  }, [publishSteps])

  const settleError = useCallback((message: string) => {
    setState((current) => ({ ...current, errorMsg: message }))
    failActiveSteps()
    patchStatus('error')
    handleRef.current?.close()
    handleRef.current = null
    onSettledRef.current?.()
  }, [failActiveSteps, patchStatus])

  const setStep = useCallback((index: number, mutate: (step: AgentStep) => AgentStep) => {
    const existing = stepsRef.current.get(index)
    stepsRef.current.set(index, existing
      ? mutate(existing)
      : mutate({ index, toolName: '', description: '', status: 'pending' }))
  }, [])

  /** Records/closes one step's wall-clock window (client clock — display only). */
  const setStepTiming = useCallback((index: number, mutate: (timing?: StepTiming) => StepTiming) => {
    setState((current) => {
      const timing = mutate(current.stepTimings.get(index))
      if (current.stepTimings.get(index) === timing) return current
      const stepTimings = new Map(current.stepTimings)
      stepTimings.set(index, timing)
      return { ...current, stepTimings }
    })
  }, [])

  /** Folds one replay-deduped transport event into run state. */
  const onEvent = useCallback<AgentRunStreamHandlers['onEvent']>((name, payload) => {
    switch (name) {
      case 'plan_token':
        if (payload && typeof payload.delta === 'string') {
          setState((current) => ({ ...current, planTokens: current.planTokens + payload.delta }))
        }
        return
      case 'plan_ready': {
        if (!payload) return
        const steps = Array.isArray(payload.steps) ? payload.steps as AgentStep[] : []
        for (const step of steps) stepsRef.current.set(step.index, { ...step, status: step.status || 'pending' })
        setState((current) => ({
          ...current,
          plan: {
            goal: String(payload.goal ?? ''),
            steps,
            reasoning: String(payload.reasoning ?? ''),
          },
          planTokens: '',
        }))
        patchStatus(requirePlanApprovalRef.current ? 'awaiting-plan' : 'running')
        publishSteps()
        return
      }
      case 'plan_approval_requested': {
        const gateId = agentGateIdFromData(payload)
        if (gateId) gateIdRef.current = gateId
        patchStatus('awaiting-plan')
        return
      }
      case 'step_start':
        if (typeof payload?.index === 'number') {
          setStep(payload.index, (step) => ({ ...step, status: 'running' }))
          setStepTiming(payload.index, (timing) => ({ startedAt: timing?.startedAt ?? Date.now() }))
          publishSteps()
        }
        patchStatus('running')
        return
      case 'step_complete':
        if (typeof payload?.index === 'number') {
          const result = typeof payload.result === 'string' ? payload.result : ''
          setStep(payload.index, (step) => ({
            ...step,
            status: 'complete',
            description: step.description || result,
          }))
          setStepTiming(payload.index, (timing) => timing
            ? { ...timing, endedAt: timing.endedAt ?? Date.now() }
            : { startedAt: Date.now(), endedAt: Date.now() })
          setState((current) => {
            const stepResults = new Map(current.stepResults)
            stepResults.set(payload.index as number, result)
            return { ...current, stepResults }
          })
          publishSteps()
        }
        patchStatus('running')
        return
      case 'step_retry':
        if (typeof payload?.index === 'number') {
          setStep(payload.index, (step) => ({ ...step, status: 'retrying' }))
          publishSteps()
        }
        patchStatus('running')
        return
      case 'step_skipped':
        if (typeof payload?.index === 'number') {
          setStep(payload.index, (step) => ({ ...step, status: 'skipped' }))
          setStepTiming(payload.index, (timing) => timing
            ? { ...timing, endedAt: timing.endedAt ?? Date.now() }
            : { startedAt: Date.now(), endedAt: Date.now() })
          publishSteps()
        }
        return
      case 'step_approval_requested': {
        const gateId = agentGateIdFromData(payload)
        if (gateId) gateIdRef.current = gateId
        patchStatus('awaiting-step')
        return
      }
      case 'complete':
        setState((current) => ({
          ...current,
          summary: typeof payload?.summary === 'string' ? payload.summary : '',
        }))
        patchStatus('complete')
        handleRef.current?.close()
        handleRef.current = null
        onSettledRef.current?.()
        return
      case 'error':
        settleError(typeof payload?.message === 'string' && payload.message
          ? payload.message
          : i18n.global.t('agent.failed'))
        return
    }
  }, [patchStatus, publishSteps, settleError, setStep])

  const closeStream = useCallback(() => {
    swapStreamHandle(handleRef, null)
  }, [])

  // Unmount safety net: a caller that forgets closeStream (or is torn down mid-run
  // by a route change) must not leave a reconnecting EventSource behind. Idempotent
  // with the explicit close paths — closing an already-null handle is a no-op.
  useEffect(() => () => {
    swapStreamHandle(handleRef, null)
  }, [])

  /**
   * Starts observing a freshly created run. `plan` seeds the step preview the
   * backend will confirm via plan_ready; `requirePlanApproval` matches the run
   * config's gate flags (canvas runs skip plan review).
   */
  const attachRun = useCallback((runId: string, plan: AgentPlan | null, requirePlanApproval = false) => {
    closeStream()
    stepsRef.current = new Map()
    for (const step of plan?.steps ?? []) stepsRef.current.set(step.index, { ...step })
    gateIdRef.current = null
    requirePlanApprovalRef.current = requirePlanApproval
    runIdRef.current = runId
    statusRef.current = 'planning'
    setState({
      ...INITIAL_STATE,
      runId,
      status: 'planning',
      busy: true,
      plan,
      stepList: Array.from(stepsRef.current.values()).sort((a, b) => a.index - b.index),
    })
    swapStreamHandle(handleRef, services.agent.openRunStream(runId, {
      onEvent,
      onOpenFailed: () => settleError(i18n.global.t('agent.streamTicketFailed')),
      onTransportLost: () => settleError(i18n.global.t('agent.failed')),
    }))
  }, [closeStream, onEvent, settleError])

  /** Releases the currently armed approval gate (plan or step). */
  const approve = useCallback(async () => {
    const runId = runIdRef.current
    if (!runId) return
    try {
      // The gateId credential makes a duplicate/late approve an explicit 409
      // instead of silently releasing whatever newer gate has armed since.
      await services.agent.approve(runId, undefined, gateIdRef.current ?? undefined)
      if (statusRef.current === 'awaiting-plan' || statusRef.current === 'awaiting-step') {
        patchStatus('running')
      }
    } catch (e) {
      if ((e as { response?: { status?: number } } | null)?.response?.status === 409 && runId) {
        // Duplicate / late / stale approve: another client already resolved the
        // gate. Re-attach — the backend's buffered replay converges the UI. The
        // still-live predecessor stream MUST be closed first, or two EventSources
        // would dispatch into the same run state (duplicated plan tokens, a double
        // onSettled) and the orphan would outlive the run.
        swapStreamHandle(handleRef, services.agent.openRunStream(runId, {
          onEvent,
          onOpenFailed: () => settleError(i18n.global.t('agent.streamTicketFailed')),
          onTransportLost: () => settleError(i18n.global.t('agent.failed')),
        }))
        return
      }
      setState((current) => ({
        ...current,
        errorMsg: e instanceof Error ? e.message : i18n.global.t('agent.failed'),
      }))
    }
  }, [onEvent, patchStatus, settleError])

  const cancel = useCallback(async () => {
    const runId = runIdRef.current
    if (!runId) {
      patchStatus('cancelled')
      return
    }
    try {
      await services.agent.cancel(runId)
    } finally {
      // Cancel settles in-flight steps like the error path does — running
      // badges stop and open timing windows close instead of freezing.
      failActiveSteps()
      patchStatus('cancelled')
      closeStream()
      onSettledRef.current?.()
    }
  }, [closeStream, failActiveSteps, patchStatus])

  return useMemo(() => ({
    ...state,
    attachRun,
    approve,
    cancel,
    closeStream,
  }), [state, attachRun, approve, cancel, closeStream])
}
