<script setup lang="ts" generic="TContext = unknown">
import { computed, nextTick, onBeforeUnmount, ref, useSlots, watch } from 'vue'
import {
  mdiAlertOutline,
  mdiArrowLeft,
  mdiArrowRight,
  mdiCheck,
  mdiCircleSlice8,
  mdiLockOutline,
  mdiProgressClock,
  mdiSkipNextOutline,
} from '@mdi/js'
import FyIcon from './FyIcon.vue'
import {
  FY_WIZARD_DEFAULT_LABELS,
  buildWizardSnapshot,
  createWizardStates,
  guardWizardStepDefinitions,
  invalidateWizardStates,
  normalizeWizardSnapshot,
} from '../wizard'
import type {
  FyWizardSnapshot,
  FyWizardActionsSlotProps,
  FyWizardErrorSlotProps,
  FyWizardLabelsInput,
  FyWizardStep,
  FyWizardStepLabelSlotProps,
  FyWizardStepState,
  FyWizardSlotActions,
  FyWizardValidationResult,
} from '../wizard'

export interface Props<T = unknown> {
  steps: FyWizardStep[]
  modelValue?: string
  states?: Record<string, FyWizardStepState>
  context?: T
  completed?: boolean
  snapshot?: FyWizardSnapshot
  validateStep?: (
    step: string,
    context: T,
    signal: AbortSignal,
  ) => boolean | FyWizardValidationResult | Promise<boolean | FyWizardValidationResult>
  resolveNext?: (step: string, context: T) => string | null
  invalidateAfter?: (changedStep: string, context: T) => string[]
  /** @deprecated Use validateStep instead. */
  canContinue?: (from: string, to: string) => boolean | Promise<boolean>
  backText?: string
  nextText?: string
  finishText?: string
  retryText?: string
  optionalText?: string
  labels?: FyWizardLabelsInput
}

const props = withDefaults(defineProps<Props<TContext>>(), {
  modelValue: undefined,
  states: undefined,
  context: undefined,
  completed: undefined,
  snapshot: undefined,
  validateStep: undefined,
  resolveNext: undefined,
  invalidateAfter: undefined,
  canContinue: undefined,
  backText: 'Back',
  nextText: 'Next',
  finishText: 'Finish',
  retryText: 'Retry',
  optionalText: 'optional',
  labels: undefined,
})
const slots = useSlots()
const hasCustomError = computed(() => Boolean(slots.error))

defineSlots<{
  [name: string]: ((props: any) => unknown) | undefined
  'step-label'(props: FyWizardStepLabelSlotProps<TContext>): unknown
  error(props: FyWizardErrorSlotProps<TContext>): unknown
  actions(props: FyWizardActionsSlotProps<TContext>): unknown
  complete(props: { actions: FyWizardSlotActions }): unknown
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'update:states': [states: Record<string, FyWizardStepState>]
  'update:completed': [completed: boolean]
  transition: [from: string, to: string]
  'validation-error': [step: string, message?: string]
  'restore-error': [message: string]
  snapshot: [snapshot: FyWizardSnapshot]
  complete: [snapshot?: FyWizardSnapshot]
  /** @deprecated Compatibility event for canContinue consumers. */
  blocked: [reason?: unknown]
}>()

const cloneStates = (
  states: Record<string, FyWizardStepState>,
): Record<string, FyWizardStepState> =>
  Object.fromEntries(
    Object.entries(states).map(([id, state]) => [
      id,
      state.status === 'error' && state.error
        ? { status: 'error', error: state.error }
        : { status: state.status },
    ]),
  )

const initialStep = props.modelValue ?? props.steps[0]?.value ?? ''
const isDevelopment = (import.meta as ImportMeta & { env: { DEV: boolean } }).env.DEV
const initialDefinitionError = guardWizardStepDefinitions(props.steps, isDevelopment)
const defaultStates = initialDefinitionError ? {} : createWizardStates(props.steps, initialStep)
const internalStep = ref(initialStep)
const internalStates = ref<Record<string, FyWizardStepState>>(
  cloneStates(props.states ?? defaultStates),
)
const internalCompleted = ref(props.completed ?? false)
const initialStepIndex = props.steps.findIndex((step) => step.value === initialStep)
const modelOnlyInitialPath = props.modelValue !== undefined
  && props.states === undefined
  && props.snapshot === undefined
