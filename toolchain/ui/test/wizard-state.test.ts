import { describe, expect, it } from 'vitest'
import {
  buildWizardSnapshot,
  createWizardStates,
  guardWizardStepDefinitions,
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
  it.each([
    {
      name: 'an empty step list',
      invalidSteps: [] as FyWizardStep[],
      error: 'Wizard requires at least one step',
    },
    {
      name: 'an empty step value',
      invalidSteps: [{ value: '', title: 'Source' }],
      error: 'Wizard step value must be a non-empty string at index 0',
    },
    {
      name: 'an empty step title',
      invalidSteps: [{ value: 'source', title: '' }],
      error: 'Wizard step title must be a non-empty string: source',
    },
    {
      name: 'duplicate step values',
      invalidSteps: [
        { value: 'source', title: 'First source' },
        { value: 'source', title: 'Second source' },
      ],
      error: 'Wizard step values must be unique: source',
    },
  ])('guards $name differently in development and production', ({ invalidSteps, error }) => {
    expect(() => guardWizardStepDefinitions(invalidSteps, true)).toThrow(error)
    expect(guardWizardStepDefinitions(invalidSteps, false)).toBe(error)
  })

  it('rejects duplicate step values instead of silently collapsing their states', () => {
    const duplicates: FyWizardStep[] = [
      { value: 'source', title: 'First source' },
      { value: 'source', title: 'Second source' },
    ]

    expect(() => createWizardStates(duplicates, 'source')).toThrow(
      'Wizard step values must be unique: source',
    )
    expect(normalizeWizardSnapshot(duplicates, {
      version: 1,
      activeStep: 'source',
      visitedPath: ['source'],
      states: { source: { status: 'active' } },
      completed: false,
    })).toEqual({ error: 'Wizard step values must be unique: source' })
  })

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
