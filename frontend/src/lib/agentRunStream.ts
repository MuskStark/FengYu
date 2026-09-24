import { useCallback, useMemo, useRef, useState } from 'react'
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

export interface AgentRunStreamState {
  runId: string | null
  status: AgentRunStatus
  plan: AgentPlan | null
  planTokens: string
  /** Ordered step list (steps map → array for render). */
  stepList: AgentStep[]
  /** Per-step execution output (step_complete events). */
  stepResults: Map<number, string>
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
  summary: null,
  errorMsg: null,
  busy: false,
  awaitingApproval: null,
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
    handleRef.current?.close()
    handleRef.current = null
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
    handleRef.current = services.agent.openRunStream(runId, {
      onEvent,
      onOpenFailed: () => settleError(i18n.global.t('agent.streamTicketFailed')),
      onTransportLost: () => settleError(i18n.global.t('agent.failed')),
    })
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
        // gate. Re-attach — the backend's buffered replay converges the UI.
        handleRef.current = services.agent.openRunStream(runId, {
          onEvent,
          onOpenFailed: () => settleError(i18n.global.t('agent.streamTicketFailed')),
          onTransportLost: () => settleError(i18n.global.t('agent.failed')),
        })
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
      patchStatus('cancelled')
      closeStream()
      onSettledRef.current?.()
    }
  }, [closeStream, patchStatus])

  return useMemo(() => ({
    ...state,
    attachRun,
    approve,
    cancel,
    closeStream,
  }), [state, attachRun, approve, cancel, closeStream])
}
