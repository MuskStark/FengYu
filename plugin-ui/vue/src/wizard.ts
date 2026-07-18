export const FY_WIZARD_SNAPSHOT_VERSION = 1 as const

export interface FyWizardStep {
  value: string
  title: string
  description?: string
  optional?: boolean
}

export type FyWizardStepStatus =
  | 'pending'
  | 'active'
  | 'validating'
  | 'complete'
  | 'error'
  | 'skipped'

export interface FyWizardStepState {
  status: FyWizardStepStatus
  error?: string
}

export interface FyWizardSnapshot {
  version: typeof FY_WIZARD_SNAPSHOT_VERSION
  activeStep: string
  visitedPath: string[]
  states: Record<string, FyWizardStepState>
  completed: boolean
}

export interface FyWizardValidationResult {
  valid: boolean
  message?: string
}

export interface FyWizardSnapshotResult {
  snapshot?: FyWizardSnapshot
  error?: string
}

export type FyWizardStatusLabels = Record<FyWizardStepStatus, string>

export interface FyWizardLabels {
  status: FyWizardStatusLabels
  progress: string
  step: (index: number, total: number) => string
  compactProgress: (index: number, total: number) => string
  errorHistory: string
  errorStep: (title: string, status: string) => string
  currentStatus: (title: string, status: string) => string
  showVisitedPath: string
  hideVisitedPath: string
}

export type FyWizardLabelsInput = Partial<Omit<FyWizardLabels, 'status'>> & {
  status?: Partial<FyWizardStatusLabels>
}

export interface FyWizardSlotActions {
  next: () => Promise<void>
  back: () => void
  goTo: (step: string) => void
  invalidate: (changedStep: string) => void
}

export interface FyWizardStepSlotProps<TContext = unknown> {
  step: FyWizardStep
  state: FyWizardStepState
  context: TContext
  actions: FyWizardSlotActions
}

export interface FyWizardStepLabelSlotProps<TContext = unknown>
  extends FyWizardStepSlotProps<TContext> {
  index: number
  statusLabel: string
  active: boolean
}

export interface FyWizardErrorSlotProps<TContext = unknown>
  extends FyWizardStepSlotProps<TContext> {
  message?: string
}

export interface FyWizardActionsSlotProps<TContext = unknown> {
  step?: FyWizardStep
  state?: FyWizardStepState
  context: TContext
  completed: boolean
  busy: boolean
  canBack: boolean
  nextLabel: string
  actions: FyWizardSlotActions
}

export const FY_WIZARD_DEFAULT_LABELS: Readonly<FyWizardLabels> = {
  status: {
    pending: 'Pending',
    active: 'Current',
    validating: 'Validating',
    complete: 'Complete',
    error: 'Error',
    skipped: 'Skipped',
  },
  progress: 'Progress',
  step: (index) => `Step ${index}`,
  compactProgress: (index, total) => `Step ${index} / ${total}`,
  errorHistory: 'Steps with errors',
  errorStep: (title, status) => `${title}: ${status}`,
  currentStatus: (title, status) => `${title}: ${status}`,
  showVisitedPath: 'Show visited steps',
  hideVisitedPath: 'Hide visited steps',
}

const copyState = (state: FyWizardStepState): FyWizardStepState =>
  state.status === 'error' && state.error
    ? { status: 'error', error: state.error }
    : { status: state.status }

function duplicateStepValue(steps: FyWizardStep[]): string | undefined {
  const seen = new Set<string>()
  return steps.find((step) => {
    if (seen.has(step.value)) return true
    seen.add(step.value)
    return false
  })?.value
}

function wizardStepDefinitionError(steps: FyWizardStep[]): string | undefined {
  if (steps.length === 0) return 'Wizard requires at least one step'
  const invalidValueIndex = steps.findIndex(
    (step) => typeof step.value !== 'string' || !step.value.trim(),
  )
  if (invalidValueIndex >= 0) {
    return `Wizard step value must be a non-empty string at index ${invalidValueIndex}`
  }
  const invalidTitle = steps.find(
    (step) => typeof step.title !== 'string' || !step.title.trim(),
  )
  if (invalidTitle) return `Wizard step title must be a non-empty string: ${invalidTitle.value}`
  const duplicate = duplicateStepValue(steps)
  return duplicate === undefined ? undefined : `Wizard step values must be unique: ${duplicate}`
}

export function guardWizardStepDefinitions(
  steps: FyWizardStep[],
  development: boolean,
): string | undefined {
  const error = wizardStepDefinitionError(steps)
  if (error && development) throw new Error(error)
  return error
}

export function createWizardStates(
  steps: FyWizardStep[],
  activeStep: string,
): Record<string, FyWizardStepState> {
  guardWizardStepDefinitions(steps, true)
  const fallback = steps.some((step) => step.value === activeStep)
    ? activeStep
    : steps[0]?.value
  return Object.fromEntries(
    steps.map((step) => [
      step.value,
      { status: step.value === fallback ? 'active' : 'pending' },
    ]),
  )
}

export function invalidateWizardStates(
  states: Record<string, FyWizardStepState>,
  invalidatedIds: string[],
): Record<string, FyWizardStepState> {
  const invalidated = new Set(invalidatedIds)
  return Object.fromEntries(
    Object.entries(states).map(([id, state]) => [
      id,
      invalidated.has(id) ? { status: 'pending' } : copyState(state),
    ]),
  )
}

export function buildWizardSnapshot(
  activeStep: string,
  visitedPath: string[],
  states: Record<string, FyWizardStepState>,
  completed: boolean,
): FyWizardSnapshot {
  return {
    version: FY_WIZARD_SNAPSHOT_VERSION,
    activeStep,
    visitedPath: [...visitedPath],
    states: Object.fromEntries(
      Object.entries(states).map(([id, state]) => [id, copyState(state)]),
    ),
    completed,
  }
}

export function normalizeWizardSnapshot(
  steps: FyWizardStep[],
  input: FyWizardSnapshot,
): FyWizardSnapshotResult {
  if (input.version !== FY_WIZARD_SNAPSHOT_VERSION) {
    return { error: `Unsupported wizard snapshot version: ${input.version}` }
  }
  const definitionError = guardWizardStepDefinitions(steps, false)
  if (definitionError) return { error: definitionError }

  const validIds = new Set(steps.map((step) => step.value))
  const visitedPath = [...new Set(input.visitedPath.filter((id) => validIds.has(id)))]
  const fallback = visitedPath.at(-1) ?? steps[0].value
  const activeStep = validIds.has(input.activeStep) ? input.activeStep : fallback
  if (!visitedPath.includes(activeStep)) visitedPath.push(activeStep)

  const states = createWizardStates(steps, activeStep)
  for (const [id, state] of Object.entries(input.states)) {
    if (validIds.has(id)) states[id] = copyState(state)
  }
  states[activeStep] = input.completed
    ? copyState(states[activeStep])
    : { status: 'active' }

  return {
    snapshot: buildWizardSnapshot(
      activeStep,
      visitedPath,
      states,
      input.completed,
    ),
  }
}
