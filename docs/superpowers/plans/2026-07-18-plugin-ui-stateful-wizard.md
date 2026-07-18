# Plugin UI Stateful Wizard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade `@infinia/plugin-ui`'s `FyStepWizard` into the approved controlled, stateful Codex-style wizard and adopt it in the official Excel plugin with responsive hybrid navigation, recoverable progress, and complete validation/error behavior.

**Architecture:** Keep reusable navigation state and transition rules in `plugin-ui`, while the Excel plugin owns its business draft and `sessionStorage` persistence. The component accepts controlled values, emits normalized snapshots, and uses an `AbortController` for one in-flight validation. Excel restores only serializable form/session data, revalidates the source grant, and deliberately requires output-directory reselection because the current SDK has no side-effect-free directory-grant validation API.

**Tech Stack:** Vue 3.5, TypeScript, Vuetify 3, Vitest, Vue Test Utils, Playwright, `@infinia/plugin-sdk`, `@infinia/plugin-ui`.

## Global Constraints

- Treat `/Users/phoebej/Develop/Java/FengYu/docs/superpowers/specs/2026-07-18-plugin-ui-stateful-wizard-design.md` as the accepted behavior contract.
- Do not introduce JavaFX, `StepWizard`, in-process plugin APIs, worker protocol changes, or a third-party state-machine dependency.
- Keep `FyStepWizard` storage-agnostic: it must never access `localStorage`, `sessionStorage`, IndexedDB, the SDK client, or a worker.
- Preserve source compatibility for existing third-party consumers during the 1.x toolchain line: retain deprecated `canContinue`, allow omitted `modelValue`/`states`, and route those calls through the new transition engine. Canonical docs and all repository consumers must use the controlled API.
- Do not edit the current user changes in `plugin-ui/vue/src/components/FyPluginShell.vue`, `plugin-ui/vue/src/styles/codex.css`, `plugin-ui/vue/test/layout-and-states.test.ts`, or `OfficialPlugins/plugin-offlinepython/**`. Put wizard-specific responsive CSS in `FyStepWizard.vue` unless a later re-read proves a shared token-only change is necessary.
- Re-read `git diff` before touching any file already modified at execution time; merge around user work rather than replacing it.
- Every user-visible string in the generic component comes from props or slots. Excel currently uses English UI copy, so this change passes explicit Excel-owned labels without inventing a second localization framework.
- Do not create commits unless the user explicitly authorizes commits. The conditional commit checkpoints below are handoff boundaries, not authorization.

## File Map

| File | Responsibility after this change |
|---|---|
| `plugin-ui/vue/src/wizard.ts` | Public wizard types plus pure creation, normalization, invalidation, and snapshot helpers. |
| `plugin-ui/vue/src/components/FyStepWizard.vue` | Controlled transition coordinator, legacy adapter, responsive UI, focus handling, and abort lifecycle. |
| `plugin-ui/vue/src/index.ts` | Public exports for all wizard values and types. |
| `plugin-ui/vue/test/wizard-state.test.ts` | Pure state/snapshot unit tests. |
| `plugin-ui/vue/test/workflow-components.test.ts` | Component transition, branch, retry, cancellation, compatibility, and accessibility tests. |
| `plugin-ui/vue/e2e/Workbench.vue` | Full-shell stateful-wizard visual fixture. |
| `plugin-ui/vue/e2e/workbench.spec.ts` | Explicit desktop/narrow, light/dark visual cases. |
| `plugin-ui/vue/playwright.config.ts` | Stable visual-test defaults; individual cases still set their viewport explicitly. |
| `OfficialPlugins/plugin-excel/ui-src/src/excelWizardState.ts` | Excel-owned serializable draft and storage adapter. |
| `OfficialPlugins/plugin-excel/ui-src/src/excelWizardState.test.ts` | Persistence versioning and corrupt-record tests. |
| `OfficialPlugins/plugin-excel/ui-src/src/ExcelSplitter.vue` | Controlled wizard integration, source revalidation, downstream invalidation, completion UI. |
| `OfficialPlugins/plugin-excel/ui-src/src/ExcelSplitter.test.ts` | End-to-end component behavior against a fake SDK client. |
| `docs/en/plugins/ui-components.md` / `docs/zh/plugins/ui-components.md` | Public controlled API and compatibility note. |
| `docs/en/plugins/official-excel.md` / `docs/zh/plugins/official-excel.md` | Actual Source → Mode → Output → Run flow and recovery rules. |

---

### Task 1: Add the public wizard state model and pure helpers

**Files:**
- Create: `plugin-ui/vue/src/wizard.ts`
- Create: `plugin-ui/vue/test/wizard-state.test.ts`
- Modify: `plugin-ui/vue/src/index.ts`