const visitedPath = ref<string[]>(initialDefinitionError
  ? []
  : modelOnlyInitialPath && initialStepIndex >= 0
  ? props.steps.slice(0, initialStepIndex + 1).map((step) => step.value)
  : initialStep ? [initialStep] : [])
const stepDefinitionError = computed(() => guardWizardStepDefinitions(props.steps, false))

const activeStepId = computed(() => {
  const requested = props.modelValue ?? internalStep.value
  return props.steps.some((step) => step.value === requested)
    ? requested
    : (props.steps[0]?.value ?? '')
})
const effectiveStates = computed(() => props.states ?? internalStates.value)
const effectiveCompleted = computed(() => props.completed ?? internalCompleted.value)
const slotContext = computed(() => props.context as TContext)
const currentIndex = computed(() =>
  props.steps.findIndex((step) => step.value === activeStepId.value),
)
const activeStep = computed(() => props.steps[currentIndex.value])
const activeSlot = computed(() => activeStep.value?.value)
const contentElement = ref<HTMLElement | null>(null)
const errorElement = ref<HTMLElement | null>(null)
const currentVisitedIndex = computed(() => visitedPath.value.indexOf(activeStepId.value))
const isFirst = computed(() => currentVisitedIndex.value <= 0)
const isLastDeclaredStep = computed(() => currentIndex.value === props.steps.length - 1)
const activeError = computed(() => {
  const state = internalStates.value[activeStepId.value]
  return state?.status === 'error' ? state.error : undefined
})
const activeIsError = computed(
  () => internalStates.value[activeStepId.value]?.status === 'error',
)
const errorSteps = computed(() => props.steps.filter(
  (step) => internalStates.value[step.value]?.status === 'error',
))
const labels = computed(() => ({
  ...FY_WIZARD_DEFAULT_LABELS,
  ...props.labels,
  status: {
    ...FY_WIZARD_DEFAULT_LABELS.status,
    ...props.labels?.status,
  },
}))

let validationController: AbortController | undefined
let transitionGeneration = 0
const transitioning = ref(false)
let legacyCompleteEmitted = false

function sameStates(
  left: Record<string, FyWizardStepState>,
  right: Record<string, FyWizardStepState>,
): boolean {
  const leftEntries = Object.entries(left)
  const rightEntries = Object.entries(right)
  return leftEntries.length === rightEntries.length
    && leftEntries.every(([id, state]) => {
      const candidate = right[id]
      return candidate?.status === state.status && candidate.error === state.error
    })
}

function cancelTransition(): void {
  transitionGeneration += 1
  validationController?.abort()
  validationController = undefined
  transitioning.value = false
}

function publishDefinitionError(): void {
  const error = stepDefinitionError.value
  if (!error) return
  cancelTransition()
  emit('validation-error', activeStepId.value, error)
}

watch(stepDefinitionError, (error) => {
  if (error) publishDefinitionError()
}, { immediate: true })

watch(
  () => props.modelValue,
  (value) => {
    if (value === undefined || !props.steps.some((step) => step.value === value)) return
    const changedExternally = value !== internalStep.value
    if (transitioning.value && changedExternally) cancelTransition()
    internalStep.value = value
    const modelOnlyCompatibility = props.states === undefined
      && props.completed === undefined
      && props.snapshot === undefined
      && props.validateStep === undefined
      && props.resolveNext === undefined
    if (modelOnlyCompatibility && changedExternally) {
      const index = props.steps.findIndex((step) => step.value === value)
      visitedPath.value = props.steps.slice(0, index + 1).map((step) => step.value)
    } else if (!visitedPath.value.includes(value)) {
      visitedPath.value = [...visitedPath.value, value]
    }
  },
)
watch(
  () => props.states,
  (value) => {
    if (!value) return
    if (transitioning.value && !sameStates(value, internalStates.value)) cancelTransition()
    internalStates.value = cloneStates(value)
  },
)
watch(
  () => props.completed,
  (value) => {
    if (value === undefined) return
    if (transitioning.value && value !== internalCompleted.value) cancelTransition()
    internalCompleted.value = value
  },
)

function withState(
  states: Record<string, FyWizardStepState>,
  step: string,
  state: FyWizardStepState,
): Record<string, FyWizardStepState> {
  return { ...cloneStates(states), [step]: { ...state } }
}

function publishModel(value: string): void {
  internalStep.value = value
  emit('update:modelValue', value)
}

