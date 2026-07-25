/**
 * Workflow component tests: FyStepWizard, FyConfirmDialog, FyTaskTable.
 *
 * These exercise the behavioral contracts documented for each component
 * without coupling to Vuetify internals:
 * - FyStepWizard validates guarded transitions, follows branches, controls
 *   snapshots/state, and retains the deprecated `canContinue` contract.
 * - FyConfirmDialog requires an explicit confirm action (`data-action="confirm"`),
 *   and a destructive dialog labels that action textually.
 * - FyTaskTable renders one row per task with a status icon + label and exposes
 *   the host (`FyTaskRow`) data via its rendered output.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { h } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createFengYuVuetify,
  createWizardStates,
  FyConfirmDialog,
  FyStepWizard,
  FyTaskTable,
} from '../src'
import type {
  FyTaskRow,
  FyWizardSnapshot,
  FyWizardStep,
  FyWizardStepState,
} from '../src'

const global = { plugins: [createFengYuVuetify()] }
const wizardSource = readFileSync(
  'src/components/FyStepWizard.vue',
  'utf8',
)

describe('FyStepWizard', () => {
  const steps: FyWizardStep[] = [
    { value: 'source', title: 'Source' },
    { value: 'mode', title: 'Mode', optional: true },
    { value: 'output', title: 'Output' },
    { value: 'run', title: 'Run' },
  ]

  it('allows localized wizard actions to wrap naturally', () => {
    expect(wizardSource).toMatch(
      /\.fy-wizard__actions\s*\{[^}]*flex-wrap:\s*wrap;/s,
    )
  })

  it('fails fast with a clear error for duplicate step values', () => {
    expect(() => mount(FyStepWizard, {
      global,
      props: {
        steps: [
          { value: 'source', title: 'First source' },
          { value: 'source', title: 'Second source' },
        ],
        states: { source: { status: 'active' } },
      },
    })).toThrow('Wizard step values must be unique: source')
  })

  it('safely stops transitions when step definitions become invalid', async () => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: { steps },
    })

    await wrapper.setProps({
      steps: [
        { value: 'source', title: 'First source' },
        { value: 'source', title: 'Second source' },
      ],
    })
    await flushPromises()

    expect(wrapper.emitted('validation-error')?.at(-1)).toEqual([
      'source',
      'Wizard step values must be unique: source',
    ])
    expect(wrapper.findAll('[data-wizard-step]')).toHaveLength(0)
    expect(wrapper.get('[data-wizard-next]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('transition')).toBeUndefined()
    expect(wrapper.emitted('snapshot')).toBeUndefined()
  })

  it('exposes the responsive progress and error semantics without unlocking future steps', async () => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'source',
        states: {
          ...createWizardStates(steps, 'source'),
          source: { status: 'error', error: 'Select a workbook' },
        },
      },
    })

    expect(wrapper.get('[data-wizard]').attributes('aria-busy')).toBe('false')
    expect(wrapper.findAll('[data-wizard-step]')).toHaveLength(steps.length)
    expect(wrapper.get('[data-wizard-current]').text()).toContain('Step 1 / 4')
    expect(wrapper.get('[data-wizard-error]').attributes('role')).toBe('alert')
    expect(wrapper.get('[data-wizard-error]').attributes('aria-live')).toBe('polite')
    expect(wrapper.get('[data-wizard-step="source"]').attributes('aria-current')).toBe('step')

    const futureStep = wrapper.get('[data-wizard-step="run"]')
    expect(futureStep.attributes('disabled')).toBeDefined()
    await futureStep.trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('renders the complete page and moves focus to the wizard content', async () => {
    const wrapper = mount(FyStepWizard, {
      global,
      attachTo: document.body,
      props: {
        steps: [steps[0]],
        resolveNext: () => null,
      },
      slots: {
        complete: () => h('p', 'Import complete'),
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-wizard-content]').text()).toContain('Import complete')
    expect(document.activeElement).toBe(wrapper.get('[data-wizard-content]').element)
    wrapper.unmount()
  })

  it('renders a focusable localized error fallback when validation fails without text', async () => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        validateStep: () => false,
        labels: { status: { error: 'Needs attention' } },
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()

    const error = wrapper.get('[data-wizard-error-container]')
    expect(wrapper.get('[data-wizard-step="source"]').attributes('aria-describedby')).toBe(
      error.attributes('id'),
    )
    expect(error.attributes('tabindex')).toBe('-1')
    expect(wrapper.findAll('[data-wizard-error-container]')).toHaveLength(1)
    expect(wrapper.get('[data-wizard-error]').text()).toContain('Needs attention')
  })

  it('mounts a custom error slot when the error state has no message', async () => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        validateStep: () => false,
      },
      slots: {
        error: ({ message }: { message?: string }) =>
          h('span', { 'data-custom-message-less-error': '' }, message ?? 'Custom fallback'),
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-custom-message-less-error]').text()).toBe('Custom fallback')
    expect(wrapper.findAll('[data-wizard-error-container]')).toHaveLength(1)
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(1)
  })

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
      global,
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

  it('validates before routing so resolveNext sees validation-updated context', async () => {
    const context = { next: 'mode' }
    const callOrder: string[] = []
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        context,
        validateStep: () => {
          callOrder.push('validate')
          context.next = 'output'
          return true
        },
        resolveNext: (_step: string, rawContext: unknown) => {
          callOrder.push('resolve')
          const value = rawContext as typeof context
          return value.next
        },
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()

    expect(callOrder).toEqual(['validate', 'resolve'])
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['output'])
  })

  it('does not route when canonical validation fails', async () => {
    const resolveNext = vi.fn(() => 'mode')
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        validateStep: () => false,
        resolveNext,
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()

    expect(resolveNext).not.toHaveBeenCalled()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it.each([
    {
      name: 'resolver exception',
      resolveNext: () => { throw new Error('Routing failed') },
      message: 'Routing failed',
    },
    {
      name: 'unknown target',
      resolveNext: () => 'removed',
      message: 'Unknown wizard step: removed',
    },
  ])('turns a $name into a controlled current-step error', async ({ resolveNext, message }) => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        validateStep: () => true,
        resolveNext,
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('validation-error')?.at(-1)).toEqual(['source', message])
    expect(wrapper.emitted('update:states')?.at(-1)?.[0]).toMatchObject({
      source: { status: 'error', error: message },
    })
    expect(wrapper.emitted('snapshot')?.at(-1)?.[0]).toMatchObject({
      activeStep: 'source',
      visitedPath: ['source'],
      completed: false,
    })
    expect(wrapper.emitted('snapshot')?.at(-1)?.[0]).not.toHaveProperty('states.removed')
  })

  it('publishes an error and retry action when validation fails', async () => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'source',
        states: createWizardStates(steps, 'source'),
        retryText: 'Try again',
        validateStep: () => ({ valid: false, message: 'Select a workbook' }),
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('validation-error')?.at(-1)).toEqual([
      'source',
      'Select a workbook',
    ])
    expect(wrapper.emitted('update:states')?.at(-1)?.[0]).toMatchObject({
      source: { status: 'error', error: 'Select a workbook' },
    })
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(1)
    expect(wrapper.get('[data-wizard-next]').text()).toContain('Try again')
  })

  it('completes with the final snapshot when resolveNext returns null', async () => {
    const validateStep = vi.fn(() => true)
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'source',
        states: createWizardStates(steps, 'source'),
        validateStep,
        resolveNext: () => null,
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()

    const snapshot = wrapper.emitted('complete')?.at(-1)?.[0] as FyWizardSnapshot
    expect(validateStep).toHaveBeenCalledOnce()
    expect(wrapper.emitted('update:completed')?.at(-1)).toEqual([true])
    expect(snapshot).toMatchObject({
      activeStep: 'source',
      visitedPath: ['source'],
      states: { source: { status: 'complete' } },
      completed: true,
    })
    expect(wrapper.emitted('snapshot')?.at(-1)).toEqual([snapshot])
  })

  it('marks declaration-order steps skipped when a branch jumps forward', async () => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'source',
        states: createWizardStates(steps, 'source'),
        resolveNext: () => 'output',
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('update:states')?.at(-1)?.[0]).toMatchObject({
      source: { status: 'complete' },
      mode: { status: 'skipped' },
      output: { status: 'active' },
    })
  })

  it.each([
    { activeStep: 'mode', backTarget: 'source', visitedPath: ['source', 'mode'] },
    { activeStep: 'run', backTarget: 'output', visitedPath: ['source', 'mode', 'output', 'run'] },
  ])(
    'initializes model-only $activeStep history from the declared prefix',
    async ({ activeStep, backTarget, visitedPath }) => {
      const wrapper = mount(FyStepWizard, {
        global,
        props: { steps, modelValue: activeStep },
      })

      await wrapper.get('[data-wizard-back]').trigger('click')
      await flushPromises()

      expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([backTarget])
      expect(wrapper.emitted('snapshot')?.at(-1)?.[0]).toMatchObject({
        activeStep: backTarget,
        visitedPath,
      })
    },
  )

  it('backfills declaration-order history when a model-only value changes externally', async () => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: { steps, modelValue: 'source' },
    })

    await wrapper.setProps({ modelValue: 'run' })
    await flushPromises()

    for (const backTarget of ['output', 'mode', 'source']) {
      await wrapper.get('[data-wizard-back]').trigger('click')
      await flushPromises()
      expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([backTarget])
      expect(wrapper.emitted('snapshot')?.at(-1)?.[0]).toMatchObject({
        activeStep: backTarget,
        visitedPath: ['source', 'mode', 'output', 'run'],
      })
      await wrapper.setProps({ modelValue: backTarget })
      await flushPromises()
    }
  })

  it('does not infer predecessor history for canonical controlled state', () => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'output',
        states: createWizardStates(steps, 'output'),
      },
    })

    expect(wrapper.get('[data-wizard-back]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-wizard-step="source"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-wizard-step="mode"]').attributes('disabled')).toBeDefined()
  })

  it('does not backfill unvisited predecessors when controlled state changes externally', async () => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'source',
        states: createWizardStates(steps, 'source'),
      },
    })

    await wrapper.setProps({
      modelValue: 'run',
      states: {
        source: { status: 'complete' },
        mode: { status: 'pending' },
        output: { status: 'pending' },
        run: { status: 'active' },
      },
    })
    await flushPromises()

    expect(wrapper.get('[data-wizard-step="mode"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-wizard-step="output"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-wizard-back]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['source'])
    expect(wrapper.emitted('snapshot')?.at(-1)?.[0]).toMatchObject({
      activeStep: 'source',
      visitedPath: ['source', 'run'],
    })
  })

  it('allows back and direct navigation only within the visited path', async () => {
    const validateStep = vi.fn(() => true)
    const snapshot: FyWizardSnapshot = {
      version: 1,
      activeStep: 'output',
      visitedPath: ['source', 'mode', 'output'],
      states: {
        source: { status: 'complete' },
        mode: { status: 'complete' },
        output: { status: 'active' },
        run: { status: 'pending' },
      },
      completed: false,
    }
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'output',
        states: snapshot.states,
        snapshot,
        validateStep,
      },
    })
    await flushPromises()

    await wrapper.get('[data-wizard-back]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['mode'])
    await wrapper.setProps({
      modelValue: 'mode',
      states: wrapper.emitted('update:states')?.at(-1)?.[0] as Record<string, FyWizardStepState>,
    })
    await wrapper.get('[data-wizard-step="source"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['source'])
    const updateCount = wrapper.emitted('update:modelValue')?.length
    await wrapper.get('[data-wizard-step="run"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toHaveLength(updateCount ?? 0)
    expect(validateStep).not.toHaveBeenCalled()
  })

  it('prunes downstream history and locks every invalidated future step', async () => {
    const snapshot: FyWizardSnapshot = {
      version: 1,
      activeStep: 'mode',
      visitedPath: ['source', 'mode', 'output', 'run'],
      states: {
        source: { status: 'complete' },
        mode: { status: 'active' },
        output: { status: 'complete' },
        run: { status: 'error', error: 'Old failure' },
      },
      completed: true,
    }
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'mode',
        states: snapshot.states,
        completed: false,
        snapshot,
        invalidateAfter: () => ['output'],
      },
      slots: {
        mode: ({ actions }: { actions: { invalidate: (step: string) => void } }) =>
          h('button', { 'data-invalidate': '', onClick: () => actions.invalidate('mode') }, 'Change'),
      },
    })
    await flushPromises()

    await wrapper.get('[data-invalidate]').trigger('click')
    await flushPromises()

    const statesUpdate = wrapper.emitted('update:states')?.at(-1)?.[0] as Record<string, FyWizardStepState>
    const saved = wrapper.emitted('snapshot')?.at(-1)?.[0] as FyWizardSnapshot
    expect(statesUpdate).toMatchObject({
      mode: { status: 'active' },
      output: { status: 'pending' },
      run: { status: 'pending' },
    })
    expect(wrapper.emitted('update:completed')?.at(-1)).toEqual([false])
    expect(saved).toEqual({
      version: 1,
      activeStep: 'mode',
      visitedPath: ['source', 'mode'],
      states: statesUpdate,
      completed: false,
    })
    expect(wrapper.get('[data-wizard-step="output"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-wizard-step="run"]').attributes('disabled')).toBeDefined()
  })

  it('relocates an invalidated active step to the nearest valid predecessor', async () => {
    const eventOrder: string[] = []
    const snapshot: FyWizardSnapshot = {
      version: 1,
      activeStep: 'run',
      visitedPath: ['source', 'mode', 'output', 'run'],
      states: {
        source: { status: 'complete' },
        mode: { status: 'complete' },
        output: { status: 'complete' },
        run: { status: 'active' },
      },
      completed: false,
    }
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'run',
        states: snapshot.states,
        snapshot,
        invalidateAfter: () => ['output', 'run'],
        'onUpdate:modelValue': () => eventOrder.push('model'),
        'onUpdate:states': () => eventOrder.push('states'),
        'onUpdate:completed': () => eventOrder.push('completed'),
        onSnapshot: () => eventOrder.push('snapshot'),
      },
      slots: {
        run: ({ actions }: { actions: { invalidate: (step: string) => void } }) =>
          h('button', { 'data-invalidate': '', onClick: () => actions.invalidate('mode') }, 'Change'),
      },
    })
    await flushPromises()
    eventOrder.length = 0

    await wrapper.get('[data-invalidate]').trigger('click')
    await flushPromises()

    expect(eventOrder).toEqual(['model', 'states', 'completed', 'snapshot'])
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['mode'])
    expect(wrapper.emitted('snapshot')?.at(-1)?.[0]).toMatchObject({
      activeStep: 'mode',
      visitedPath: ['source', 'mode'],
      states: {
        mode: { status: 'active' },
        output: { status: 'pending' },
        run: { status: 'pending' },
      },
      completed: false,
    })
  })

  it('retains the first step in history when invalidation prunes the entire visited path', async () => {
    const firstSteps = steps.slice(0, 2)
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps: firstSteps,
        validateStep: () => true,
        resolveNext: () => 'mode',
        invalidateAfter: () => ['source'],
      },
      slots: {
        source: ({ actions }: { actions: { invalidate: (step: string) => void } }) =>
          h('button', { 'data-invalidate': '', onClick: () => actions.invalidate('source') }, 'Change'),
      },
    })

    await wrapper.get('[data-invalidate]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('snapshot')?.at(-1)?.[0]).toMatchObject({
      activeStep: 'source',
      visitedPath: ['source'],
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('snapshot')?.at(-1)?.[0]).toMatchObject({
      activeStep: 'mode',
      visitedPath: ['source', 'mode'],
    })
  })

  it('replaces stale branch history after invalidation and revalidation', async () => {
    const context = { branch: 'mode' }
    const snapshot: FyWizardSnapshot = {
      version: 1,
      activeStep: 'source',
      visitedPath: ['source', 'output', 'run'],
      states: {
        source: { status: 'active' },
        mode: { status: 'skipped' },
        output: { status: 'complete' },
        run: { status: 'complete' },
      },
      completed: false,
    }
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        context,
        modelValue: 'source',
        states: snapshot.states,
        validateStep: () => true,
        resolveNext: (_step: string, value: unknown) => (value as typeof context).branch,
        invalidateAfter: () => ['output', 'run'],
        'onUpdate:modelValue': (value: string) => wrapper.setProps({ modelValue: value }),
        'onUpdate:states': (value: Record<string, FyWizardStepState>) =>
          wrapper.setProps({ states: value }),
        'onUpdate:completed': (value: boolean) => wrapper.setProps({ completed: value }),
      },
      slots: {
        source: ({ actions }: { actions: { invalidate: (step: string) => void } }) =>
          h('button', { 'data-invalidate': '', onClick: () => actions.invalidate('source') }, 'Change'),
      },
    })
    await wrapper.setProps({ snapshot })
    await flushPromises()

    await wrapper.get('[data-invalidate]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('snapshot')?.at(-1)?.[0]).toMatchObject({
      activeStep: 'mode',
      visitedPath: ['source', 'mode'],
      states: {
        source: { status: 'complete' },
        mode: { status: 'active' },
        output: { status: 'pending' },
        run: { status: 'pending' },
      },
    })
    expect(wrapper.get('[data-wizard-step="run"]').attributes('disabled')).toBeDefined()
  })

  it('normalizes a changed snapshot and rejects unsupported versions', async () => {
    const originalStates = createWizardStates(steps, 'source')
    const wrapper = mount(FyStepWizard, {
      global,
      props: { steps, modelValue: 'source', states: originalStates },
    })
    const restored = {
      version: 1 as const,
      activeStep: 'removed',
      visitedPath: ['source', 'mode', 'mode', 'removed'],
      states: {
        source: { status: 'complete' as const },
        mode: { status: 'active' as const },
        removed: { status: 'error' as const, error: 'old' },
      },
      completed: false,
    }

    await wrapper.setProps({ snapshot: restored })
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['mode'])
    expect(wrapper.emitted('update:states')?.at(-1)?.[0]).not.toHaveProperty('removed')
    expect(wrapper.emitted('snapshot')?.at(-1)?.[0]).toMatchObject({
      activeStep: 'mode',
      visitedPath: ['source', 'mode'],
    })

    const modelUpdates = wrapper.emitted('update:modelValue')?.length
    const stateUpdates = wrapper.emitted('update:states')?.length
    await wrapper.setProps({
      snapshot: { ...restored, version: 2 as 1 },
    })
    await flushPromises()
    expect(wrapper.emitted('restore-error')?.at(-1)).toEqual([
      'Unsupported wizard snapshot version: 2',
    ])
    expect(wrapper.emitted('update:modelValue')).toHaveLength(modelUpdates ?? 0)
    expect(wrapper.emitted('update:states')).toHaveLength(stateUpdates ?? 0)
  })

  it('aborts in-flight validation on unmount without publishing an error', async () => {
    let signal!: AbortSignal
    let resolveValidation!: (valid: boolean) => void
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        validateStep: (_step: string, _context: unknown, nextSignal: AbortSignal) => {
          signal = nextSignal
          return new Promise<boolean>((resolve) => {
            resolveValidation = resolve
          })
        },
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
    resolveValidation(false)
    await flushPromises()
    expect(wrapper.emitted('validation-error')).toBeUndefined()
  })

  it('supersedes validation on snapshot restore without an old finally clearing the new transition', async () => {
    const signals: AbortSignal[] = []
    const resolvers: Array<(valid: boolean) => void> = []
    const validateStep = vi.fn((_step: string, _context: unknown, signal: AbortSignal) => {
      signals.push(signal)
      return new Promise<boolean>((resolve) => resolvers.push(resolve))
    })
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'source',
        states: createWizardStates(steps, 'source'),
        validateStep,
        'onUpdate:modelValue': (value: string) => wrapper.setProps({ modelValue: value }),
        'onUpdate:states': (value: Record<string, FyWizardStepState>) =>
          wrapper.setProps({ states: value }),
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await wrapper.setProps({
      snapshot: {
        version: 1,
        activeStep: 'mode',
        visitedPath: ['source', 'mode'],
        states: {
          source: { status: 'complete' },
          mode: { status: 'active' },
          output: { status: 'pending' },
          run: { status: 'pending' },
        },
        completed: false,
      },
    })
    await flushPromises()

    expect(signals[0].aborted).toBe(true)
    await wrapper.get('[data-wizard-next]').trigger('click')
    expect(validateStep).toHaveBeenCalledTimes(2)
    const countsAfterNewValidation = {
      model: wrapper.emitted('update:modelValue')?.length ?? 0,
      states: wrapper.emitted('update:states')?.length ?? 0,
      completed: wrapper.emitted('update:completed')?.length ?? 0,
      transition: wrapper.emitted('transition')?.length ?? 0,
      complete: wrapper.emitted('complete')?.length ?? 0,
      error: wrapper.emitted('validation-error')?.length ?? 0,
      snapshot: wrapper.emitted('snapshot')?.length ?? 0,
    }

    resolvers[0](true)
    await flushPromises()
    expect({
      model: wrapper.emitted('update:modelValue')?.length ?? 0,
      states: wrapper.emitted('update:states')?.length ?? 0,
      completed: wrapper.emitted('update:completed')?.length ?? 0,
      transition: wrapper.emitted('transition')?.length ?? 0,
      complete: wrapper.emitted('complete')?.length ?? 0,
      error: wrapper.emitted('validation-error')?.length ?? 0,
      snapshot: wrapper.emitted('snapshot')?.length ?? 0,
    }).toEqual(countsAfterNewValidation)
    expect(wrapper.get('[data-wizard-next]').attributes('disabled')).toBeDefined()

    resolvers[1](true)
    await flushPromises()
    expect(wrapper.emitted('transition')?.at(-1)).toEqual(['mode', 'output'])
  })

  it('supersedes validation on invalidation and ignores its stale rejection', async () => {
    let signal!: AbortSignal
    let rejectValidation!: (reason: unknown) => void
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        validateStep: (_step: string, _context: unknown, nextSignal: AbortSignal) => {
          signal = nextSignal
          return new Promise<boolean>((_resolve, reject) => {
            rejectValidation = reject
          })
        },
      },
      slots: {
        source: (slotProps: { actions: { invalidate: (step: string) => void } }) =>
          h('button', {
            'data-invalidate-source': '',
            onClick: () => slotProps.actions.invalidate('source'),
          }, 'Invalidate'),
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await wrapper.get('[data-invalidate-source]').trigger('click')
    expect(signal.aborted).toBe(true)
    expect(wrapper.emitted('update:states')?.at(-1)?.[0]).toMatchObject({
      source: { status: 'active' },
    })
    const countsAfterInvalidation = {
      model: wrapper.emitted('update:modelValue')?.length ?? 0,
      states: wrapper.emitted('update:states')?.length ?? 0,
      completed: wrapper.emitted('update:completed')?.length ?? 0,
      transition: wrapper.emitted('transition')?.length ?? 0,
      complete: wrapper.emitted('complete')?.length ?? 0,
      error: wrapper.emitted('validation-error')?.length ?? 0,
      snapshot: wrapper.emitted('snapshot')?.length ?? 0,
    }

    rejectValidation(new Error('stale failure'))
    await flushPromises()
    expect({
      model: wrapper.emitted('update:modelValue')?.length ?? 0,
      states: wrapper.emitted('update:states')?.length ?? 0,
      completed: wrapper.emitted('update:completed')?.length ?? 0,
      transition: wrapper.emitted('transition')?.length ?? 0,
      complete: wrapper.emitted('complete')?.length ?? 0,
      error: wrapper.emitted('validation-error')?.length ?? 0,
      snapshot: wrapper.emitted('snapshot')?.length ?? 0,
    }).toEqual(countsAfterInvalidation)
  })

  it('supersedes validation when controlled state changes externally', async () => {
    let signal!: AbortSignal
    let resolveValidation!: (valid: boolean) => void
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'source',
        states: createWizardStates(steps, 'source'),
        validateStep: (_step: string, _context: unknown, nextSignal: AbortSignal) => {
          signal = nextSignal
          return new Promise<boolean>((resolve) => {
            resolveValidation = resolve
          })
        },
      },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await wrapper.setProps({
      states: {
        ...createWizardStates(steps, 'source'),
        source: { status: 'error', error: 'Changed externally' },
      },
    })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    const updateCounts = {
      model: wrapper.emitted('update:modelValue')?.length ?? 0,
      states: wrapper.emitted('update:states')?.length ?? 0,
      completed: wrapper.emitted('update:completed')?.length ?? 0,
      transition: wrapper.emitted('transition')?.length ?? 0,
      complete: wrapper.emitted('complete')?.length ?? 0,
      error: wrapper.emitted('validation-error')?.length ?? 0,
      snapshot: wrapper.emitted('snapshot')?.length ?? 0,
    }

    resolveValidation(true)
    await flushPromises()
    expect({
      model: wrapper.emitted('update:modelValue')?.length ?? 0,
      states: wrapper.emitted('update:states')?.length ?? 0,
      completed: wrapper.emitted('update:completed')?.length ?? 0,
      transition: wrapper.emitted('transition')?.length ?? 0,
      complete: wrapper.emitted('complete')?.length ?? 0,
      error: wrapper.emitted('validation-error')?.length ?? 0,
      snapshot: wrapper.emitted('snapshot')?.length ?? 0,
    }).toEqual(updateCounts)
  })

  it('emits successful transition and completion events in canonical order', async () => {
    const transitionOrder: string[] = []
    const transitionWrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'source',
        states: createWizardStates(steps, 'source'),
        validateStep: () => true,
        'onUpdate:modelValue': () => transitionOrder.push('modelValue'),
        'onUpdate:states': (states: Record<string, FyWizardStepState>) => {
          if (states.source.status !== 'validating') transitionOrder.push('states')
        },
        'onUpdate:completed': () => transitionOrder.push('completed'),
        onTransition: () => transitionOrder.push('transition'),
        onSnapshot: () => transitionOrder.push('snapshot'),
      },
    })
    await transitionWrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()
    expect(transitionOrder).toEqual([
      'modelValue',
      'states',
      'completed',
      'transition',
      'snapshot',
    ])

    const completionOrder: string[] = []
    const completionWrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'source',
        states: createWizardStates(steps, 'source'),
        resolveNext: () => null,
        'onUpdate:states': (states: Record<string, FyWizardStepState>) => {
          if (states.source.status !== 'validating') completionOrder.push('states')
        },
        'onUpdate:completed': () => completionOrder.push('completed'),
        onComplete: () => completionOrder.push('complete'),
        onSnapshot: () => completionOrder.push('snapshot'),
      },
    })
    await completionWrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()
    expect(completionOrder).toEqual(['states', 'completed', 'complete', 'snapshot'])
  })

  it('renders every configurable label and retains the legacy back selector', async () => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        backText: 'Previous',
        nextText: 'Forward',
        finishText: 'Done',
        retryText: 'Try once more',
        optionalText: 'Maybe',
      },
    })

    expect(wrapper.get('[data-action="back"]').text()).toContain('Previous')
    expect(wrapper.get('[data-wizard-step="mode"]').text()).toContain('(Maybe)')
    expect(wrapper.get('[data-wizard-next]').text()).toContain('Forward')

    await wrapper.setProps({
      modelValue: 'run',
      states: createWizardStates(steps, 'run'),
    })
    expect(wrapper.get('[data-wizard-next]').text()).toContain('Done')

    await wrapper.setProps({
      modelValue: 'source',
      states: {
        ...createWizardStates(steps, 'source'),
        source: { status: 'error', error: 'Try again' },
      },
    })
    expect(wrapper.get('[data-wizard-next]').text()).toContain('Try once more')
  })

  it('overrides every generic progress, status, history, and live-region label', async () => {
    const snapshot: FyWizardSnapshot = {
      version: 1,
      activeStep: 'mode',
      visitedPath: ['source', 'mode'],
      states: {
        source: { status: 'complete' },
        mode: { status: 'error', error: 'Bad mapping' },
        output: { status: 'pending' },
        run: { status: 'pending' },
      },
      completed: false,
    }
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'mode',
        states: snapshot.states,
        snapshot,
        labels: {
          status: {
            pending: 'WAIT',
            active: 'HERE',
            validating: 'CHECK',
            complete: 'DONE',
            error: 'BROKEN',
            skipped: 'BYPASSED',
          },
          progress: 'Localized progress',
          step: (index: number, total: number) => `Stage ${index} of ${total}`,
          compactProgress: (index: number, total: number) => `Phase ${index}/${total}`,
          errorHistory: 'Localized problem stages',
          errorStep: (title: string, status: string) => `${title} => ${status}`,
          currentStatus: (title: string, status: string) => `${title} now ${status}`,
          showVisitedPath: 'Show localized history',
          hideVisitedPath: 'Hide localized history',
        },
      },
    })
    await wrapper.setProps({ states: { ...snapshot.states } })
    await flushPromises()

    expect(wrapper.get('[data-wizard-progress]').attributes('aria-label')).toBe('Localized progress')
    expect(wrapper.get('[data-wizard-step="source"]').text()).toContain('Stage 1 of 4')
    expect(wrapper.get('[data-wizard-step="source"]').text()).toContain('DONE')
    expect(wrapper.get('[data-wizard-current]').text()).toContain('Phase 2/4')
    expect(wrapper.get('[data-wizard-errors]').attributes('aria-label')).toBe(
      'Localized problem stages',
    )
    expect(wrapper.get('[data-wizard-errors]').text()).toContain('Mode => BROKEN')
    expect(wrapper.get('[data-wizard-live]').text()).toBe('Mode now BROKEN')
    expect(wrapper.get('[data-wizard-history] summary').text()).toBe('Show localized history')

    wrapper.get('[data-wizard-history]').element.setAttribute('open', '')
    await wrapper.get('[data-wizard-history]').trigger('toggle')
    expect(wrapper.get('[data-wizard-history] summary').text()).toBe('Hide localized history')
  })

  it('provides typed step-label, error, and actions slots with working actions', async () => {
    const snapshot: FyWizardSnapshot = {
      version: 1,
      activeStep: 'mode',
      visitedPath: ['source', 'mode'],
      states: {
        source: { status: 'complete' },
        mode: { status: 'error', error: 'Bad mapping' },
        output: { status: 'pending' },
        run: { status: 'pending' },
      },
      completed: false,
    }
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'mode',
        states: snapshot.states,
        snapshot,
        validateStep: () => true,
        resolveNext: () => 'output',
      },
      slots: {
        'step-label': ({ step, statusLabel }: { step: FyWizardStep; statusLabel: string }) =>
          h('span', { 'data-custom-step-label': step.value }, `${step.title}/${statusLabel}`),
        error: ({ message, actions }: { message?: string; actions: { next: () => Promise<void> } }) =>
          h('button', { 'data-custom-error': '', onClick: actions.next }, `Repair ${message}`),
        actions: ({ canBack, nextLabel }: { canBack: boolean; nextLabel: string }) =>
          h('div', { 'data-custom-actions': '' }, `${canBack ? 'can-back' : 'no-back'}:${nextLabel}`),
      },
    })
    await wrapper.setProps({ states: { ...snapshot.states } })
    await flushPromises()

    expect(wrapper.findAll('[data-custom-step-label]')).toHaveLength(steps.length)
    expect(wrapper.get('[data-custom-step-label="mode"]').text()).toBe('Mode/Error')
    expect(wrapper.find('[data-wizard-error]').exists()).toBe(false)
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(1)
    expect(wrapper.get('[data-custom-error]').text()).toBe('Repair Bad mapping')
    expect(wrapper.get('[data-custom-actions]').text()).toBe('can-back:Retry')
    expect(wrapper.find('[data-wizard-next]').exists()).toBe(false)

    await wrapper.get('[data-custom-error]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('transition')?.at(-1)).toEqual(['mode', 'output'])
  })

  it('treats externally controlled validating state as busy and announces it persistently', () => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: {
        steps,
        modelValue: 'mode',
        states: {
          source: { status: 'complete' },
          mode: { status: 'validating' },
          output: { status: 'pending' },
          run: { status: 'pending' },
        },
      },
    })

    expect(wrapper.get('[data-wizard]').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('[data-wizard-live]').attributes('role')).toBe('status')
    expect(wrapper.get('[data-wizard-live]').attributes('aria-live')).toBe('polite')
    expect(wrapper.get('[data-wizard-live]').text()).toContain('Validating')
    expect(wrapper.findAll('[role="status"]')).toHaveLength(1)
    expect(wrapper.get('[data-wizard-current]').attributes('aria-live')).toBeUndefined()
    expect(wrapper.get('[data-wizard-next]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-wizard-back]').attributes('disabled')).toBeDefined()
  })

  it('retains uncontrolled canContinue and blocked compatibility', async () => {
    const canContinue = vi.fn<(_from: string, _to: string) => boolean | Promise<boolean>>()
      .mockReturnValueOnce(false)
      .mockRejectedValueOnce(new Error('legacy failure'))
      .mockReturnValueOnce(true)
    const wrapper = mount(FyStepWizard, { global, props: { steps, canContinue } })

    await wrapper.get('[data-action="next"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('blocked')?.at(-1)).toEqual([])

    await wrapper.get('[data-action="next"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('blocked')?.at(-1)?.[0]).toBeInstanceOf(Error)

    await wrapper.get('[data-action="next"]').trigger('click')
    await flushPromises()
    expect(canContinue).toHaveBeenLastCalledWith('source', 'mode')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['mode'])
  })

  it('emits legacy uncontrolled final completion immediately without canContinue(from, from)', async () => {
    const canContinue = vi.fn(() => false)
    const wrapper = mount(FyStepWizard, {
      global,
      props: { steps: [steps[0]], canContinue },
    })

    const click = wrapper.get('[data-wizard-next]').trigger('click')
    expect(wrapper.emitted('complete')).toEqual([[]])
    expect(canContinue).not.toHaveBeenCalled()
    await click
  })

  it('emits model-only legacy final completion immediately and only once', async () => {
    const canContinue = vi.fn(() => false)
    const wrapper = mount(FyStepWizard, {
      global,
      props: { steps, modelValue: 'run', canContinue },
    })

    const firstClick = wrapper.get('[data-wizard-next]').trigger('click')
    expect(wrapper.emitted('complete')).toEqual([[]])
    expect(canContinue).not.toHaveBeenCalled()
    await firstClick

    await wrapper.get('[data-wizard-next]').trigger('click')
    expect(wrapper.emitted('complete')).toHaveLength(1)
    expect(canContinue).not.toHaveBeenCalled()
  })

  it('completes an uncontrolled wizard only once', async () => {
    const wrapper = mount(FyStepWizard, {
      global,
      props: { steps: [steps[0]], resolveNext: () => null },
    })

    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-wizard-next]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('complete')).toHaveLength(1)
  })
})

describe('FyConfirmDialog', () => {
  // v-dialog teleports its content to document.body, so the buttons are
  // queried from the live document (attachTo: document.body mounts the root
  // there) rather than from the wrapper's tree.
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('uses its own focus restoration without Vuetify retain-focus', () => {
    const wrapper = mount(FyConfirmDialog, {
      global,
      props: { modelValue: false, title: 'Continue?' },
    })
    const dialog = wrapper.findComponent({ name: 'VDialog' })
    expect(dialog.props('retainFocus')).toBe(false)
    expect(dialog.props('captureFocus')).toBe(false)
  })

  it('requires an explicit confirm action', async () => {
    const wrapper = mount(FyConfirmDialog, {
      global,
      attachTo: document.body,
      props: { modelValue: true, title: 'Delete task?', destructive: true },
    })
    await wrapper.vm.$nextTick()
    const confirmButton = document.body.querySelector<HTMLButtonElement>('[data-action="confirm"]')!
    confirmButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('emits cancel from the cancel action', async () => {
    const wrapper = mount(FyConfirmDialog, {
      global,
      attachTo: document.body,
      props: { modelValue: true, title: 'Continue?' },
    })
    await wrapper.vm.$nextTick()
    const cancelButton = document.body.querySelector<HTMLButtonElement>('[data-action="cancel"]')!
    cancelButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('closes on Esc and returns focus to the opener', async () => {
    // Real opener button in the document, focused before the dialog opens, so
    // the component has an element to remember and restore focus to.
    const opener = document.createElement('button')
    opener.textContent = 'Open dialog'
    document.body.appendChild(opener)
    opener.focus()
    expect(document.activeElement).toBe(opener)

    const wrapper = mount(FyConfirmDialog, {
      global,
      attachTo: document.body,
      props: { modelValue: false, title: 'Delete task?' },
    })
    await wrapper.vm.$nextTick()

    // Open the dialog: the watcher captures the focused opener.
    await wrapper.setProps({ modelValue: true })
    await wrapper.vm.$nextTick()
    // jsdom does NOT move focus into the dialog on open the way a real browser
    // (and Vuetify's focus trap) does. Simulate that focus-steal here so the
    // restore assertion below is meaningful rather than trivially true.
    const confirmButton = document.body.querySelector<HTMLElement>('[data-action="confirm"]')!
    confirmButton.focus()
    expect(document.activeElement).toBe(confirmButton)

    // Esc must emit `cancel` and close. Dispatched onto the focused element
    // (which lives inside the dialog) the way a real browser delivers a
    // keystroke — it bubbles up to the dialog's `@keydown.esc` handler exactly
    // once. Dispatching on `window` does not reach jsdom's element-level
    // listener, and dispatching on the (unfocused) `.v-card` bubbles through
    // two overlay elements and double-fires the handler.
    document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    // The component closes itself by emitting update:modelValue:false.
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])

    // Reflect the close through the prop (the host would do this), then assert
    // focus was restored to the opener.
    await wrapper.setProps({ modelValue: false })
    await wrapper.vm.$nextTick()
    expect(document.activeElement).toBe(opener)

    opener.remove()
  })

  it('returns focus to the opener when closed via the cancel button', async () => {
    // jsdom's KeyboardEvent dispatch onto a teleported v-dialog card can be
    // flaky; this test pins the focus-restore contract through the cancel
    // button path, which is fully under the test's control.
    const opener = document.createElement('button')
    opener.textContent = 'Open dialog'
    document.body.appendChild(opener)
    opener.focus()
    expect(document.activeElement).toBe(opener)

    const wrapper = mount(FyConfirmDialog, {
      global,
      attachTo: document.body,
      props: { modelValue: false, title: 'Delete task?' },
    })
    await wrapper.vm.$nextTick()

    // Open: watcher remembers the opener.
    await wrapper.setProps({ modelValue: true })
    await wrapper.vm.$nextTick()
    // Simulate the browser/Vuetify focus-steal that jsdom does not perform, so
    // the restore assertion exercises the component's focus-return code rather
    // than passing trivially because the opener never lost focus.
    const confirmButton = document.body.querySelector<HTMLElement>('[data-action="confirm"]')!
    confirmButton.focus()
    expect(document.activeElement).toBe(confirmButton)

    // Close via the cancel button.
    const cancelButton = document.body.querySelector<HTMLButtonElement>('[data-action="cancel"]')!
    cancelButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await wrapper.vm.$nextTick()
    // Host honors the emitted close.
    await wrapper.setProps({ modelValue: false })
    await wrapper.vm.$nextTick()

    expect(document.activeElement).toBe(opener)

    opener.remove()
  })
})

describe('FyTaskTable', () => {
  const tasks: FyTaskRow[] = [
    { id: '1', name: 'sales-2026.xlsx', status: 'success' },
    { id: '2', name: 'contacts.xlsx', status: 'running', detail: '42%' },
  ]

  it('renders a row per task with the name and a status label', () => {
    const wrapper = mount(FyTaskTable, { global, props: { tasks } })
    const text = wrapper.text()
    expect(text).toContain('sales-2026.xlsx')
    expect(text).toContain('contacts.xlsx')
    // Status is conveyed as readable text (not color alone).
    expect(text).toContain('Success')
    expect(text).toContain('Running')
  })

  it('renders an empty state when there are no tasks', () => {
    const wrapper = mount(FyTaskTable, { global, props: { tasks: [] } })
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
  })
})