**Interfaces produced:** `FyWizardStep`, `FyWizardStepStatus`, `FyWizardStepState`, `FyWizardSnapshot`, `FyWizardValidationResult`, and pure helper functions consumed by `FyStepWizard.vue` and Excel.

- [ ] **Step 1: Write failing tests for initial state, snapshot normalization, invalidation, and immutability**

```ts
import { describe, expect, it } from 'vitest'
import {
  buildWizardSnapshot,
  createWizardStates,
  invalidateWizardStates,
  normalizeWizardSnapshot,
  type FyWizardStep,
} from '../src/wizard'

const steps: FyWizardStep[] = [
  { value: 'source', title: 'Source' },
  { value: 'mode', title: 'Mode' },
  { value: 'output', title: 'Output' },
  { value: 'run', title: 'Run' },
]

describe('wizard state helpers', () => {
  it('creates one active state and pending remaining states', () => {
    expect(createWizardStates(steps, 'source')).toEqual({
      source: { status: 'active' },
      mode: { status: 'pending' },
      output: { status: 'pending' },
      run: { status: 'pending' },
    })
  })

  it('normalizes unknown and duplicate snapshot entries', () => {
    const result = normalizeWizardSnapshot(steps, {
      version: 1,
      activeStep: 'removed',
      visitedPath: ['source', 'mode', 'mode', 'removed'],
      states: {
        source: { status: 'complete' },
        mode: { status: 'active' },
        removed: { status: 'error', error: 'old' },
      },
      completed: false,
    })
    expect(result.error).toBeUndefined()
    expect(result.snapshot).toMatchObject({
      activeStep: 'mode',
      visitedPath: ['source', 'mode'],
      completed: false,
    })
    expect(result.snapshot?.states.removed).toBeUndefined()
  })

  it('rejects unsupported snapshot versions', () => {
    const result = normalizeWizardSnapshot(steps, {
      version: 2 as 1,
      activeStep: 'source',
      visitedPath: ['source'],
      states: {},
      completed: false,
    })
    expect(result).toEqual({ error: 'Unsupported wizard snapshot version: 2' })
  })

  it('invalidates selected states without mutating input', () => {
    const original = {
      source: { status: 'complete' as const },
      mode: { status: 'complete' as const },
      output: { status: 'error' as const, error: 'Choose a folder' },
      run: { status: 'pending' as const },
    }
    expect(invalidateWizardStates(original, ['mode', 'output'])).toEqual({
      source: { status: 'complete' },
      mode: { status: 'pending' },
      output: { status: 'pending' },
      run: { status: 'pending' },
    })
    expect(original.output.error).toBe('Choose a folder')
  })

  it('builds a detached versioned snapshot', () => {
    const states = createWizardStates(steps, 'source')
    const snapshot = buildWizardSnapshot('source', ['source'], states, false)
    states.source.status = 'error'
    expect(snapshot).toEqual({
      version: 1,
      activeStep: 'source',
      visitedPath: ['source'],
      states: {
        source: { status: 'active' },
        mode: { status: 'pending' },
        output: { status: 'pending' },
        run: { status: 'pending' },
      },
      completed: false,
    })
  })
})
```

- [ ] **Step 2: Run the test and confirm it fails because the module does not exist**

Run: `npm test -- test/wizard-state.test.ts` from `plugin-ui/vue`

Expected: FAIL with an import-resolution error for `../src/wizard`.

- [ ] **Step 3: Implement the public types and pure helpers**

Create `plugin-ui/vue/src/wizard.ts` with these exact exports and rules:

```ts
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

const copyState = (state: FyWizardStepState): FyWizardStepState =>
  state.status === 'error' && state.error
    ? { status: 'error', error: state.error }
    : { status: state.status }

export function createWizardStates(
  steps: FyWizardStep[],
  activeStep: string,
): Record<string, FyWizardStepState> {
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
  if (steps.length === 0) return { error: 'Wizard requires at least one step' }

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
```

- [ ] **Step 4: Export the model and remove the duplicate local `FyWizardStep` declaration**

Add to `plugin-ui/vue/src/index.ts`:

```ts
export {
  FY_WIZARD_SNAPSHOT_VERSION,
  buildWizardSnapshot,
  createWizardStates,
  invalidateWizardStates,
  normalizeWizardSnapshot,
} from './wizard'
export type {
  FyWizardSnapshot,
  FyWizardSnapshotResult,
  FyWizardStep,
  FyWizardStepState,
  FyWizardStepStatus,
  FyWizardValidationResult,
} from './wizard'
```

Import `FyWizardStep` from `../wizard` in `FyStepWizard.vue`; do not export a second type from the component file.

- [ ] **Step 5: Run focused tests and type/build checks**

Run from `plugin-ui/vue`:

```bash
npm test -- test/wizard-state.test.ts
npm run build
```

Expected: both commands PASS and the generated declaration surface includes all new types.