function publishStates(states: Record<string, FyWizardStepState>): void {
  const detached = cloneStates(states)
  internalStates.value = detached
  emit('update:states', cloneStates(detached))
}

function publishCompleted(completed: boolean): void {
  internalCompleted.value = completed
  emit('update:completed', completed)
}

function makeSnapshot(
  step = activeStepId.value,
  states = effectiveStates.value,
  completed = effectiveCompleted.value,
): FyWizardSnapshot {
  return buildWizardSnapshot(step, visitedPath.value, states, completed)
}

function publishError(step: string, message?: string): void {
  const nextStates = withState(
    effectiveStates.value,
    step,
    message ? { status: 'error', error: message } : { status: 'error' },
  )
  publishStates(nextStates)
  emit('validation-error', step, message)
  emit('snapshot', makeSnapshot(step, nextStates, false))
}

function resolveNextStep(from: string): string | null {
  if (props.resolveNext) return props.resolveNext(from, props.context as TContext)
  const index = props.steps.findIndex((step) => step.value === from)
  return props.steps[index + 1]?.value ?? null
}

function completeAndMove(from: string, to: string | null): void {
  const nextStates = withState(effectiveStates.value, from, { status: 'complete' })

  if (to === null) {
    publishStates(nextStates)
    publishCompleted(true)
    const finalSnapshot = makeSnapshot(from, nextStates, true)
    emit('complete', finalSnapshot)
    emit('snapshot', finalSnapshot)
    return
  }

  const fromIndex = props.steps.findIndex((step) => step.value === from)
  const toIndex = props.steps.findIndex((step) => step.value === to)
  if (toIndex > fromIndex + 1) {
    for (const step of props.steps.slice(fromIndex + 1, toIndex)) {
      if (!visitedPath.value.includes(step.value)) {
        nextStates[step.value] = { status: 'skipped' }
      }
    }
  }
  nextStates[to] = { status: 'active' }
  if (!visitedPath.value.includes(to)) visitedPath.value = [...visitedPath.value, to]

  publishModel(to)
  publishStates(nextStates)
  publishCompleted(false)
  emit('transition', from, to)
  emit('snapshot', makeSnapshot(to, nextStates, false))
}

async function focusContent(): Promise<void> {
  await nextTick()
  contentElement.value?.focus()
}

async function focusError(): Promise<void> {
  await nextTick()
  errorElement.value?.focus()
}

function validationResult(
  result: boolean | FyWizardValidationResult,
): FyWizardValidationResult {
  return typeof result === 'boolean' ? { valid: result } : result
}

async function advance(): Promise<void> {
  if (stepDefinitionError.value) {
    publishDefinitionError()
    return
  }
  if (isBusy.value || effectiveCompleted.value) return
  const legacyCanContinue = props.canContinue !== undefined
    && props.states === undefined
    && props.completed === undefined
    && props.snapshot === undefined
    && props.validateStep === undefined
    && props.resolveNext === undefined
  if (legacyCanContinue && isLastDeclaredStep.value) {
    if (!legacyCompleteEmitted) {
      legacyCompleteEmitted = true
      emit('complete')
    }
    return
  }
  const from = activeStepId.value
  const legacyTarget = !props.validateStep && props.canContinue
    ? props.steps[currentIndex.value + 1]?.value ?? from
    : undefined
  const generation = ++transitionGeneration
  transitioning.value = true
  validationController?.abort()
  const controller = new AbortController()
  validationController = controller
  publishStates(withState(effectiveStates.value, from, { status: 'validating' }))

  try {
    const raw = props.validateStep
      ? await props.validateStep(from, props.context as TContext, controller.signal)
      : props.canContinue
        ? await props.canContinue(from, legacyTarget ?? from)
        : true
    if (controller.signal.aborted || generation !== transitionGeneration) return
    const result = validationResult(raw)
    if (!result.valid) {
      if (!props.validateStep && props.canContinue) emit('blocked')
      publishError(from, result.message)
      await focusError()
      return
    }
    const to = resolveNextStep(from)
    if (to !== null && !props.steps.some((step) => step.value === to)) {
      throw new Error(`Unknown wizard step: ${to}`)
    }
    completeAndMove(from, to)
    await focusContent()
  } catch (error) {
    if (controller.signal.aborted || generation !== transitionGeneration) return
    if (error instanceof DOMException && error.name === 'AbortError') return
    if (!props.validateStep && props.canContinue) emit('blocked', error)
    publishError(from, error instanceof Error ? error.message : undefined)
    await focusError()
  } finally {
    if (generation === transitionGeneration) {
      transitioning.value = false
      if (validationController === controller) validationController = undefined
    }
  }
}

