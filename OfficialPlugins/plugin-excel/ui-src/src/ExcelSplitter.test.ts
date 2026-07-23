import {
  flushPromises,
  mount,
  type VueWrapper,
} from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import { createFengYuVuetify, FENGYU_CLIENT_KEY } from '@infinia/plugin-ui'
import ExcelSplitter from './ExcelSplitter.vue'
import {
  EXCEL_WIZARD_STORAGE_KEY,
  loadExcelWizardRecord,
  saveExcelWizardRecord,
  type ExcelWizardRecord,
} from './excelWizardState'

const sourceRef: FileRef = {
  id: 'source-grant',
  name: 'sales.xlsx',
  kind: 'file',
  access: 'read',
  size: 1024,
}

const outputRef: FileRef = {
  id: 'output-grant',
  name: 'exports',
  kind: 'directory',
  access: 'write',
  size: 0,
}

const replacementSourceRef: FileRef = {
  id: 'replacement-source-grant',
  name: 'replacement.xlsx',
  kind: 'file',
  access: 'read',
  size: 2048,
}

const analyzeSuccess = {
  success: true,
  sheets: { Sales: { A: 'Region', B: 'Amount' } },
}

type FakeClientOverrides = Omit<Partial<FengYuClient>, 'files'> & {
  files?: Partial<FengYuClient['files']>
}

function fakeClient(overrides: FakeClientOverrides = {}): FengYuClient {
  const { files, ...rest } = overrides
  return {
    ready: vi.fn().mockResolvedValue({ theme: 'light', locale: 'en', platform: 'web' }),
    on: vi.fn().mockReturnValue(() => {}),
    notify: vi.fn().mockResolvedValue(true),
    files: {
      open: vi.fn().mockResolvedValue(sourceRef),
      inputDirectory: vi.fn(),
      workspaceDirectory: vi.fn(),
      outputDirectory: vi.fn().mockResolvedValue(outputRef),
      export: vi.fn().mockResolvedValue(true),
      ...files,
    },
    invoke: vi.fn().mockImplementation((method: string, params: Record<string, unknown>) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'estimate') return Promise.resolve({ success: true, fileCount: 2, exact: true })
      if (method === 'configure') {
        if (
          params.mode === 'BY_SHEET'
          && Object.hasOwn(params, 'selectedSheets')
          && Array.isArray(params.selectedSheets)
          && params.selectedSheets.length === 0
        ) {
          return Promise.resolve({ success: false, summary: 'Select at least one sheet' })
        }
        return Promise.resolve({ success: true, summary: `configured mode=${params.mode}` })
      }
      if (method === 'split') {
        return Promise.resolve({
          success: true,
          summary: 'wrote 2 file(s)',
          fileCount: 2,
          files: ['north.xlsx', 'south.xlsx'],
        })
      }
      return Promise.reject(new Error(`Unexpected method: ${method}`))
    }),
    dispose: vi.fn(),
    request: vi.fn(),
    ...rest,
  } as unknown as FengYuClient
}

function mountSplitter(client: FengYuClient): VueWrapper {
  return mount(ExcelSplitter, {
    global: {
      plugins: [createFengYuVuetify({ theme: 'light', locale: 'en' })],
      provide: { [FENGYU_CLIENT_KEY]: client },
    },
  })
}

function step(wrapper: VueWrapper, value: string) {
  return wrapper.get(`[data-wizard-step="${value}"]`)
}

async function chooseSource(wrapper: VueWrapper): Promise<void> {
  await wrapper.get('[data-action="pick-file"]').trigger('click')
  await flushPromises()
}

async function next(wrapper: VueWrapper): Promise<void> {
  await wrapper.get('[data-action="next"]').trigger('click')
  await flushPromises()
}

async function chooseOutput(wrapper: VueWrapper): Promise<void> {
  await wrapper.get('[data-action="pick-directory"]').trigger('click')
  await flushPromises()
}

async function completeRun(wrapper: VueWrapper): Promise<void> {
  await chooseSource(wrapper)
  await next(wrapper)
  await chooseOutput(wrapper)
  await next(wrapper)
  await next(wrapper)
}

async function chooseMode(wrapper: VueWrapper, value: 'BY_COLUMN' | 'COMPLEX'): Promise<void> {
  await wrapper.get(`[data-mode="${value}"]`).trigger('click')
  await flushPromises()
}

async function updateVuetifyField(
  wrapper: VueWrapper,
  componentName: 'VSelect' | 'VTextField',
  label: string,
  value: unknown,
): Promise<void> {
  const component = wrapper.findAllComponents({ name: componentName })
    .find((candidate) => candidate.props('label') === label)
  expect(component, `${componentName} with label ${label}`).toBeDefined()
  component!.vm.$emit('update:modelValue', value)
  await flushPromises()
}

