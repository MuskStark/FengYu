import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  FY_WIZARD_DEFAULT_LABELS,
  buildWizardSnapshot,
  createWizardStates,
  invalidateWizardStates,
  normalizeWizardSnapshot,
  type FyWizardLabels,
  type FyWizardLabelsInput,
  type FyWizardSlotActions,
  type FyWizardSnapshot,
  type FyWizardStep,
  type FyWizardStepState,
  type FyWizardValidationResult,
} from '../wizard'
import { cn } from '../lib/utils'
import { GhostButton, GoldButton } from './chrome'
import { ErrorState } from './states'

export interface StepWizardRenderProps<TContext> {
  step: FyWizardStep
  state: FyWizardStepState
  context: TContext
  actions: FyWizardSlotActions
}

export interface StepWizardStep<TContext> extends FyWizardStep {
  /** Returns a validation verdict for the CURRENT context before advancing. */
  validate?: (
    context: TContext,
    step: FyWizardStep,
  ) => Promise<FyWizardValidationResult> | FyWizardValidationResult
  /** Step body. Receives live actions so custom flows can drive the wizard. */
  render: (props: StepWizardRenderProps<TContext>) => ReactNode
}

export interface StepWizardLabels extends FyWizardLabelsInput {
  next?: string
  finish?: string
  back?: string
}

function mergeLabels(input?: StepWizardLabels): FyWizardLabels & { next: string; finish: string; back: string } {
  const base = FY_WIZARD_DEFAULT_LABELS
  return {
    ...base,
    ...input,
    status: { ...base.status, ...(input?.status ?? {}) },
    next: input?.next ?? '下一步',
    finish: input?.finish ?? '完成',
    back: input?.back ?? '上一步',
  }
}

/**
 * Infinia step wizard over the framework-neutral `wizard.ts` state machine
 * (same snapshot format as the Vue 2.x kit, so persisted snapshots survive
 * the React migration). The caller owns `context`; the wizard owns step
 * states, the visited path, and snapshot emission.
 */