function navigateTo(step: string): void {
  if (
    stepDefinitionError.value
    || isBusy.value
    || step === activeStepId.value
    || !visitedPath.value.includes(step)
    || !props.steps.some((item) => item.value === step)
  ) return

  const nextStates = cloneStates(effectiveStates.value)
  const currentState = nextStates[activeStepId.value]
  if (currentState?.status === 'active' || currentState?.status === 'validating') {
    nextStates[activeStepId.value] = { status: 'pending' }
  }
  nextStates[step] = { status: 'active' }
  publishModel(step)
  publishStates(nextStates)
  publishCompleted(false)
  emit('snapshot', makeSnapshot(step, nextStates, false))
}

function back(): void {
  const target = visitedPath.value[currentVisitedIndex.value - 1]
  if (target) navigateTo(target)
}

function goTo(step: string): void {
  navigateTo(step)
}

function invalidate(changedStep: string): void {
  if (stepDefinitionError.value) {
    publishDefinitionError()
    return
  }
  cancelTransition()
  const previousPath = [...visitedPath.value]
  const changedIndex = previousPath.indexOf(changedStep)
  const invalidatedIds = props.invalidateAfter
    ? props.invalidateAfter(changedStep, props.context as TContext)
    : changedIndex >= 0
      ? previousPath.slice(changedIndex + 1)
      : []
  const invalidated = new Set(invalidatedIds)
  const firstInvalidatedIndex = previousPath.findIndex((step) => invalidated.has(step))
  const nextPath = firstInvalidatedIndex >= 0
    ? previousPath.slice(0, firstInvalidatedIndex)
    : previousPath
  const prunedSteps = firstInvalidatedIndex >= 0
    ? previousPath.slice(firstInvalidatedIndex)
    : []
  prunedSteps.forEach((step) => invalidated.add(step))

  const activePathIndex = previousPath.indexOf(activeStepId.value)
  const activeWasPruned = firstInvalidatedIndex >= 0 && activePathIndex >= firstInvalidatedIndex
  const nextActiveStep = activeWasPruned
    ? (nextPath.at(-1) ?? props.steps[0]?.value ?? '')
    : activeStepId.value
  if (nextActiveStep && !nextPath.includes(nextActiveStep)) nextPath.push(nextActiveStep)
  visitedPath.value = nextPath

  const baseStates = effectiveStates.value[activeStepId.value]?.status === 'validating'
    ? withState(effectiveStates.value, activeStepId.value, { status: 'active' })
    : effectiveStates.value
  const nextStates = invalidateWizardStates(baseStates, [...invalidated])
  if (nextActiveStep) nextStates[nextActiveStep] = { status: 'active' }
  if (nextActiveStep !== activeStepId.value) publishModel(nextActiveStep)
  publishStates(nextStates)
  publishCompleted(false)
  emit('snapshot', makeSnapshot(nextActiveStep, nextStates, false))
}

function restoreFromSnapshot(snapshot?: FyWizardSnapshot): void {
  if (!snapshot) return
  const result = normalizeWizardSnapshot(props.steps, snapshot)
  if (!result.snapshot) {
    emit('restore-error', result.error ?? 'Unable to restore wizard snapshot')
    return
  }

  cancelTransition()
  const restored = result.snapshot
  visitedPath.value = [...restored.visitedPath]
  publishModel(restored.activeStep)
  publishStates(restored.states)
  publishCompleted(restored.completed)
  emit('snapshot', restored)
}

watch(() => props.snapshot, restoreFromSnapshot, { immediate: true })
onBeforeUnmount(cancelTransition)

const slotActions: FyWizardSlotActions = {
  next: advance,
  back,
  goTo,
  invalidate,
}

function stepStatus(step: string): FyWizardStepState['status'] {
  return internalStates.value[step]?.status
    ?? (step === activeStepId.value ? 'active' : 'pending')
}

function statusIcon(status: FyWizardStepState['status']): string {
  return {
    pending: mdiLockOutline,
    active: mdiCircleSlice8,
    validating: mdiProgressClock,
    complete: mdiCheck,
    error: mdiAlertOutline,
    skipped: mdiSkipNextOutline,
  }[status]
}