function inaccessibleStorage(): Storage {
  const unavailable = () => {
    throw new DOMException('Storage unavailable', 'SecurityError')
  }
  return {
    length: 0,
    clear: unavailable,
    getItem: unavailable,
    key: () => null,
    removeItem: unavailable,
    setItem: unavailable,
  }
}

function storedRecord(activeStep: 'source' | 'mode' | 'output' | 'run', completed = false): ExcelWizardRecord {
  const visitedPath = ['source', 'mode', 'output', 'run']
  const activeIndex = visitedPath.indexOf(activeStep)
  return {
    version: 1,
    wizard: {
      version: 1,
      activeStep,
      visitedPath: visitedPath.slice(0, activeIndex + 1),
      states: {
        source: { status: 'complete' },
        mode: { status: activeIndex > 1 ? 'complete' : activeStep === 'mode' ? 'active' : 'pending' },
        output: { status: activeIndex > 2 ? 'complete' : activeStep === 'output' ? 'active' : 'pending' },
        run: { status: activeStep === 'run' ? (completed ? 'complete' : 'active') : 'pending' },
      },
      completed,
    },
    draft: {
      sourceFileRef: sourceRef,
      sessionId: 'restored-session',
      mode: 'BY_COLUMN',
      selectedSheets: ['Sales'],
      splitSheet: 'Sales',
      splitColumn: 'Region',
      filePrefix: 'restored-',
      complexEntries: [],
    },
  }
}