- [ ] **Step 6: Conditional checkpoint**

If and only if the user has authorized commits, commit with: `✨ feat(plugin-ui): add wizard state model`

---

### Task 2: Replace the linear controller with the controlled transition engine

**Files:**
- Modify: `plugin-ui/vue/src/components/FyStepWizard.vue`
- Modify: `plugin-ui/vue/test/workflow-components.test.ts`

**Interfaces consumed:** Task 1 helpers and types. **Interfaces produced:** controlled props/events/slots plus deprecated `canContinue` behavior.

- [ ] **Step 1: Replace the old wizard tests with failing controlled-transition tests**

Add focused cases that mount through `createFengYuVuetify()` and verify:

```ts
it('validates once, completes the current step, and follows resolveNext', async () => {
  let resolveValidation!: (valid: boolean) => void
  const validateStep = vi.fn(() => new Promise<boolean>((resolve) => {
    resolveValidation = resolve
  }))
  const wrapper = mount(FyStepWizard, {
    props: {
      steps,
      modelValue: 'source',
      states: createWizardStates(steps, 'source'),
      validateStep,
      resolveNext: () => 'output',
      'onUpdate:modelValue': (value: string) => wrapper.setProps({ modelValue: value }),
      'onUpdate:states': (value: Record<string, FyWizardStepState>) =>
        wrapper.setProps({ states: value }),
    },
    global: { plugins: [createFengYuVuetify()] },
  })

  await wrapper.get('[data-wizard-next]').trigger('click')
  await wrapper.get('[data-wizard-next]').trigger('click')
  expect(validateStep).toHaveBeenCalledTimes(1)
  expect(wrapper.emitted('update:states')?.at(0)?.[0]).toMatchObject({
    source: { status: 'validating' },
  })

  resolveValidation(true)
  await flushPromises()
  expect(wrapper.emitted('transition')?.at(-1)).toEqual(['source', 'output'])
  expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['output'])
})
```

Also add exact cases for:

- `{ valid: false, message: 'Select a workbook' }` leaves `source` active as `error`, emits `validation-error`, and changes the primary action label to `retryText`.
- `resolveNext` returning `null` emits `update:completed(true)`, `complete`, and the final snapshot.
- A branch from `source` directly to `output` marks `mode` as `skipped`.
- Back and direct navigation only allow IDs in `visitedPath`; neither invokes validation.
- A changed `snapshot` prop normalizes and emits controlled update events; version 2 emits `restore-error` and does not alter controlled state.
- Unmounting during validation aborts the signal and emits no validation error.
- The deprecated `canContinue(from, to)` still works when controlled props are omitted.

- [ ] **Step 2: Run the component test and confirm the old component fails the new contract**

Run: `npm test -- test/workflow-components.test.ts` from `plugin-ui/vue`

Expected: FAIL on missing state props/events, branch handling, snapshots, and abort behavior.

- [ ] **Step 3: Define the canonical and compatibility props/events in `FyStepWizard.vue`**

Use this shape; `modelValue`, `states`, and `canContinue` remain optional only for 1.x compatibility, while documentation treats the first two as required:

```ts
interface Props<TContext = unknown> {
  steps: FyWizardStep[]
  modelValue?: string
  states?: Record<string, FyWizardStepState>
  context?: TContext
  completed?: boolean
  snapshot?: FyWizardSnapshot
  validateStep?: (
    step: string,
    context: TContext,
    signal: AbortSignal,
  ) => boolean | FyWizardValidationResult | Promise<boolean | FyWizardValidationResult>
  resolveNext?: (step: string, context: TContext) => string | null
  invalidateAfter?: (changedStep: string, context: TContext) => string[]
  canContinue?: (from: string, to: string) => boolean | Promise<boolean>
  backText?: string
  nextText?: string
  finishText?: string
  retryText?: string
  optionalText?: string
}

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'update:states': [states: Record<string, FyWizardStepState>]
  'update:completed': [completed: boolean]
  transition: [from: string, to: string]
  'validation-error': [step: string, message?: string]
  'restore-error': [message: string]
  snapshot: [snapshot: FyWizardSnapshot]
  complete: [snapshot: FyWizardSnapshot]
}>()
```

Defaults must be `Back`, `Next`, `Finish`, `Retry`, and `optional`, with every string overridable.

- [ ] **Step 4: Implement one guarded asynchronous transition**

Implement a single `advance()` path with these concrete invariants:

```ts
let validationController: AbortController | undefined
const transitioning = ref(false)

function validationResult(
  result: boolean | FyWizardValidationResult,
): FyWizardValidationResult {
  return typeof result === 'boolean' ? { valid: result } : result
}

async function advance(): Promise<void> {
  if (transitioning.value || props.completed) return
  const from = activeStep.value
  const to = resolveNextStep(from)
  transitioning.value = true
  validationController?.abort()
  validationController = new AbortController()
  publishStates(withState(effectiveStates.value, from, { status: 'validating' }))

  try {
    const raw = props.validateStep
      ? await props.validateStep(from, props.context as TContext, validationController.signal)
      : props.canContinue
        ? await props.canContinue(from, to ?? from)
        : true
    if (validationController.signal.aborted) return
    const result = validationResult(raw)
    if (!result.valid) {
      publishError(from, result.message)
      return
    }
    completeAndMove(from, to)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    publishError(from, error instanceof Error ? error.message : undefined)
  } finally {
    transitioning.value = false
  }
}
```

`completeAndMove` must clone state, mark the source complete, calculate skipped declaration-order steps when jumping forward, append the destination once to `visitedPath`, emit controlled updates in `modelValue → states → completed → transition/complete → snapshot` order, and never mutate props.

Declare the SFC as `<script setup lang="ts" generic="TContext = unknown">` and call `defineProps<Props<TContext>>()` so the cast in `advance()` uses the component's actual generic context type.

- [ ] **Step 5: Implement restoration, back navigation, invalidation, and cleanup**

Use `watch(() => props.snapshot, restoreFromSnapshot, { immediate: true })`, keyed by object identity. `restoreFromSnapshot` calls `normalizeWizardSnapshot` and applies only its output through events. Keep `visitedPath` as the component's sole internal navigation history; initialize it from the active step for legacy callers and replace it from a valid snapshot.

Expose slot actions with this stable shape:

```ts
interface FyWizardSlotActions {
  next: () => Promise<void>
  back: () => void
  goTo: (step: string) => void
  invalidate: (changedStep: string) => void
}
```

`invalidate(changedStep)` calls `invalidateAfter` when provided; otherwise invalidates entries after `changedStep` in the actual visited path. It also emits `update:completed(false)` and a fresh snapshot. Register `onBeforeUnmount(() => validationController?.abort())`.

- [ ] **Step 6: Run focused component tests**

Run: `npm test -- test/wizard-state.test.ts test/workflow-components.test.ts` from `plugin-ui/vue`

Expected: PASS, including duplicate-click and abort cases.

- [ ] **Step 7: Conditional checkpoint**

If and only if the user has authorized commits, commit with: `✨ feat(plugin-ui): make step wizard stateful`

---

### Task 3: Implement the responsive hybrid Codex UI and visual contract

**Files:**
- Modify: `plugin-ui/vue/src/components/FyStepWizard.vue`
- Modify: `plugin-ui/vue/e2e/Workbench.vue`
- Modify: `plugin-ui/vue/e2e/workbench.spec.ts`
- Modify: `plugin-ui/vue/playwright.config.ts`
- Regenerate: `plugin-ui/vue/e2e/workbench.spec.ts-snapshots/*` only after inspecting the rendered result

- [ ] **Step 1: Add failing DOM/accessibility assertions**

Extend `workflow-components.test.ts` to require:

```ts
expect(wrapper.get('[data-wizard]').attributes('aria-busy')).toBe('false')
expect(wrapper.findAll('[data-wizard-step]')).toHaveLength(steps.length)
expect(wrapper.get('[data-wizard-current]').text()).toContain('Step 1 / 4')
expect(wrapper.get('[data-wizard-error]').attributes('role')).toBe('alert')
expect(wrapper.get('[data-wizard-error]').attributes('aria-live')).toBe('polite')
```

The test must also assert that the active navigation item has `aria-current="step"`, disabled future items cannot be clicked, and a completed page returns focus to `[data-wizard-content]`.

- [ ] **Step 2: Add semantic hooks and component-scoped responsive CSS**

The root and major regions must have stable selectors:

```html
<section data-wizard :aria-busy="transitioning">
  <nav class="fy-wizard__desktop-path" aria-label="Progress">
    <!-- all declared steps, each with data-wizard-step and textual status -->
  </nav>
  <div class="fy-wizard__compact-path" data-wizard-current>
    <!-- Step N / M, active title, plus every error step -->
  </div>
  <div ref="contentElement" class="fy-wizard__content" data-wizard-content tabindex="-1">
    <!-- active step or complete slot -->
  </div>
</section>
```

Add scoped CSS with one breakpoint and no dependency on the dirty shared stylesheet:

```css
.fy-wizard__desktop-path {
  display: grid;
  grid-template-columns: repeat(var(--fy-wizard-step-count), minmax(0, 1fr));
  gap: 8px;
}

.fy-wizard__compact-path { display: none; }

@media (max-width: 720px) {
  .fy-wizard__desktop-path { display: none; }
  .fy-wizard__compact-path { display: grid; gap: 8px; }
  .fy-wizard__actions { position: sticky; bottom: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .fy-wizard__status-icon { transition: none; }
}
```