function statusLabel(status: FyWizardStepState['status']): string {
  return labels.value.status[status]
}

const isBusy = computed(
  () => transitioning.value || stepStatus(activeStepId.value) === 'validating',
)
const nextActionLabel = computed(() =>
  activeIsError.value
    ? props.retryText
    : isLastDeclaredStep.value
      ? props.finishText
      : props.nextText,
)
const historyOpen = ref(false)

function onHistoryToggle(event: Event): void {
  historyOpen.value = (event.currentTarget as HTMLDetailsElement).open
}

function visitedStep(value: string): FyWizardStep | undefined {
  return props.steps.find((step) => step.value === value)
}
</script>

<template>
  <section
    class="fy-step-wizard"
    data-step-wizard
    data-wizard
    :aria-busy="isBusy"
    :style="{ '--fy-wizard-step-count': steps.length }"
  >
    <div
      class="fy-wizard__live"
      data-wizard-live
      role="status"
      aria-live="polite"
      aria-atomic="true"
    >
      {{ activeStep ? labels.currentStatus(activeStep.title, statusLabel(stepStatus(activeStepId))) : '' }}
    </div>

    <nav
      v-if="!stepDefinitionError"
      class="fy-wizard__desktop-path"
      data-wizard-progress
      :aria-label="labels.progress"
    >
      <ol class="fy-wizard__desktop-list">
        <li v-for="(step, index) in steps" :key="step.value" class="fy-wizard__step">
          <button
            type="button"
            class="fy-wizard__step-button"
            :class="{ 'fy-wizard__step-button--active': step.value === activeStepId }"
            :aria-current="step.value === activeStepId ? 'step' : undefined"
            :aria-describedby="step.value === activeStepId
              && stepStatus(step.value) === 'error'
              ? `fy-wizard-active-error-${step.value}`
              : undefined"
            :disabled="isBusy || !visitedPath.includes(step.value)"
            :data-wizard-step="step.value"
            :data-status="stepStatus(step.value)"
            @click="goTo(step.value)"
          >
            <span
              class="fy-wizard__status-icon"
              :class="`fy-wizard__status-icon--${stepStatus(step.value)}`"
              aria-hidden="true"
            >
              <FyIcon :path="statusIcon(stepStatus(step.value))" :size="14" />
            </span>
            <span class="fy-wizard__step-copy">
              <slot
                name="step-label"
                :step="step"
                :index="index"
                :state="effectiveStates[step.value] ?? { status: stepStatus(step.value) }"
                :status-label="statusLabel(stepStatus(step.value))"
                :active="step.value === activeStepId"
                :context="slotContext"
                :actions="slotActions"
              >
                <span class="fy-wizard__step-number">{{ labels.step(index + 1, steps.length) }}</span>
                <span class="fy-wizard__step-title">
                  {{ step.title }}
                  <span v-if="step.optional" class="fy-wizard__optional">({{ optionalText }})</span>
                </span>
                <span class="fy-wizard__status-label">{{ statusLabel(stepStatus(step.value)) }}</span>
              </slot>
            </span>
          </button>
        </li>
      </ol>
    </nav>

    <div v-if="!stepDefinitionError" class="fy-wizard__compact-path" data-wizard-current>
      <div class="fy-wizard__compact-current">
        <span class="fy-wizard__compact-count">
          {{ labels.compactProgress(currentIndex + 1, steps.length) }}
        </span>
        <span class="fy-wizard__compact-title">{{ activeStep?.title }}</span>
        <span class="fy-wizard__compact-status">
          <FyIcon
            class="fy-wizard__status-icon"
            :size="14"
            :path="statusIcon(stepStatus(activeStepId))"
          />
          {{ statusLabel(stepStatus(activeStepId)) }}
        </span>
      </div>
      <ul
        v-if="errorSteps.length"
        class="fy-wizard__compact-errors"
        data-wizard-errors
        :aria-label="labels.errorHistory"
      >
        <li
          v-for="step in errorSteps"
          :key="step.value"
          class="fy-wizard__compact-error"
        >
          <FyIcon :path="mdiAlertOutline" :size="16" />
          <span>{{ labels.errorStep(step.title, statusLabel('error')) }}</span>
        </li>
      </ul>
      <details
        class="fy-wizard__history"
        data-wizard-history
        @toggle="onHistoryToggle"
      >
        <summary>{{ historyOpen ? labels.hideVisitedPath : labels.showVisitedPath }}</summary>
        <ol>
          <li v-for="value in visitedPath" :key="value">
            <button
              type="button"
              :disabled="isBusy || value === activeStepId"
              @click="goTo(value)"
            >
              {{ visitedStep(value)?.title ?? value }}
            </button>
          </li>
        </ol>
      </details>
    </div>

    <div
      ref="contentElement"
      class="fy-wizard__content"
      data-wizard-content
      tabindex="-1"
    >
      <div
        v-if="!stepDefinitionError && (effectiveCompleted || activeSlot)"
        :key="effectiveCompleted ? '__complete' : activeStepId"
        class="fy-wizard__step-content"
        data-wizard-step-content
      >
        <slot v-if="effectiveCompleted" name="complete" :actions="slotActions" />
        <slot
          v-else
          :name="activeSlot!"
          :step="activeStep"
          :state="effectiveStates[activeStepId]"
          :context="context"
          :actions="slotActions"
        />
      </div>
      <div
        v-if="activeIsError"
        :id="`fy-wizard-active-error-${activeStepId}`"
        ref="errorElement"
        class="fy-wizard__error"
        data-wizard-error-container
        :role="hasCustomError ? 'alert' : undefined"
        :aria-live="hasCustomError ? 'polite' : undefined"
        tabindex="-1"
      >
        <slot
          name="error"
          :step="activeStep!"
          :state="effectiveStates[activeStepId] ?? { status: 'error', error: activeError }"
          :message="activeError"
          :context="slotContext"
          :actions="slotActions"
        >
          <p data-wizard-error role="alert" aria-live="polite">
            <FyIcon :path="mdiAlertOutline" :size="16" />
            {{ activeError || statusLabel('error') }}
          </p>
        </slot>
      </div>
    </div>

    <div class="fy-wizard__actions">
      <slot
        name="actions"
        :step="activeStep"
        :state="effectiveStates[activeStepId]"
        :context="slotContext"
        :completed="effectiveCompleted"
        :busy="isBusy"
        :can-back="!stepDefinitionError && !isFirst && !isBusy"
        :next-label="nextActionLabel"
        :actions="slotActions"
      >
        <v-btn
          variant="text"
          :disabled="Boolean(stepDefinitionError) || isFirst || isBusy"
          data-action="back"
          data-wizard-back
          @click="back"
        >
          <template #prepend><FyIcon :path="mdiArrowLeft" :size="16" /></template>
          {{ backText }}
        </v-btn>
        <v-btn
          color="primary"
          variant="flat"
          :loading="isBusy"
          :disabled="Boolean(stepDefinitionError) || isBusy || effectiveCompleted"
          data-action="next"
          data-wizard-next
          @click="advance"
        >
          <template #append><FyIcon :path="mdiArrowRight" :size="16" /></template>
          {{ nextActionLabel }}
        </v-btn>
      </slot>
    </div>
  </section>