export function StepWizard<TContext>({
  steps,
  context,
  completed = false,
  snapshot,
  onSnapshot,
  labels: labelsInput,
  footer,
  className,
}: {
  steps: StepWizardStep<TContext>[]
  /** Caller-owned wizard context (forms state); passed to validate/render. */
  context: TContext
  /** External completion flag (result screen); freezes navigation. */
  completed?: boolean
  /** Restore seed (e.g. from localStorage via normalizeWizardSnapshot upstream). */
  snapshot?: FyWizardSnapshot
  /** Emitted after every transition with the fresh snapshot (persist it). */
  onSnapshot?: (snapshot: FyWizardSnapshot) => void
  labels?: StepWizardLabels
  /** Optional custom footer; defaults to back/next + progress. */
  footer?: (props: {
    busy: boolean
    canBack: boolean
    nextLabel: string
    actions: FyWizardSlotActions
    completed: boolean
  }) => ReactNode
  className?: string
}) {
  const definitions = useMemo<FyWizardStep[]>(
    () => steps.map(({ validate: _validate, render: _render, ...rest }) => rest),
    [steps],
  )
  const labels = useMemo(() => mergeLabels(labelsInput), [labelsInput])

  const restored = useMemo(() => {
    if (!snapshot) return undefined
    return normalizeWizardSnapshot(definitions, snapshot).snapshot
  }, [definitions, snapshot])

  const [active, setActive] = useState(restored?.activeStep ?? steps[0]?.value ?? '')
  const [states, setStates] = useState<Record<string, FyWizardStepState>>(
    () => createWizardStates(definitions, restored?.activeStep ?? steps[0]?.value ?? ''),
  )
  const [visited, setVisited] = useState<string[]>(restored?.visitedPath ?? [steps[0]?.value ?? ''])
  const [busy, setBusy] = useState(false)
  const [completedState, setCompletedState] = useState(restored?.completed ?? false)
  const mounted = useRef(true)
  const isCompleted = completed || completedState

  const emitSnapshot = useCallback(
    (nextActive: string, nextVisited: string[], nextStates: Record<string, FyWizardStepState>, done: boolean) => {
      onSnapshot?.(buildWizardSnapshot(nextActive, nextVisited, nextStates, done))
    },
    [onSnapshot],
  )

  const goTo = useCallback(
    (stepValue: string) => {
      const target = steps.find((step) => step.value === stepValue)
      if (!target || isCompleted) return
      setStates((previous) => ({
        ...previous,
        [stepValue]: { status: 'active' },
      }))
      setActive(stepValue)
      setVisited((path) => (path.includes(stepValue) ? path : [...path, stepValue]))
    },
    [steps, isCompleted],
  )

  const back = useCallback(() => {
    if (isCompleted) return
    const index = steps.findIndex((step) => step.value === active)
    if (index <= 0) return
    const previous = steps[index - 1]
    setStates((current) => ({ ...current, [active]: { status: 'pending' }, [previous.value]: { status: 'active' } }))
    setActive(previous.value)
  }, [steps, active, isCompleted])

  const next = useCallback(async () => {
    if (busy || isCompleted) return
    const index = steps.findIndex((step) => step.value === active)
    const step = steps[index]
    if (!step) return
    const finish = index === steps.length - 1

    if (step.validate) {
      setBusy(true)
      setStates((current) => ({ ...current, [active]: { status: 'validating' } }))
      let verdict: FyWizardValidationResult
      try {
        verdict = await step.validate(context, step)
      } catch (error) {
        verdict = { valid: false, message: error instanceof Error ? error.message : String(error) }
      }
      if (!mounted.current) return
      setBusy(false)
      if (!verdict.valid) {
        setStates((current) => ({ ...current, [active]: { status: 'error', error: verdict.message } }))
        return
      }
    }

    if (finish) {
      const doneStates = { ...states, [active]: { status: 'complete' as const } }
      setStates(doneStates)
      setCompletedState(true)
      emitSnapshot(active, visited, doneStates, true)
      return
    }

    const target = steps[index + 1]
    const advanced = { ...states, [active]: { status: 'complete' as const }, [target.value]: { status: 'active' as const } }
    setStates(advanced)
    setActive(target.value)
    const nextVisited = visited.includes(target.value) ? visited : [...visited, target.value]
    setVisited(nextVisited)
    emitSnapshot(target.value, nextVisited, advanced, false)
  }, [busy, isCompleted, steps, active, states, context, visited, emitSnapshot])

  const invalidate = useCallback(
    (changedStep: string) => {
      const index = steps.findIndex((step) => step.value === changedStep)
      if (index < 0) return
      const downstream = steps.slice(index + 1).map((step) => step.value)
      setStates((current) => invalidateWizardStates(current, downstream))
    },
    [steps],
  )

  const actions = useMemo<FyWizardSlotActions>(() => ({ next, back, goTo, invalidate }), [next, back, goTo, invalidate])
  const activeIndex = steps.findIndex((step) => step.value === active)
  const activeStep = steps[activeIndex]
  const activeState = states[active] ?? { status: 'pending' as const }

  return (
    <div data-wizard="" aria-busy={busy} className={cn('flex min-h-0 flex-1 flex-col', className)}>
      {/* 步骤轨：与下方内容同宽——连接线弹性填充，末步右缘对齐内容右缘。 */}
      <ol data-wizard-steps="" className="mb-5 flex w-full items-center">
        {steps.map((step, index) => {
          const state = states[step.value] ?? { status: 'pending' as const }
          const done = state.status === 'complete' || (completed && index <= activeIndex)
          const current = step.value === active && !isCompleted
          const last = index === steps.length - 1
          return (
            <li key={step.value} className={cn('flex min-w-0 items-center gap-1.5', last ? 'shrink-0' : 'flex-1')}>
              <span
                data-wizard-step={step.value}
                data-status={state.status}
                className={cn(
                  'flex shrink-0 items-center gap-1.5 rounded-full px-2.5 py-1 text-xs transition-colors',
                  current ? 'bg-gold font-medium text-gold-ink' : done ? 'text-ink' : 'text-ink-3',
                )}
              >
                <span
                  className={cn(
                    'grid size-4 shrink-0 place-items-center rounded-full font-mono text-[10px]',
                    current ? 'text-gold-ink' : done ? 'text-ink' : 'border border-line-strong text-ink-3',
                  )}
                  style={current ? { background: 'rgba(24,24,27,0.12)' } : undefined}
                >
                  {done ? '✓' : index + 1}
                </span>
                <span className="truncate">{step.title}</span>
              </span>
              {last ? null : <span className="h-px min-w-4 flex-1 shrink-0 bg-line" />}
            </li>
          )
        })}
      </ol>

      {/* 当前步骤内容 */}
      <div data-wizard-body="" className="min-h-0 flex-1">
        {activeStep
          ? activeStep.render({
              step: activeStep,
              state: activeState,
              context,
              actions,
            })
          : null}
        {activeState.status === 'error' && activeState.error ? (
          <ErrorState className="mt-3" title={labels.errorStep(activeStep?.title ?? '', labels.status.error)} message={activeState.error} />
        ) : null}
      </div>

      {/* 页脚：进度 + 导航 */}
      {footer ? (
        footer({ busy, canBack: activeIndex > 0, nextLabel: activeIndex === steps.length - 1 ? labels.finish : labels.next, actions, completed: isCompleted })
      ) : (
        <div data-wizard-footer="" className="mt-5 flex items-center gap-3 border-t border-line pt-4">
          <span className="font-mono text-[11px] text-ink-3">{labels.compactProgress(activeIndex + 1, steps.length)}</span>
          <div className="ml-auto flex items-center gap-2">
            <GhostButton data-wizard-back="" disabled={busy || activeIndex <= 0 || isCompleted} onClick={back}>
              {labels.back}
            </GhostButton>
            <GoldButton data-wizard-next="" disabled={busy || isCompleted} onClick={() => void next()}>
              {busy ? labels.status.validating : activeIndex === steps.length - 1 ? labels.finish : labels.next}
            </GoldButton>
          </div>
        </div>
      )}
    </div>
  )
}