Use existing Codex CSS variables/tokens for surface, border, typography, focus ring, and spacing. Every status must have icon/text semantics; color alone is insufficient.

- [ ] **Step 3: Replace the workbench body with a deterministic full-shell wizard fixture**

`Workbench.vue` must render `FyPluginShell + FyStepWizard` and expose controls or URL query state for exactly these fixtures: `normal`, `validating`, `error`, `skipped`, and `complete`. Use deterministic local state; do not call a real worker or use timers.

- [ ] **Step 4: Make every screenshot viewport and theme explicit**

Use a table-driven Playwright test:

```ts
const cases = [
  { name: 'desktop-light-normal', width: 1280, height: 900, theme: 'light', state: 'normal' },
  { name: 'desktop-dark-error', width: 1280, height: 900, theme: 'dark', state: 'error' },
  { name: 'desktop-light-skipped', width: 1280, height: 900, theme: 'light', state: 'skipped' },
  { name: 'narrow-light-normal', width: 390, height: 844, theme: 'light', state: 'normal' },
  { name: 'narrow-dark-validating', width: 390, height: 844, theme: 'dark', state: 'validating' },
  { name: 'narrow-light-complete', width: 390, height: 844, theme: 'light', state: 'complete' },
] as const

for (const fixture of cases) {
  test(fixture.name, async ({ page }) => {
    await page.setViewportSize({ width: fixture.width, height: fixture.height })
    await page.goto(`/?theme=${fixture.theme}&state=${fixture.state}`)
    await expect(page.locator('[data-workbench-shell]')).toHaveScreenshot(`${fixture.name}.png`, {
      animations: 'disabled',
    })
  })
}
```

Wrap the fixture's existing `FyPluginShell` with `data-workbench-shell`; do not edit the user's in-progress `FyPluginShell.vue` merely to add a test selector. Do not screenshot only the wizard: the acceptance surface is the full plugin shell.

- [ ] **Step 5: Run component and visual tests, inspect failures, then update baselines once**

Run from `plugin-ui/vue`:

```bash
npm test -- test/workflow-components.test.ts
npm run test:visual
```

If the existing script uses a different Playwright name, use the exact `package.json` script. First run without `--update-snapshots`, inspect each diff, fix layout defects, then regenerate approved baselines and rerun normally. Expected: all six explicit visual cases PASS at their declared viewport.

- [ ] **Step 6: Conditional checkpoint**

If and only if the user has authorized commits, commit with: `✨ feat(plugin-ui): add responsive wizard layout`

---

### Task 4: Add Excel-owned versioned progress persistence

**Files:**
- Create: `OfficialPlugins/plugin-excel/ui-src/src/excelWizardState.ts`
- Create: `OfficialPlugins/plugin-excel/ui-src/src/excelWizardState.test.ts`

**Interfaces produced:** serializable Excel draft/record plus injected-`Storage` load/save/clear functions. The adapter must not import Vue.

- [ ] **Step 1: Write failing storage contract tests**

```ts
import { beforeEach, describe, expect, it } from 'vitest'
import {
  EXCEL_WIZARD_STORAGE_KEY,
  clearExcelWizardRecord,
  loadExcelWizardRecord,
  saveExcelWizardRecord,
  type ExcelWizardRecord,
} from './excelWizardState'

const record: ExcelWizardRecord = {
  version: 1,
  wizard: {
    version: 1,
    activeStep: 'mode',
    visitedPath: ['source', 'mode'],
    states: { source: { status: 'complete' }, mode: { status: 'active' } },
    completed: false,
  },
  draft: {
    sourceFileRef: {
      id: 'source-token',
      name: 'sales.xlsx',
      kind: 'file',
      access: 'read',
      size: 1024,
    },
    sessionId: 'session-1',
    mode: 'BY_SHEET',
    selectedSheets: ['Q1'],
    splitSheet: null,
    splitColumn: null,
    filePrefix: '',
    complexEntries: [],
  },
}

describe('Excel wizard persistence', () => {
  beforeEach(() => sessionStorage.clear())

  it('round-trips a versioned record', () => {
    saveExcelWizardRecord(sessionStorage, record)
    expect(loadExcelWizardRecord(sessionStorage)).toEqual(record)
  })

  it('drops corrupt and unsupported records', () => {
    sessionStorage.setItem(EXCEL_WIZARD_STORAGE_KEY, '{bad json')
    expect(loadExcelWizardRecord(sessionStorage)).toBeNull()
    sessionStorage.setItem(EXCEL_WIZARD_STORAGE_KEY, JSON.stringify({ version: 2 }))
    expect(loadExcelWizardRecord(sessionStorage)).toBeNull()
  })

  it('clears the owned key only', () => {
    sessionStorage.setItem('unrelated', 'keep')
    saveExcelWizardRecord(sessionStorage, record)
    clearExcelWizardRecord(sessionStorage)
    expect(sessionStorage.getItem(EXCEL_WIZARD_STORAGE_KEY)).toBeNull()
    expect(sessionStorage.getItem('unrelated')).toBe('keep')
  })
})
```