</template>

<style scoped>
.fy-step-wizard {
  --fy-wizard-gap: 14px;
  display: grid;
  gap: var(--fy-wizard-gap);
  color: rgb(var(--v-theme-on-surface));
}

.fy-wizard__live {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.fy-wizard__desktop-path {
  display: grid;
  grid-template-columns: repeat(var(--fy-wizard-step-count), minmax(0, 1fr));
  gap: 6px;
}

.fy-wizard__desktop-list {
  display: contents;
  margin: 0;
  padding: 0;
  list-style: none;
}

.fy-wizard__step {
  min-width: 0;
}

.fy-wizard__step-button {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  gap: 9px;
  width: 100%;
  min-height: 64px;
  padding: 10px;
  color: rgb(var(--v-theme-on-surface));
  text-align: start;
  background: rgb(var(--v-theme-surface-container-low));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: var(--fy-radius-md, 10px);
  transition: background-color 120ms ease, border-color 120ms ease, opacity 120ms ease;
}

.fy-wizard__step-button:not(:disabled) {
  cursor: pointer;
}

.fy-wizard__step-button:not(:disabled):hover {
  background: rgb(var(--v-theme-surface-container-high));
}

.fy-wizard__step-button--active {
  background: rgb(var(--v-theme-surface-container-high));
  border-color: rgba(var(--v-theme-on-surface), 0.62);
  box-shadow: inset 0 -2px 0 rgb(var(--v-theme-primary));
}

.fy-wizard__step-button:disabled {
  cursor: not-allowed;
  opacity: 0.62;
}

.fy-wizard__step-button[data-status='error'] {
  border-color: rgb(var(--v-theme-error));
}

.fy-wizard__status-icon {
  display: inline-grid;
  place-items: center;
  width: 24px;
  height: 24px;
  color: rgb(var(--v-theme-on-surface));
  background: rgb(var(--v-theme-surface-container-high));
  border-radius: 50%;
  transition: background-color 120ms ease, color 120ms ease;
}

.fy-wizard__status-icon--complete {
  color: rgb(var(--v-theme-tertiary));
}

.fy-wizard__status-icon--error {
  color: rgb(var(--v-theme-error));
}

.fy-wizard__status-icon--active,
.fy-wizard__status-icon--validating {
  color: rgb(var(--v-theme-on-primary));
  background: rgb(var(--v-theme-primary));
}

.fy-wizard__step-copy,
.fy-wizard__compact-current {
  display: grid;
  min-width: 0;
}

.fy-wizard__step-number,
.fy-wizard__status-label,
.fy-wizard__optional,
.fy-wizard__compact-count,
.fy-wizard__compact-status {
  font-size: 0.6875rem;
  line-height: 1.35;
  opacity: 0.72;
}

.fy-wizard__step-title,
.fy-wizard__compact-title {
  overflow: hidden;
  font-size: 0.8125rem;
  font-weight: 610;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fy-wizard__compact-path {
  display: none;
}

.fy-wizard__content {
  min-height: 230px;
  padding: 22px;
  background: rgb(var(--v-theme-surface-container-low));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: var(--fy-radius-lg, 14px);
}

.fy-wizard__step-content {
  width: 100%;
  min-width: 0;
}

.fy-wizard__error,
.fy-wizard__compact-error {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgb(var(--v-theme-error));
}

.fy-wizard__error {
  padding: 12px;
  margin: 16px 0 0;
  font-size: 0.8125rem;
  color: rgb(var(--v-theme-on-error-container));
  background: rgb(var(--v-theme-error-container));
  border: 1px solid rgba(var(--v-theme-error), 0.3);
  border-radius: var(--fy-radius-md, 10px);
}

.fy-wizard__error p {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}

.fy-wizard__actions {
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 0;
  background: transparent;
}

@media (max-width: 720px) {
  .fy-wizard__desktop-path {
    display: none;
  }

  .fy-wizard__compact-path {
    display: grid;
    gap: 8px;
    padding: 12px 14px;
    background: rgb(var(--v-theme-surface-container-low));
    border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
    border-radius: var(--fy-radius-md, 10px);
  }

  .fy-wizard__compact-current {
    grid-template-columns: 1fr auto;
    gap: 2px 12px;
  }

  .fy-wizard__compact-title {
    grid-column: 1;
  }

  .fy-wizard__compact-status {
    display: inline-flex;
    grid-row: 1 / span 2;
    grid-column: 2;
    align-items: center;
    gap: 6px;
  }

  .fy-wizard__compact-status .fy-wizard__status-icon {
    width: 24px;
    height: 24px;
  }

  .fy-wizard__compact-errors {
    display: grid;
    gap: 6px;
    padding: 8px 0 0;
    margin: 0;
    list-style: none;
    border-top: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  }

  .fy-wizard__compact-error {
    font-size: 0.8125rem;
  }

  .fy-wizard__history {
    padding-top: 8px;
    font-size: 0.8125rem;
    border-top: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  }

  .fy-wizard__history summary {
    cursor: pointer;
    color: rgb(var(--v-theme-secondary));
    font-size: 0.75rem;
  }

  .fy-wizard__history ol {
    display: grid;
    gap: 4px;
    padding-inline-start: 24px;
    margin: 8px 0 0;
  }

  .fy-wizard__history button {
    padding: 2px 0;
    color: inherit;
    background: transparent;
    border: 0;
    text-align: start;
  }

  .fy-wizard__content {
    min-height: 280px;
    padding: 16px;
  }

  .fy-wizard__actions {
    position: sticky;
    bottom: 0;
    padding: 10px 0;
    background: rgb(var(--v-theme-background));
  }
}

@media (prefers-reduced-motion: reduce) {
  .fy-wizard__status-icon {
    transition: none;
  }
}
</style>