describe('ExcelSplitter stateful wizard', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.stubGlobal('crypto', { randomUUID: vi.fn(() => 'new-session') })
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('validates a selected source once and advances only after analyze succeeds', async () => {
    let resolveAnalyze!: (value: unknown) => void
    const analyze = new Promise((resolve) => { resolveAnalyze = resolve })
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return analyze
      return Promise.resolve({ success: true })
    })
    const wrapper = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))

    await wrapper.get('[data-action="pick-file"]').trigger('click')
    await flushPromises()

    expect(step(wrapper, 'source').attributes('data-status')).toBe('validating')
    expect(step(wrapper, 'source').attributes('aria-current')).toBe('step')
    expect(invoke).toHaveBeenCalledTimes(1)
    expect(invoke).toHaveBeenCalledWith(
      'analyze',
      { session: 'new-session', sourceFile: sourceRef },
      { signal: expect.any(AbortSignal) },
    )

    resolveAnalyze(analyzeSuccess)
    await flushPromises()

    expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
    expect(step(wrapper, 'source').attributes('data-status')).toBe('complete')
  })

  it('renders an analyze summary failure on Source and retries exactly once', async () => {
    const invoke = vi.fn()
      .mockResolvedValueOnce({ success: false, summary: 'Workbook grant expired' })
      .mockResolvedValueOnce(analyzeSuccess)
    const wrapper = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))

    await chooseSource(wrapper)

    expect(step(wrapper, 'source').attributes('aria-current')).toBe('step')
    expect(wrapper.get('[data-wizard-error]').text()).toContain('Workbook grant expired')
    expect(wrapper.findAll('[role="alert"]')
      .filter((alert) => alert.text().includes('Workbook grant expired'))).toHaveLength(1)
    expect(wrapper.get('[data-action="next"]').text()).toContain('Retry')

    await next(wrapper)

    expect(invoke).toHaveBeenCalledTimes(2)
    expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
  })

  it.each([
    ['a fractional header index', 0, 1.9, 'Use whole-number indices of 1 or greater'],
    ['a zero column index', 1, 0, 'Use whole-number indices of 1 or greater'],
    ['a negative header index', 0, -2, 'Use whole-number indices of 1 or greater'],
  ])(
    'rejects COMPLEX rules with %s without configuring',
    async (_case, fieldIndex, value, message) => {
      const client = fakeClient()
      const invoke = client.invoke as ReturnType<typeof vi.fn>
      const wrapper = mountSplitter(client)
      await chooseSource(wrapper)
      await chooseMode(wrapper, 'COMPLEX')
      await wrapper.findAll('button')
        .find((button) => button.text().includes('Add rule'))!
        .trigger('click')
      const indexFields = wrapper.findAllComponents({ name: 'VTextField' })
        .filter((field) => field.props('type') === 'number')
      indexFields[fieldIndex].vm.$emit('update:modelValue', value)
      await flushPromises()

      await next(wrapper)

      expect(invoke.mock.calls.filter(([method]) => method === 'configure')).toHaveLength(0)
      expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
      expect(step(wrapper, 'mode').attributes('data-status')).toBe('error')
      expect(wrapper.get('[data-wizard-error]').text()).toContain(message)
    },
  )

  it('rejects a copy-all COMPLEX rule unless both indices are exactly -1', async () => {
    const client = fakeClient()
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    const wrapper = mountSplitter(client)
    await chooseSource(wrapper)
    await chooseMode(wrapper, 'COMPLEX')
    await wrapper.findAll('button')
      .find((button) => button.text().includes('Add rule'))!
      .trigger('click')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    const headerIndex = wrapper.findAllComponents({ name: 'VTextField' })
      .filter((field) => field.props('type') === 'number')[0]
    headerIndex.vm.$emit('update:modelValue', 1)
    await flushPromises()

    await next(wrapper)

    expect(invoke.mock.calls.filter(([method]) => method === 'configure')).toHaveLength(0)
    expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
    expect(step(wrapper, 'mode').attributes('data-status')).toBe('error')
    expect(wrapper.get('[data-wizard-error]').text()).toContain(
      'Copy-all rules require both indices to be -1',
    )
  })

  it('keeps valid COMPLEX indices numeric when edited through number inputs', async () => {
    const client = fakeClient()
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    const wrapper = mountSplitter(client)
    await chooseSource(wrapper)
    await chooseMode(wrapper, 'COMPLEX')
    await wrapper.findAll('button')
      .find((button) => button.text().includes('Add rule'))!
      .trigger('click')
    const indexInputs = wrapper.findAll('input[type="number"]')
    await indexInputs[0].setValue('2')
    await indexInputs[1].setValue('3')
    await flushPromises()

    await next(wrapper)

    expect(invoke).toHaveBeenCalledWith(
      'configure',
      {
        session: 'new-session',
        mode: 'COMPLEX',
        filePrefix: '',
        complexEntries: [{
          fieldName: '',
          sheetName: 'Sales',
          headerIndex: 2,
          columnIndex: 3,
        }],
      },
      { signal: expect.any(AbortSignal) },
    )
    expect(step(wrapper, 'output').attributes('aria-current')).toBe('step')
  })

  it.each([
    ['sheet', 'Archive', 'Region', 'Choose a sheet from the analyzed workbook'],
    ['column', 'Sales', 'Legacy', 'Choose a column from the analyzed sheet'],
  ])(
    'rejects an unavailable BY_COLUMN %s during ordinary Mode validation',
    async (_case, selectedSheet, selectedColumn, message) => {
      const client = fakeClient()
      const invoke = client.invoke as ReturnType<typeof vi.fn>
      const wrapper = mountSplitter(client)
      await chooseSource(wrapper)
      await chooseMode(wrapper, 'BY_COLUMN')
      await updateVuetifyField(wrapper, 'VSelect', 'Sheet', selectedSheet)
      await updateVuetifyField(wrapper, 'VSelect', 'Column', selectedColumn)

      await next(wrapper)

      expect(invoke.mock.calls.filter(([method]) => method === 'configure')).toHaveLength(0)
      expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
      expect(wrapper.get('[data-wizard-error]').text()).toContain(message)
    },
  )

  it.each([
    {
      name: 'BY_SHEET with an empty selection',
      prepare: async (_wrapper: VueWrapper) => {},
      expected: { session: 'new-session', mode: 'BY_SHEET', filePrefix: '' },
    },
    {
      name: 'BY_SHEET with selected sheets',
      prepare: async (wrapper: VueWrapper) => {
        await updateVuetifyField(wrapper, 'VSelect', 'Sheets (leave empty for all)', ['Sales'])
        await updateVuetifyField(wrapper, 'VTextField', 'Output file prefix (optional)', 'q3-')
      },
      expected: {
        session: 'new-session',
        mode: 'BY_SHEET',
        filePrefix: 'q3-',
        selectedSheets: ['Sales'],
      },
    },
    {
      name: 'BY_COLUMN',
      prepare: async (wrapper: VueWrapper) => {
        await chooseMode(wrapper, 'BY_COLUMN')
        await updateVuetifyField(wrapper, 'VSelect', 'Sheet', 'Sales')
        await updateVuetifyField(wrapper, 'VSelect', 'Column', 'Region')
      },
      expected: {
        session: 'new-session',
        mode: 'BY_COLUMN',
        filePrefix: '',
        splitSheet: 'Sales',
        splitColumn: 'Region',
      },
    },
    {
      name: 'COMPLEX copy-all',
      prepare: async (wrapper: VueWrapper) => {
        await chooseMode(wrapper, 'COMPLEX')
        await wrapper.findAll('button')
          .find((button) => button.text().includes('Add rule'))!
          .trigger('click')
        await wrapper.get('input[type="checkbox"]').setValue(true)
        await flushPromises()
      },
      expected: {
        session: 'new-session',
        mode: 'COMPLEX',
        filePrefix: '',
        complexEntries: [{
          fieldName: '',
          sheetName: 'Sales',
          headerIndex: -1,
          columnIndex: -1,
        }],
      },
    },
  ])('completes Source to Run with the worker payload for $name', async ({ prepare, expected }) => {
    const client = fakeClient()
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    const wrapper = mountSplitter(client)

    await chooseSource(wrapper)
    await prepare(wrapper)
    await next(wrapper)
    expect(invoke).toHaveBeenCalledWith(
      'configure',
      expected,
      { signal: expect.any(AbortSignal) },
    )

    await chooseOutput(wrapper)
    await next(wrapper)
    await next(wrapper)

    expect(wrapper.text()).toContain('2 file(s) written')
    expect(invoke.mock.calls.filter(([method]) => method === 'split')).toHaveLength(1)
  })

  it('sends an empty filePrefix to clear a prior value in the same worker session', async () => {
    const client = fakeClient()
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    const wrapper = mountSplitter(client)

    await chooseSource(wrapper)
    await updateVuetifyField(wrapper, 'VTextField', 'Output file prefix (optional)', 'q3-')
    await next(wrapper)

    await step(wrapper, 'mode').trigger('click')
    await updateVuetifyField(wrapper, 'VTextField', 'Output file prefix (optional)', '')
    await next(wrapper)

    expect(invoke.mock.calls
      .filter(([method]) => method === 'configure')
      .map(([, args]) => args))
      .toEqual([
        { session: 'new-session', mode: 'BY_SHEET', filePrefix: 'q3-' },
        { session: 'new-session', mode: 'BY_SHEET', filePrefix: '' },
      ])
  })

  it('renders and retries a configure summary failure on Mode', async () => {
    let configureAttempts = 0
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'configure') {
        configureAttempts += 1
        return Promise.resolve(configureAttempts === 1
          ? { success: false, summary: 'Selected sheet does not exist' }
          : { success: true, summary: 'configured mode=BY_SHEET' })
      }
      return Promise.resolve({ success: true })
    })
    const wrapper = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))

    await chooseSource(wrapper)
    await next(wrapper)

    expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
    expect(wrapper.get('[data-wizard-error]').text()).toContain('Selected sheet does not exist')
    expect(wrapper.findAll('[role="alert"]')
      .filter((alert) => alert.text().includes('Selected sheet does not exist'))).toHaveLength(1)
    expect(wrapper.get('[data-action="next"]').text()).toContain('Retry')

    await next(wrapper)
    expect(configureAttempts).toBe(2)
    expect(step(wrapper, 'output').attributes('aria-current')).toBe('step')
  })

  it('renders and retries a split summary failure without replacing the output grant', async () => {
    let splitAttempts = 0
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'configure') return Promise.resolve({ success: true })
      if (method === 'split') {
        splitAttempts += 1
        return Promise.resolve(splitAttempts === 1
          ? { success: false, summary: 'Output directory is full' }
          : { success: true, fileCount: 1, files: ['sales.xlsx'] })
      }
      return Promise.reject(new Error(`Unexpected method: ${method}`))
    })
    const client = fakeClient({ invoke: invoke as FengYuClient['invoke'] })
    const wrapper = mountSplitter(client)

    await chooseSource(wrapper)
    await next(wrapper)
    await chooseOutput(wrapper)
    await next(wrapper)
    await next(wrapper)

    expect(step(wrapper, 'run').attributes('aria-current')).toBe('step')
    expect(wrapper.get('[data-wizard-error]').text()).toContain('Output directory is full')
    expect(wrapper.findAll('[role="alert"]')
      .filter((alert) => alert.text().includes('Output directory is full'))).toHaveLength(1)
    expect(wrapper.get('[data-action="next"]').text()).toContain('Retry')

    await next(wrapper)
    expect(splitAttempts).toBe(2)
    expect(client.files.outputDirectory).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('1 file(s) written')
  })

  it('clears a stale configure error when a replacement source invalidates Mode', async () => {
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'configure') {
        return Promise.resolve({ success: false, summary: 'Old configure failure' })
      }
      return Promise.reject(new Error(`Unexpected method: ${method}`))
    })
    const wrapper = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
    await chooseSource(wrapper)
    await next(wrapper)
    expect(wrapper.text()).toContain('Old configure failure')

    await step(wrapper, 'source').trigger('click')
    await chooseSource(wrapper)

    expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
    expect(wrapper.text()).not.toContain('Old configure failure')
  })

  it('clears a stale split error when a replacement output invalidates Run', async () => {
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'configure') return Promise.resolve({ success: true })
      if (method === 'split') {
        return Promise.resolve({ success: false, summary: 'Old split failure' })
      }
      return Promise.reject(new Error(`Unexpected method: ${method}`))
    })
    const wrapper = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
    await chooseSource(wrapper)
    await next(wrapper)
    await chooseOutput(wrapper)
    await next(wrapper)
    await next(wrapper)
    expect(wrapper.text()).toContain('Old split failure')

    await step(wrapper, 'output').trigger('click')
    await chooseOutput(wrapper)
    await next(wrapper)

    expect(step(wrapper, 'run').attributes('aria-current')).toBe('step')
    expect(wrapper.text()).not.toContain('Old split failure')
  })

  it('continues without persistence and reports an unavailable Storage once', async () => {
    vi.stubGlobal('sessionStorage', inaccessibleStorage())
    const client = fakeClient()
    const wrapper = mountSplitter(client)
    await flushPromises()

    await chooseSource(wrapper)

    expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
    expect(client.notify).toHaveBeenCalledTimes(1)
    expect(client.notify).toHaveBeenCalledWith('Unable to save wizard progress')
  })

  it('invalidates Output and Run, clears the result, and saves incomplete progress when mode changes', async () => {
    const client = fakeClient()
    const wrapper = mountSplitter(client)
    await completeRun(wrapper)
    expect(wrapper.text()).toContain('2 file(s) written')

    await step(wrapper, 'mode').trigger('click')
    await wrapper.get('[data-mode="BY_COLUMN"]').trigger('click')
    await flushPromises()

    expect(step(wrapper, 'output').attributes('data-status')).toBe('pending')
    expect(step(wrapper, 'run').attributes('data-status')).toBe('pending')
    expect(loadExcelWizardRecord(sessionStorage)).toMatchObject({
      wizard: { completed: false },
      draft: { mode: 'BY_COLUMN' },
    })

    await step(wrapper, 'run').trigger('click')
    expect(wrapper.text()).not.toContain('2 file(s) written')
  })

  it('locks historical future steps after mode changes and requires forward revalidation before another split', async () => {
    const client = fakeClient()
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    const wrapper = mountSplitter(client)
    await completeRun(wrapper)
    expect(invoke.mock.calls.filter(([method]) => method === 'split')).toHaveLength(1)

    await step(wrapper, 'mode').trigger('click')
    await wrapper.get('[data-mode="COMPLEX"]').trigger('click')
    await wrapper.findAll('button').find((button) => button.text().includes('Add rule'))!.trigger('click')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await flushPromises()

    expect(step(wrapper, 'output').attributes('disabled')).toBeDefined()
    expect(step(wrapper, 'run').attributes('disabled')).toBeDefined()
    await step(wrapper, 'run').trigger('click')
    await flushPromises()
    expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
    expect(invoke.mock.calls.filter(([method]) => method === 'split')).toHaveLength(1)

    await next(wrapper)
    expect(invoke).toHaveBeenCalledWith(
      'configure',
      {
        session: 'new-session',
        mode: 'COMPLEX',
        filePrefix: '',
        complexEntries: [{
          fieldName: '',
          sheetName: 'Sales',
          headerIndex: -1,
          columnIndex: -1,
        }],
      },
      { signal: expect.any(AbortSignal) },
    )
    expect(step(wrapper, 'output').attributes('aria-current')).toBe('step')
    expect(step(wrapper, 'run').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('Output: exports')

    await chooseOutput(wrapper)
    await next(wrapper)
    await next(wrapper)
    expect(invoke.mock.calls.filter(([method]) => method === 'split')).toHaveLength(2)
  })

  it('requires an output selection before entering Run', async () => {
    const wrapper = mountSplitter(fakeClient())
    await chooseSource(wrapper)
    await next(wrapper)

    expect(step(wrapper, 'output').attributes('aria-current')).toBe('step')
    await next(wrapper)

    expect(step(wrapper, 'output').attributes('aria-current')).toBe('step')
    expect(wrapper.get('[data-wizard-error]').text()).toContain('Choose an output folder')
  })

  it('single-flights duplicate Run actions, shows completion, exports explicitly, and can navigate back', async () => {
    let resolveSplit!: (value: unknown) => void
    const split = new Promise((resolve) => { resolveSplit = resolve })
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'configure') return Promise.resolve({ success: true })
      if (method === 'split') return split
      return Promise.reject(new Error(`Unexpected method: ${method}`))
    })
    const client = fakeClient({ invoke: invoke as FengYuClient['invoke'] })
    const wrapper = mountSplitter(client)
    await chooseSource(wrapper)
    await next(wrapper)
    await chooseOutput(wrapper)
    await next(wrapper)

    const finish = wrapper.get('[data-action="next"]')
    await Promise.all([finish.trigger('click'), finish.trigger('click')])
    await flushPromises()

    expect(invoke).toHaveBeenCalledWith(
      'configure',
      { session: 'new-session', mode: 'BY_SHEET', filePrefix: '' },
      { signal: expect.any(AbortSignal) },
    )
    expect(invoke.mock.calls.filter(([method]) => method === 'split')).toHaveLength(1)
    expect(invoke).toHaveBeenCalledWith(
      'split',
      { session: 'new-session', sourceFile: sourceRef, outputDir: outputRef },
      { signal: expect.any(AbortSignal) },
    )
    resolveSplit({ success: true, fileCount: 2, files: ['north.xlsx', 'south.xlsx'] })
    await flushPromises()

    expect(wrapper.text()).toContain('2 file(s) written')
    expect(client.files.export).not.toHaveBeenCalled()
    await wrapper.get('[data-action="export-results"]').trigger('click')
    await flushPromises()
    expect(client.files.export).toHaveBeenCalledWith(outputRef)

    await wrapper.get('[data-action="back"]').trigger('click')
    await flushPromises()
    expect(step(wrapper, 'output').attributes('aria-current')).toBe('step')
  })

  it('hides the Download button on desktop because files are written in place', async () => {
    const client = fakeClient({ ready: vi.fn().mockResolvedValue({ theme: 'light', locale: 'en', platform: 'desktop' }) })
    const wrapper = mountSplitter(client)
    await flushPromises()
    await completeRun(wrapper)

    expect(wrapper.text()).toContain('2 file(s) written')
    expect(wrapper.find('[data-action="export-results"]').exists()).toBe(false)
  })

  it('requests an estimate after configuring and shows the count on the Output step', async () => {
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'estimate') return Promise.resolve({ success: true, fileCount: 3, exact: true })
      if (method === 'configure') return Promise.resolve({ success: true })
      return Promise.resolve({ success: true })
    })
    const client = fakeClient({ invoke: invoke as FengYuClient['invoke'] })
    const wrapper = mountSplitter(client)
    await chooseSource(wrapper)
    await next(wrapper) // configure + estimate fire here

    expect(invoke.mock.calls.filter(([method]) => method === 'estimate'))
      .toEqual([['estimate', { session: 'new-session' }]])
    await flushPromises()
    expect(wrapper.text()).toContain('Expected files')
    expect(wrapper.text()).toContain('3')
  })

  it('shows the concrete split rule on the Output summary for BY_COLUMN', async () => {
    const client = fakeClient()
    const wrapper = mountSplitter(client)
    await chooseSource(wrapper)
    await chooseMode(wrapper, 'BY_COLUMN')
    await updateVuetifyField(wrapper, 'VSelect', 'Sheet', 'Sales')
    await updateVuetifyField(wrapper, 'VSelect', 'Column', 'Region')
    await next(wrapper) // → Output step

    expect(wrapper.text()).toContain('Mode')
    expect(wrapper.text()).toContain('By column: Region in Sales')
    expect(wrapper.text()).toContain('Column “Region” in sheet “Sales”')
  })

  it('keeps an explicit export failure visible on the completed result', async () => {
    const client = fakeClient({
      files: { export: vi.fn().mockRejectedValue(new Error('Export grant expired')) },
    })
    const wrapper = mountSplitter(client)
    await completeRun(wrapper)

    await wrapper.get('[data-action="export-results"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('2 file(s) written')
    expect(wrapper.findAll('[role="alert"]')
      .filter((alert) => alert.text().includes('Export grant expired'))).toHaveLength(1)
  })

  it('replays restored BY_COLUMN configuration before enabling Output', async () => {
    saveExcelWizardRecord(sessionStorage, storedRecord('run', true))
    let resolveConfigure!: (value: unknown) => void
    const configure = new Promise((resolve) => { resolveConfigure = resolve })
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'configure') return configure
      return Promise.reject(new Error(`Unexpected method: ${method}`))
    })
    const client = fakeClient({ invoke: invoke as FengYuClient['invoke'] })
    const wrapper = mountSplitter(client)
    await flushPromises()

    expect(invoke).toHaveBeenCalledWith(
      'analyze',
      { session: 'restored-session', sourceFile: sourceRef },
      { signal: expect.any(AbortSignal) },
    )
    expect(invoke).toHaveBeenCalledWith(
      'configure',
      {
        session: 'restored-session',
        mode: 'BY_COLUMN',
        filePrefix: 'restored-',
        splitSheet: 'Sales',
        splitColumn: 'Region',
      },
      { signal: expect.any(AbortSignal) },
    )
    expect(step(wrapper, 'output').attributes('aria-current')).toBeUndefined()
    expect(step(wrapper, 'output').attributes('disabled')).toBeDefined()

    resolveConfigure({ success: true, summary: 'configured mode=BY_COLUMN' })
    await flushPromises()

    expect(step(wrapper, 'output').attributes('aria-current')).toBe('step')
    expect(step(wrapper, 'output').attributes('data-status')).toBe('active')
    expect(step(wrapper, 'run').attributes('data-status')).toBe('pending')
    expect(step(wrapper, 'run').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('Output: exports')
    expect(invoke.mock.calls.some(([method]) => method === 'split')).toBe(false)

    await step(wrapper, 'run').trigger('click')
    await flushPromises()
    expect(step(wrapper, 'output').attributes('aria-current')).toBe('step')
    expect(invoke.mock.calls.some(([method]) => method === 'split')).toBe(false)
  })

  it('restores empty BY_SHEET as an omitted selectedSheets field', async () => {
    const record = storedRecord('output')
    record.draft = {
      ...record.draft,
      mode: 'BY_SHEET',
      selectedSheets: [],
      splitSheet: null,
      splitColumn: null,
      filePrefix: '',
    }
    saveExcelWizardRecord(sessionStorage, record)
    const client = fakeClient()
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    const wrapper = mountSplitter(client)
    await flushPromises()

    expect(invoke).toHaveBeenCalledWith(
      'configure',
      {
        session: 'restored-session',
        mode: 'BY_SHEET',
        filePrefix: '',
      },
      { signal: expect.any(AbortSignal) },
    )
    const configureArgs = invoke.mock.calls.find(([method]) => method === 'configure')?.[1]
    expect(Object.hasOwn(configureArgs as object, 'selectedSheets')).toBe(false)
    expect(step(wrapper, 'output').attributes('aria-current')).toBe('step')
  })

  it.each([
    [
      'sheet',
      { Archive: { A: 'Region' } },
      'Choose a sheet from the analyzed workbook',
    ],
    [
      'column',
      { Sales: { A: 'Customer', B: 'Amount' } },
      'Choose a column from the analyzed sheet',
    ],
  ])(
    'rejects a restored BY_COLUMN %s that disappeared during re-analysis without configuring',
    async (_case, latestSheets, message) => {
      saveExcelWizardRecord(sessionStorage, storedRecord('output'))
      const invoke = vi.fn().mockImplementation((method: string) => {
        if (method === 'analyze') return Promise.resolve({ success: true, sheets: latestSheets })
        if (method === 'configure') {
          return Promise.resolve({ success: true, summary: 'must not configure a stale draft' })
        }
        return Promise.reject(new Error(`Unexpected method: ${method}`))
      })
      const wrapper = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
      await flushPromises()

      expect(invoke.mock.calls.filter(([method]) => method === 'configure')).toHaveLength(0)
      expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
      expect(step(wrapper, 'mode').attributes('data-status')).toBe('error')
      expect(step(wrapper, 'output').attributes('disabled')).toBeDefined()
      expect(wrapper.get('[data-wizard-error]').text()).toContain(message)
      expect(wrapper.get('[data-action="next"]').text()).toContain('Retry')
    },
  )

  it.each([
    ['failure response', { success: false, summary: 'Stored column no longer exists' }],
    ['thrown error', new Error('Configure worker restarted')],
  ])('returns restored configure %s to Mode and retries before Output', async (_case, failure) => {
    saveExcelWizardRecord(sessionStorage, storedRecord('run', true))
    let configureAttempts = 0
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'configure') {
        configureAttempts += 1
        if (configureAttempts === 1) {
          return failure instanceof Error ? Promise.reject(failure) : Promise.resolve(failure)
        }
        return Promise.resolve({ success: true, summary: 'configured mode=BY_COLUMN' })
      }
      return Promise.reject(new Error(`Unexpected method: ${method}`))
    })
    const wrapper = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
    await flushPromises()

    expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
    expect(step(wrapper, 'mode').attributes('data-status')).toBe('error')
    expect(step(wrapper, 'output').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-wizard-error]').text()).toContain(
      failure instanceof Error ? failure.message : failure.summary,
    )
    expect(wrapper.get('[data-action="next"]').text()).toContain('Retry')

    await next(wrapper)

    expect(configureAttempts).toBe(2)
    expect(step(wrapper, 'output').attributes('aria-current')).toBe('step')
  })

  it('ignores stale restored configure completion after a replacement source is selected', async () => {
    saveExcelWizardRecord(sessionStorage, storedRecord('run', true))
    let resolveConfigure!: (value: unknown) => void
    const configure = new Promise((resolve) => { resolveConfigure = resolve })
    const invoke = vi.fn().mockImplementation((method: string, params: Record<string, unknown>) => {
      if (method === 'configure') return configure
      if (method === 'analyze') {
        return Promise.resolve((params.sourceFile as FileRef).id === sourceRef.id
          ? analyzeSuccess
          : { success: true, sheets: { Replacement: { A: 'Fresh value' } } })
      }
      return Promise.reject(new Error(`Unexpected method: ${method}`))
    })
    const wrapper = mountSplitter(fakeClient({
      invoke: invoke as FengYuClient['invoke'],
      files: { open: vi.fn().mockResolvedValue(replacementSourceRef) },
    }))
    await flushPromises()
    expect(invoke.mock.calls.filter(([method]) => method === 'configure')).toHaveLength(1)

    await chooseSource(wrapper)
    expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')

    resolveConfigure({ success: true, summary: 'configured stale mode' })
    await flushPromises()

    expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
    expect(step(wrapper, 'output').attributes('aria-current')).toBeUndefined()
    expect(invoke.mock.calls.filter(([method]) => method === 'configure')).toHaveLength(1)
    await step(wrapper, 'source').trigger('click')
    expect(wrapper.text()).toContain('replacement.xlsx')
  })

  it.each(['resolve', 'reject'] as const)(
    'keeps a newly selected source when stale restore analysis later %s',
    async (settlement) => {
      saveExcelWizardRecord(sessionStorage, storedRecord('run', true))
      let resolveRestore!: (value: unknown) => void
      let rejectRestore!: (reason: unknown) => void
      const restoreAnalyze = new Promise((resolve, reject) => {
        resolveRestore = resolve
        rejectRestore = reject
      })
      const invoke = vi.fn().mockImplementation((method: string, params: Record<string, unknown>) => {
        if (method !== 'analyze') return Promise.resolve({ success: true })
        if ((params.sourceFile as FileRef).id === sourceRef.id) return restoreAnalyze
        return Promise.resolve({
          success: true,
          sheets: { Replacement: { A: 'Fresh value' } },
        })
      })
      const wrapper = mountSplitter(fakeClient({
        invoke: invoke as FengYuClient['invoke'],
        files: { open: vi.fn().mockResolvedValue(replacementSourceRef) },
      }))
      await flushPromises()
      expect(invoke.mock.calls.filter(([method]) => method === 'analyze')).toHaveLength(1)

      await chooseSource(wrapper)
      expect(invoke.mock.calls.filter(([method]) => method === 'analyze')).toHaveLength(2)
      expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')

      if (settlement === 'resolve') {
        resolveRestore({ success: true, sheets: { Stale: { A: 'Old value' } } })
      } else {
        rejectRestore(new Error('Old restore failed'))
      }
      await flushPromises()

      expect(step(wrapper, 'mode').attributes('aria-current')).toBe('step')
      expect(invoke.mock.calls.filter(([method]) => method === 'analyze')).toHaveLength(2)
      expect(invoke.mock.calls.some(([method]) => method === 'split')).toBe(false)
      expect(loadExcelWizardRecord(sessionStorage)).toMatchObject({
        wizard: { activeStep: 'mode' },
        draft: {
          sourceFileRef: replacementSourceRef,
          sessionId: 'new-session',
        },
      })

      await step(wrapper, 'source').trigger('click')
      expect(wrapper.text()).toContain('replacement.xlsx')
      expect(wrapper.text()).not.toContain('Stale')
      expect(wrapper.text()).not.toContain('Old restore failed')
    },
  )

  it('shares pending restore validation when Next is pressed', async () => {
    saveExcelWizardRecord(sessionStorage, storedRecord('run', true))
    let resolveRestore!: (value: unknown) => void
    const restoreAnalyze = new Promise((resolve) => { resolveRestore = resolve })
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return restoreAnalyze
      return Promise.resolve({ success: true })
    })
    const wrapper = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
    await flushPromises()

    expect(step(wrapper, 'source').attributes('data-status')).toBe('validating')
    await wrapper.get('[data-action="next"]').trigger('click')
    await flushPromises()
    expect(invoke.mock.calls.filter(([method]) => method === 'analyze')).toHaveLength(1)

    resolveRestore(analyzeSuccess)
    await flushPromises()
    expect(invoke.mock.calls.filter(([method]) => method === 'analyze')).toHaveLength(1)
    expect(invoke.mock.calls.some(([method]) => method === 'split')).toBe(false)
    expect(step(wrapper, 'output').attributes('aria-current')).toBe('step')
  })

  it('returns a failed restored source grant to Source and never calls split', async () => {
    saveExcelWizardRecord(sessionStorage, storedRecord('run', true))
    const invoke = vi.fn().mockRejectedValue(new Error('Source permission expired'))
    const wrapper = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
    await flushPromises()

    expect(step(wrapper, 'source').attributes('aria-current')).toBe('step')
    expect(wrapper.get('[data-wizard-error]').text()).toContain('Source permission expired')
    expect(invoke.mock.calls.some(([method]) => method === 'split')).toBe(false)
  })

  it('starts cleanly at Source when the storage record is corrupt', async () => {
    sessionStorage.setItem(EXCEL_WIZARD_STORAGE_KEY, '{not json')
    const invoke = vi.fn()
    const wrapper = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
    await flushPromises()

    expect(step(wrapper, 'source').attributes('aria-current')).toBe('step')
    expect(step(wrapper, 'source').attributes('data-status')).toBe('active')
    expect(wrapper.text()).not.toContain('Selected:')
    expect(invoke).not.toHaveBeenCalled()
  })
})