The `FileRef` literal above matches the current SDK's `id`, `name`, `kind`, `access`, and `size` contract and keeps the record JSON-serializable.

- [ ] **Step 2: Run the test and confirm the adapter is missing**

Run: `npm test -- src/excelWizardState.test.ts` from `OfficialPlugins/plugin-excel/ui-src`

Expected: FAIL with import-resolution error.

- [ ] **Step 3: Implement the adapter with explicit schema checks**

```ts
import type { FileRef } from '@infinia/plugin-sdk'
import type { FyWizardSnapshot } from '@infinia/plugin-ui'

export const EXCEL_WIZARD_STORAGE_KEY = 'fengyu:fan.summer.excel:wizard:v1'

export interface ExcelComplexEntry {
  fieldName: string
  sheetName: string
  headerIndex: number
  columnIndex: number
  copyAll: boolean
}

export interface ExcelWizardDraft {
  sourceFileRef: FileRef | null
  sessionId: string
  mode: 'BY_SHEET' | 'BY_COLUMN' | 'COMPLEX'
  selectedSheets: string[]
  splitSheet: string | null
  splitColumn: string | null
  filePrefix: string
  complexEntries: ExcelComplexEntry[]
}

export interface ExcelWizardRecord {
  version: 1
  wizard: FyWizardSnapshot
  draft: ExcelWizardDraft
}

function isRecord(value: unknown): value is ExcelWizardRecord {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<ExcelWizardRecord>
  return candidate.version === 1
    && candidate.wizard?.version === 1
    && typeof candidate.draft?.sessionId === 'string'
    && ['BY_SHEET', 'BY_COLUMN', 'COMPLEX'].includes(candidate.draft.mode ?? '')
}

export function loadExcelWizardRecord(storage: Storage): ExcelWizardRecord | null {
  const raw = storage.getItem(EXCEL_WIZARD_STORAGE_KEY)
  if (!raw) return null
  try {
    const value: unknown = JSON.parse(raw)
    return isRecord(value) ? value : null
  } catch {
    return null
  }
}

export function saveExcelWizardRecord(
  storage: Storage,
  record: ExcelWizardRecord,
): void {
  storage.setItem(EXCEL_WIZARD_STORAGE_KEY, JSON.stringify(record))
}

export function clearExcelWizardRecord(storage: Storage): void {
  storage.removeItem(EXCEL_WIZARD_STORAGE_KEY)
}
```

- [ ] **Step 4: Run the focused storage tests**

Run: `npm test -- src/excelWizardState.test.ts` from `OfficialPlugins/plugin-excel/ui-src`

Expected: PASS.

- [ ] **Step 5: Conditional checkpoint**

If and only if the user has authorized commits, commit with: `✨ feat(excel): persist wizard draft state`

---

### Task 5: Migrate Excel to the controlled wizard and recovery flow

**Files:**
- Modify: `OfficialPlugins/plugin-excel/ui-src/src/ExcelSplitter.vue`
- Create: `OfficialPlugins/plugin-excel/ui-src/src/ExcelSplitter.test.ts`

**Interfaces consumed:** controlled wizard API and `excelWizardState.ts`. **Behavior produced:** Source → Mode → Output → Run with async state, retry, invalidation, restore, and explicit completion.

- [ ] **Step 1: Write failing integration tests around a fake SDK client**

Mount `ExcelSplitter` with the same Vuetify/plugin client setup used elsewhere in the package. Cover these externally visible flows:

1. Source selection enters `validating`, calls the current analyze RPC once, and advances to Mode only after success.
2. Analyze rejection keeps Source active, renders the worker message, and Retry calls analyze once more.
3. Changing mode invalidates Output and Run, clears a previous result, and emits/saves `completed=false`.
4. Output selection is required before Run.
5. Run duplicate clicks make one split RPC; success displays the `#complete` page and still allows navigation back to visited configuration.
6. A valid stored draft restores fields, calls analyze again to validate source/session, clears the stored output directory, and resumes at Output or the last earlier valid step.
7. A failed restored source grant returns to Source with an error and does not call split.
8. A corrupt storage record starts cleanly at Source.

Use `flushPromises()` rather than timers. Assert emitted/visible behavior and RPC arguments, not component internals.

- [ ] **Step 2: Run the new test and confirm current linear integration fails**

Run: `npm test -- src/ExcelSplitter.test.ts` from `OfficialPlugins/plugin-excel/ui-src`

Expected: FAIL because the current component still uses `canContinue`, has no controlled states, and has no recovery adapter.

- [ ] **Step 3: Add controlled navigation state and pure downstream invalidation**

Initialize the component with:

```ts
const activeStep = ref('source')
const wizardStates = ref<Record<string, FyWizardStepState>>(
  createWizardStates(steps, 'source'),
)
const wizardCompleted = ref(false)
const restoreSnapshot = shallowRef<FyWizardSnapshot>()

function invalidateFrom(changedStep: string): string[] {
  const dependencies: Record<string, string[]> = {
    source: ['mode', 'output', 'run'],
    mode: ['output', 'run'],
    output: ['run'],
    run: [],
  }
  return dependencies[changedStep] ?? []
}

function resolveNext(step: string): string | null {
  return ({ source: 'mode', mode: 'output', output: 'run', run: null } as const)[step]
    ?? null
}
```

Import the public types/helpers from `@infinia/plugin-ui`; do not duplicate their definitions locally.

Add an Excel-owned invalidation function and call it from the existing source, mode/configuration, and output change handlers:

```ts
function invalidateDependencies(changedStep: string): void {
  wizardStates.value = invalidateWizardStates(
    wizardStates.value,
    invalidateFrom(changedStep),
  )
  wizardCompleted.value = false
  result.value = null
}
```

Changing Source also clears session/sheet analysis and the persisted record. Changing Mode or any mode-specific rule calls `invalidateDependencies('mode')`; choosing another output directory calls `invalidateDependencies('output')`. Do not use a broad deep watcher that fires while a saved draft is being restored.

- [ ] **Step 4: Move business checks into one abort-aware `validateStep`**

Use the current worker method names and payload types already present in `ExcelSplitter.vue`. Preserve them exactly while routing each phase through:

```ts
async function validateStep(
  step: string,
  _context: unknown,
  signal: AbortSignal,
): Promise<FyWizardValidationResult> {
  if (step === 'source') return validateSource(signal)
  if (step === 'mode') return validateMode()
  if (step === 'output') {
    return outputDirRef.value
      ? { valid: true }
      : { valid: false, message: 'Choose an output folder' }
  }
  if (step === 'run') return runSplit(signal)
  return { valid: false, message: `Unknown wizard step: ${step}` }
}
```

Before and after each awaited SDK call, check `signal.aborted`; pass `{ signal }` as the third argument to `client.invoke`, and throw `new DOMException('Aborted', 'AbortError')` when stale. `validateMode` first applies the current local mode rules and then runs the existing `configure` RPC with the signal. `runSplit` returns `{ valid: true }` only after a valid result is stored; errors return `{ valid: false, message }` rather than independently changing the active step.

- [ ] **Step 5: Wire the controlled template and localized status text**

Replace `canContinue`/`@complete="onComplete"` with:

```html
<FyStepWizard
  v-model="activeStep"
  v-model:states="wizardStates"
  v-model:completed="wizardCompleted"
  :steps="steps"
  :snapshot="restoreSnapshot"
  :context="wizardContext"
  :validate-step="validateStep"
  :resolve-next="resolveNext"
  :invalidate-after="invalidateFrom"
  back-text="Back"
  next-text="Next"
  finish-text="Run split"
  retry-text="Retry"
  optional-text="optional"
  @snapshot="persistSnapshot"
  @validation-error="onValidationError"
>
```

Keep the existing named Source, Mode, Output, and Run slots. Add a `#complete` slot that displays the split result and provides a button that calls the existing download/open-result action. Do not hide errors behind toasts; render the current-step error in the wizard and use existing notifications only as a secondary signal.

- [ ] **Step 6: Restore and revalidate business resources safely**

On mount:

```ts
async function restoreProgress(): Promise<void> {
  const record = loadExcelWizardRecord(sessionStorage)
  if (!record) return
  applyDraft(record.draft)
  outputDirRef.value = null
  result.value = null
  const sourceResult = await validateSource(new AbortController().signal)
  if (!sourceResult.valid) {
    activeStep.value = 'source'
    wizardStates.value = {
      ...createWizardStates(steps, 'source'),
      source: { status: 'error', error: sourceResult.message },
    }
    wizardCompleted.value = false
    return
  }
  restoreSnapshot.value = {
    ...record.wizard,
    activeStep: record.wizard.activeStep === 'run' ? 'output' : record.wizard.activeStep,
    states: invalidateWizardStates(record.wizard.states, ['output', 'run']),
    completed: false,
  }
}
```

If the real SDK exposes a non-mutating output-grant validation method at implementation time, add a focused test and retain Output only when that check succeeds. Otherwise the explicit reselection behavior above is required.

`persistSnapshot(snapshot)` combines the snapshot with a freshly copied serializable draft and writes only via `saveExcelWizardRecord(sessionStorage, record)`. Selecting a new source clears the old record before analysis. Keep a successful completion snapshot so reload can revalidate and return to Output; do not pretend a stale result can be downloaded after reload.

- [ ] **Step 7: Run Excel unit tests and production build**

Run from `OfficialPlugins/plugin-excel/ui-src`:

```bash
npm test -- src/excelWizardState.test.ts src/ExcelSplitter.test.ts src/officialSdk.test.ts
npm run build
```

Expected: all focused tests PASS and the plugin UI production bundle builds without type errors.

- [ ] **Step 8: Conditional checkpoint**

If and only if the user has authorized commits, commit with: `✨ feat(excel): adopt stateful step wizard`

---

### Task 6: Document the public API, verify package consumers, and finish

**Files:**
- Modify: `docs/en/plugins/ui-components.md`
- Modify: `docs/zh/plugins/ui-components.md`
- Modify: `docs/en/plugins/official-excel.md`
- Modify: `docs/zh/plugins/official-excel.md`
- Verify only: `plugin-cli/**`, `OfficialPlugins/**`, `plugin-ui/vue/**`

- [ ] **Step 1: Update English and Chinese UI-component docs in parallel structure**

Both pages must include:

- the six statuses and controlled `v-model`, `v-model:states`, `v-model:completed` example;
- the exact props/events/slots from the new API;
- `validateStep` cancellation guidance;
- `resolveNext`, `invalidateAfter`, and snapshot normalization semantics;
- the rule that persistence belongs to the consuming plugin;
- responsive desktop/narrow behavior and non-color status semantics;
- a clearly marked 1.x compatibility note for deprecated `canContinue` and omitted controlled props.

Use this minimal canonical example in both languages, translating prose but not identifiers:

```vue
<FyStepWizard
  v-model="activeStep"
  v-model:states="states"
  v-model:completed="completed"
  :steps="steps"
  :validate-step="validateStep"
  :resolve-next="resolveNext"
  @snapshot="saveProgress"
>
  <template #source>Source form</template>
  <template #complete>Completed result</template>
</FyStepWizard>
```

- [ ] **Step 2: Correct the Excel workflow docs**

Describe the actual four steps as `Source → Mode → Output → Run`, the three current modes, inline retry behavior, downstream invalidation, and reload recovery. State explicitly that output-directory permission must be selected again after reload under the current SDK contract.

- [ ] **Step 3: Search every repository consumer and remove accidental old usage**

Run from the repository root:

```bash
rg -n "FyStepWizard|canContinue" plugin-ui OfficialPlugins plugin-cli docs
```

Expected:

- `canContinue` remains only in the compatibility implementation/test and compatibility documentation.
- Excel uses controlled props and events.
- no legacy JavaFX `StepWizard` is introduced.

- [ ] **Step 4: Run focused final verification**

Run exactly:

```bash
(cd plugin-ui/vue && npm test -- test/wizard-state.test.ts test/workflow-components.test.ts)
(cd plugin-ui/vue && npm run build)
(cd plugin-ui/vue && npm run test:visual)
(cd OfficialPlugins/plugin-excel/ui-src && npm test -- src/excelWizardState.test.ts src/ExcelSplitter.test.ts src/officialSdk.test.ts)
(cd OfficialPlugins/plugin-excel/ui-src && npm run build)
git diff --check
```

Expected: every command exits 0. Record the exact test counts and any baseline files changed in the handoff.

- [ ] **Step 5: Review the final diff against scope and user work**

Run:

```bash
git status --short
git diff -- plugin-ui/vue OfficialPlugins/plugin-excel docs/en/plugins docs/zh/plugins docs/superpowers
```

Confirm no `OfficialPlugins/plugin-offlinepython/**` file was changed by this work, no existing edits in the three dirty `plugin-ui` files were overwritten, no storage API appears in `FyStepWizard.vue`, and no worker/manifest/protocol file changed.

- [ ] **Step 6: Conditional final commit**

If and only if the user has authorized commits, commit the remaining docs/verification changes with: `📝 docs(plugins): document stateful wizard`

## Acceptance Checklist

- [ ] Desktop shows the complete horizontal path; narrow layout focuses the current step while retaining every error step.
- [ ] Pending, active, validating, complete, error, and skipped states are visible and accessible without relying on color alone.
- [ ] Async validation is single-flight, retryable, abortable, and immune to stale completion.
- [ ] Conditional branches update skipped states and actual visited history.
- [ ] Back navigation is free only across visited steps; future steps stay locked.
- [ ] Upstream Excel changes invalidate dependent downstream state and prior results.
- [ ] Component snapshots are JSON-only and storage-agnostic.
- [ ] Excel owns versioned persistence, revalidates its source/session, and safely requires output reselection.
- [ ] Final completion is explicit and users can return to visited configuration.
- [ ] Public package exports, EN/ZH docs, unit tests, visual tests, and Excel build all agree on the same API.
- [ ] Existing unrelated user changes remain intact.
