import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render } from '@testing-library/react'
import { fireEvent } from '@testing-library/dom'
import type { FengYuClient, FileRef } from '@infinia/plugin-sdk'
import {
  FengYuClientProvider,
  FengYuI18nProvider,
  NotifyProvider,
  createFengYuI18n,
} from '@infinia/plugin-ui'
import { ExcelSplitter } from './ExcelSplitter'
import {
  EXCEL_WIZARD_STORAGE_KEY,
  loadExcelWizardRecord,
  saveExcelWizardRecord,
  type ExcelWizardRecord,
} from './excelWizardState'
import { tables } from './i18n'

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
  sheets: [{ name: 'Sales', columns: [{ index: '0', header: 'Region' }, { index: '1', header: 'Amount' }] }],
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

interface SplitterHandle {
  container: HTMLElement
  unmount: () => void
}

function mountSplitter(client: FengYuClient): SplitterHandle {
  // A fresh i18n runtime per mount keeps locale state isolated between tests.
  const i18n = createFengYuI18n(tables)
  const { container, unmount } = render(
    <FengYuClientProvider client={client}>
      <NotifyProvider client={client}>
        <FengYuI18nProvider i18n={i18n}>
          <ExcelSplitter nav="split" />
        </FengYuI18nProvider>
      </NotifyProvider>
    </FengYuClientProvider>,
  )
  return { container, unmount }
}

function step(handle: SplitterHandle, value: string): HTMLElement {
  const element = handle.container.querySelector(`[data-wizard-step="${value}"]`)
  expect(element, `step ${value}`).not.toBeNull()
  return element as HTMLElement
}

function status(handle: SplitterHandle, value: string): string | null {
  return step(handle, value).getAttribute('data-status')
}

function qsa(handle: SplitterHandle, selector: string): HTMLElement[] {
  return Array.from(handle.container.querySelectorAll<HTMLElement>(selector))
}

/** Drain microtasks, effects and the wizard's deferred (setTimeout 0) auto-advance/auto-run. */
async function flush(): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0))
  })
}

async function click(element: Element): Promise<void> {
  await act(async () => {
    fireEvent.click(element)
  })
}

async function changeValue(element: Element, value: string): Promise<void> {
  await act(async () => {
    fireEvent.change(element, { target: { value } })
  })
}

async function chooseSource(handle: SplitterHandle): Promise<void> {
  await click(handle.container.querySelector('[data-action="pick-file"]')!)
  await flush()
}

async function next(handle: SplitterHandle): Promise<void> {
  await click(handle.container.querySelector('[data-wizard-next]')!)
  await flush()
}

async function goBack(handle: SplitterHandle): Promise<void> {
  await click(handle.container.querySelector('[data-wizard-back]')!)
  await flush()
}

async function chooseOutput(handle: SplitterHandle): Promise<void> {
  await click(handle.container.querySelector('[data-action="pick-directory"]')!)
  await flush()
}

async function completeRun(handle: SplitterHandle): Promise<void> {
  await chooseSource(handle)
  await next(handle)
  await chooseOutput(handle)
  await next(handle)
  await next(handle)
}

async function chooseMode(handle: SplitterHandle, value: 'BY_COLUMN' | 'COMPLEX'): Promise<void> {
  await click(handle.container.querySelector(`[data-mode="${value}"]`)!)
  await flush()
}

async function addComplexRule(handle: SplitterHandle): Promise<void> {
  await click(handle.container.querySelector('[data-action="add-rule"]')!)
  await flush()
}

function numberInputs(handle: SplitterHandle): HTMLInputElement[] {
  return qsa(handle, 'input[type="number"]') as HTMLInputElement[]
}

function alertsContaining(handle: SplitterHandle, text: string): HTMLElement[] {
  return qsa(handle, '[role="alert"]').filter((alert) => alert.textContent?.includes(text))
}

function wizardError(handle: SplitterHandle): HTMLElement {
  const element = handle.container.querySelector('[data-error-state]')
  expect(element, 'wizard error state').not.toBeNull()
  return element as HTMLElement
}

function field(handle: SplitterHandle, name: string): HTMLInputElement {
  const element = handle.container.querySelector(`[data-field="${name}"]`)
  expect(element, `field ${name}`).not.toBeNull()
  return element as HTMLInputElement
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

  it('aborts an in-flight restore analyze when the component unmounts', async () => {
    // A stored record triggers restoreProgress → validateSource on mount, which fires analyze with
    // the restore controller's AbortSignal. Unmounting must abort that signal so the in-flight call
    // is transport-cancelled and its late resolution never mutates unmounted state.
    saveExcelWizardRecord(sessionStorage, storedRecord('mode'))
    let resolveAnalyze!: (value: unknown) => void
    const analyzePromise = new Promise((resolve) => { resolveAnalyze = resolve })
    let capturedSignal: AbortSignal | undefined
    const invoke = vi.fn().mockImplementation(
      (method: string, _params: unknown, options?: { signal?: AbortSignal }) => {
        if (method === 'analyze') {
          capturedSignal = options?.signal
          return analyzePromise
        }
        return Promise.resolve({ success: true })
      },
    )
    const client = fakeClient({ invoke: invoke as FengYuClient['invoke'] })
    const handle = mountSplitter(client)
    await flush() // restoreProgress → validateSource → invoke('analyze', …, { signal })

    expect(invoke).toHaveBeenCalledWith(
      'analyze', expect.anything(), expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(capturedSignal?.aborted, 'signal is live while mounted').toBe(false)

    handle.unmount() // unmount effect → cancelRestore → restoreController.abort()

    expect(capturedSignal?.aborted, 'unmount must abort the in-flight analyze signal').toBe(true)

    // Resolving the now-stale request after unmount must not throw or mutate anything.
    resolveAnalyze({ success: true, sheets: [] })
    await flush()
  })

  it('validates a selected source once and advances only after analyze succeeds', async () => {
    let resolveAnalyze!: (value: unknown) => void
    const analyze = new Promise((resolve) => { resolveAnalyze = resolve })
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return analyze
      return Promise.resolve({ success: true })
    })
    const handle = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))

    await click(handle.container.querySelector('[data-action="pick-file"]')!)
    await flush()

    expect(status(handle, 'source')).toBe('validating')
    expect(invoke).toHaveBeenCalledTimes(1)
    expect(invoke).toHaveBeenCalledWith(
      'analyze',
      { session: 'new-session', sourceFile: sourceRef },
      { signal: expect.any(AbortSignal) },
    )

    resolveAnalyze(analyzeSuccess)
    await flush()

    expect(status(handle, 'mode')).toBe('active')
    expect(status(handle, 'source')).toBe('complete')
  })

  it('renders numbered step chips with a check mark once a step completes', async () => {
    const handle = mountSplitter(fakeClient())
    await chooseSource(handle) // → Mode; Source completes

    expect(step(handle, 'source').textContent).toContain('✓')
    expect(step(handle, 'mode').textContent).toContain('2')
    expect(step(handle, 'output').textContent).toContain('3')
    expect(step(handle, 'run').textContent).toContain('4')
  })

  it('renders an analyze summary failure on Source and retries exactly once', async () => {
    const invoke = vi.fn()
      .mockResolvedValueOnce({ success: false, summary: 'Workbook grant expired' })
      .mockResolvedValueOnce(analyzeSuccess)
    const handle = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))

    await chooseSource(handle)

    expect(status(handle, 'source')).toBe('error')
    expect(wizardError(handle).textContent).toContain('Workbook grant expired')
    expect(alertsContaining(handle, 'Workbook grant expired')).toHaveLength(1)

    await next(handle)

    expect(invoke).toHaveBeenCalledTimes(2)
    expect(status(handle, 'mode')).toBe('active')
  })

  it.each([
    ['a fractional header index', 0, '1.9', 'Use whole-number indices of 1 or greater'],
    ['a zero column index', 1, '0', 'Use whole-number indices of 1 or greater'],
    ['a negative header index', 0, '-2', 'Use whole-number indices of 1 or greater'],
  ])(
    'rejects COMPLEX rules with %s without configuring',
    async (_case, fieldIndex, value, message) => {
      const client = fakeClient()
      const invoke = client.invoke as ReturnType<typeof vi.fn>
      const handle = mountSplitter(client)
      await chooseSource(handle)
      await chooseMode(handle, 'COMPLEX')
      await addComplexRule(handle)
      await changeValue(numberInputs(handle)[fieldIndex], value)

      await next(handle)

      expect(invoke.mock.calls.filter(([method]) => method === 'configure')).toHaveLength(0)
      expect(status(handle, 'mode')).toBe('error')
      expect(wizardError(handle).textContent).toContain(message)
    },
  )

  it('keeps valid COMPLEX indices numeric when edited through number inputs', async () => {
    const client = fakeClient()
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    const handle = mountSplitter(client)
    await chooseSource(handle)
    await chooseMode(handle, 'COMPLEX')
    await addComplexRule(handle)
    await changeValue(numberInputs(handle)[0], '2')
    await changeValue(numberInputs(handle)[1], '3')

    await next(handle)

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
    expect(status(handle, 'output')).toBe('active')
  })

  it('toggling copy-all clears both indices to the -1 sentinels through the checkbox', async () => {
    // The Vue test forced a disabled number field to 1 while copy-all was checked (component-event
    // emit). The React inputs are genuinely disabled, so the reachable DOM contract is the toggle:
    // checking copy-all must move a rule to the -1/-1 sentinel payload (covered end-to-end below).
    const client = fakeClient()
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    const handle = mountSplitter(client)
    await chooseSource(handle)
    await chooseMode(handle, 'COMPLEX')
    await addComplexRule(handle)
    const checkbox = handle.container.querySelector('input[type="checkbox"]') as HTMLInputElement
    expect(checkbox.checked).toBe(false)
    await click(checkbox)

    await next(handle)

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
    expect(numberInputs(handle).every((input) => input.disabled)).toBe(true)
    expect(status(handle, 'output')).toBe('active')
  })

  it.each([
    ['sheet', 'Archive', 'Region', 'Choose a sheet from the analyzed workbook'],
    ['column', 'Sales', 'Legacy', 'Choose a column from the analyzed sheet'],
  ])(
    'rejects an unavailable BY_COLUMN %s during ordinary Mode validation',
    async (_case, selectedSheet, selectedColumn, message) => {
      const client = fakeClient()
      const invoke = client.invoke as ReturnType<typeof vi.fn>
      const handle = mountSplitter(client)
      await chooseSource(handle)
      await chooseMode(handle, 'BY_COLUMN')
      // The sheet/column pickers are free-text comboboxes (datalist suggestions), so a stale
      // hand-typed value is expressible through the DOM exactly like the old VSelect emit.
      await changeValue(field(handle, 'splitSheet'), selectedSheet)
      await changeValue(field(handle, 'splitColumn'), selectedColumn)

      await next(handle)

      expect(invoke.mock.calls.filter(([method]) => method === 'configure')).toHaveLength(0)
      expect(status(handle, 'mode')).toBe('error')
      expect(wizardError(handle).textContent).toContain(message)
    },
  )

  it.each([
    {
      name: 'BY_SHEET with an empty selection',
      prepare: async (_handle: SplitterHandle) => {},
      expected: { session: 'new-session', mode: 'BY_SHEET', filePrefix: '' },
    },
    {
      name: 'BY_SHEET with selected sheets',
      prepare: async (handle: SplitterHandle) => {
        await click(handle.container.querySelector('[data-sheet="Sales"]')!)
        await changeValue(field(handle, 'filePrefix'), 'q3-')
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
      prepare: async (handle: SplitterHandle) => {
        await chooseMode(handle, 'BY_COLUMN')
        await changeValue(field(handle, 'splitSheet'), 'Sales')
        await changeValue(field(handle, 'splitColumn'), 'Region')
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
      prepare: async (handle: SplitterHandle) => {
        await chooseMode(handle, 'COMPLEX')
        await addComplexRule(handle)
        await click(handle.container.querySelector('input[type="checkbox"]')!)
        await flush()
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
    const handle = mountSplitter(client)

    await chooseSource(handle)
    await prepare(handle)
    await next(handle)
    expect(invoke).toHaveBeenCalledWith(
      'configure',
      expected,
      { signal: expect.any(AbortSignal) },
    )

    await chooseOutput(handle)
    await next(handle)
    await next(handle)

    expect(handle.container.textContent).toContain('2 file(s) written')
    expect(invoke.mock.calls.filter(([method]) => method === 'split')).toHaveLength(1)
  })

  it('sends an empty filePrefix to clear a prior value in the same worker session', async () => {
    const client = fakeClient()
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    const handle = mountSplitter(client)

    await chooseSource(handle)
    await changeValue(field(handle, 'filePrefix'), 'q3-')
    await next(handle)

    await goBack(handle) // Output → Mode
    await changeValue(field(handle, 'filePrefix'), '')
    await next(handle)

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
    const handle = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))

    await chooseSource(handle)
    await next(handle)

    expect(status(handle, 'mode')).toBe('error')
    expect(wizardError(handle).textContent).toContain('Selected sheet does not exist')
    expect(alertsContaining(handle, 'Selected sheet does not exist')).toHaveLength(1)

    await next(handle)
    expect(configureAttempts).toBe(2)
    expect(status(handle, 'output')).toBe('active')
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
    const handle = mountSplitter(client)

    await chooseSource(handle)
    await next(handle)
    await chooseOutput(handle)
    await next(handle) // Output → Run auto-runs the split, which fails

    expect(status(handle, 'run')).toBe('error')
    expect(wizardError(handle).textContent).toContain('Output directory is full')
    expect(alertsContaining(handle, 'Output directory is full')).toHaveLength(1)

    await next(handle)
    expect(splitAttempts).toBe(2)
    expect(client.files.outputDirectory).toHaveBeenCalledTimes(1)
    expect(handle.container.textContent).toContain('1 file(s) written')
  })

  it('clears a stale configure error when a replacement source invalidates Mode', async () => {
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'configure') {
        return Promise.resolve({ success: false, summary: 'Old configure failure' })
      }
      return Promise.reject(new Error(`Unexpected method: ${method}`))
    })
    const handle = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
    await chooseSource(handle)
    await next(handle)
    expect(handle.container.textContent).toContain('Old configure failure')

    await goBack(handle) // Mode → Source
    await chooseSource(handle)

    expect(status(handle, 'mode')).toBe('active')
    expect(handle.container.textContent).not.toContain('Old configure failure')
  })

  it('executes the split automatically when advancing from Output to Run', async () => {
    const client = fakeClient()
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    const handle = mountSplitter(client)
    await chooseSource(handle)
    await next(handle) // Mode → Output
    await chooseOutput(handle)
    await next(handle) // Output → Run: the split must run without another click

    expect(invoke.mock.calls.filter(([method]) => method === 'split')).toHaveLength(1)
    expect(handle.container.textContent).toContain('2 file(s) written')
  })

  it('clears a stale split error when the output is re-picked, then re-runs automatically', async () => {
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'configure') return Promise.resolve({ success: true })
      if (method === 'split') {
        return Promise.resolve({ success: false, summary: 'Old split failure' })
      }
      return Promise.reject(new Error(`Unexpected method: ${method}`))
    })
    const handle = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
    await chooseSource(handle)
    await next(handle)
    await chooseOutput(handle)
    await next(handle) // Output → Run auto-runs the split, which fails
    expect(handle.container.textContent).toContain('Old split failure')

    await goBack(handle) // Run → Output
    await chooseOutput(handle) // re-pick invalidates Run and clears the stale error

    expect(status(handle, 'output')).toBe('active')
    expect(status(handle, 'run')).toBe('pending')
    expect(handle.container.querySelector('[data-error-state]')).toBeNull()

    await next(handle) // Output → Run auto-runs the split again

    expect(status(handle, 'run')).toBe('error')
    expect(invoke.mock.calls.filter(([method]) => method === 'split')).toHaveLength(2)
    expect(wizardError(handle).textContent).toContain('Old split failure')
  })

  it('continues without persistence and reports an unavailable Storage once', async () => {
    vi.stubGlobal('sessionStorage', inaccessibleStorage())
    const client = fakeClient()
    const handle = mountSplitter(client)
    await flush()

    await chooseSource(handle)

    expect(status(handle, 'mode')).toBe('active')
    expect(client.notify).toHaveBeenCalledTimes(1)
    expect(client.notify).toHaveBeenCalledWith('Unable to save wizard progress')
  })

  it('invalidates Output and Run, clears the result, and saves incomplete progress when mode changes', async () => {
    const client = fakeClient()
    const handle = mountSplitter(client)
    await completeRun(handle)
    expect(handle.container.textContent).toContain('2 file(s) written')

    // The completed wizard freezes its own navigation; the result screen's "adjust split mode"
    // action reopens Mode through the same invalidation path as the old step-rail click.
    await click(handle.container.querySelector('[data-action="adjust-mode"]')!)
    await flush()
    await click(handle.container.querySelector('[data-mode="BY_COLUMN"]')!)
    await flush()

    expect(status(handle, 'output')).toBe('pending')
    expect(status(handle, 'run')).toBe('pending')
    expect(loadExcelWizardRecord(sessionStorage)).toMatchObject({
      wizard: { completed: false },
      draft: { mode: 'BY_COLUMN' },
    })

    expect(handle.container.textContent).not.toContain('2 file(s) written')
  })

  it('requires forward revalidation before another split after mode changes', async () => {
    const client = fakeClient()
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    const handle = mountSplitter(client)
    await completeRun(handle)
    expect(invoke.mock.calls.filter(([method]) => method === 'split')).toHaveLength(1)

    await click(handle.container.querySelector('[data-action="adjust-mode"]')!)
    await flush()
    await click(handle.container.querySelector('[data-mode="COMPLEX"]')!)
    await addComplexRule(handle)
    await click(handle.container.querySelector('input[type="checkbox"]')!)
    await flush()

    // Future steps stay locked at pending until Mode re-validates (the React-kit rail is not
    // clickable at all, so an invalid Run cannot be jumped to).
    expect(status(handle, 'output')).toBe('pending')
    expect(status(handle, 'run')).toBe('pending')

    await next(handle)
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
    expect(status(handle, 'output')).toBe('active')

    await chooseOutput(handle)
    await next(handle)
    await next(handle)
    expect(invoke.mock.calls.filter(([method]) => method === 'split')).toHaveLength(2)
  })

  it('requires an output selection before entering Run', async () => {
    const handle = mountSplitter(fakeClient())
    await chooseSource(handle)
    await next(handle)

    expect(status(handle, 'output')).toBe('active')
    await next(handle)

    expect(status(handle, 'output')).toBe('error')
    expect(wizardError(handle).textContent).toContain('Choose an output folder')
  })

  it('single-flights duplicate Run actions, shows completion, exports explicitly, and freezes navigation', async () => {
    let resolveSplit!: (value: unknown) => void
    const split = new Promise((resolve) => { resolveSplit = resolve })
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'configure') return Promise.resolve({ success: true })
      if (method === 'split') return split
      return Promise.reject(new Error(`Unexpected method: ${method}`))
    })
    const client = fakeClient({ invoke: invoke as FengYuClient['invoke'] })
    const handle = mountSplitter(client)
    await chooseSource(handle)
    await next(handle)
    await chooseOutput(handle)
    await next(handle) // Output → Run: auto-run puts the wizard mid-validation (busy)

    const finish = handle.container.querySelector('[data-wizard-next]') as HTMLButtonElement
    // The auto-run owns the single in-flight split; duplicate finish clicks must not start a second.
    await act(async () => {
      fireEvent.click(finish)
      fireEvent.click(finish)
    })
    await flush()

    expect(invoke).toHaveBeenCalledWith(
      'configure',
      { session: 'new-session', mode: 'BY_SHEET', filePrefix: '' },
      { signal: expect.any(AbortSignal) },
    )
    expect(invoke.mock.calls.filter(([method]) => method === 'split')).toHaveLength(1)
    expect(invoke).toHaveBeenCalledWith(
      'split',
      // split re-sends the full config so a worker restarted between configure and split
      // (the host tears down/restarts a worker on a file-grant version change, e.g. the
      // output-dir grant on the Output step) can re-apply it instead of falling back.
      {
        session: 'new-session',
        sourceFile: sourceRef,
        outputDir: outputRef,
        mode: 'BY_SHEET',
        filePrefix: '',
      },
      { signal: expect.any(AbortSignal) },
    )
    resolveSplit({ success: true, fileCount: 2, files: ['north.xlsx', 'south.xlsx'] })
    await flush()

    expect(handle.container.textContent).toContain('2 file(s) written')
    expect(client.files.export).not.toHaveBeenCalled()
    await click(handle.container.querySelector('[data-action="export-results"]')!)
    await flush()
    expect(client.files.export).toHaveBeenCalledWith(outputRef)

    // The React kit freezes navigation once completed: Back is disabled (the result screen's
    // restart / adjust actions replace the old back-from-complete path).
    expect((handle.container.querySelector('[data-wizard-back]') as HTMLButtonElement).disabled).toBe(true)
  })

  it('hides the Download button on desktop because files are written in place', async () => {
    const client = fakeClient({ ready: vi.fn().mockResolvedValue({ theme: 'light', locale: 'en', platform: 'desktop' }) })
    const handle = mountSplitter(client)
    await flush()
    await completeRun(handle)

    expect(handle.container.textContent).toContain('2 file(s) written')
    expect(handle.container.querySelector('[data-action="export-results"]')).toBeNull()
  })

  it('requests an estimate after configuring and shows the count on the Output step', async () => {
    const invoke = vi.fn().mockImplementation((method: string) => {
      if (method === 'analyze') return Promise.resolve(analyzeSuccess)
      if (method === 'estimate') return Promise.resolve({ success: true, fileCount: 3, exact: true })
      if (method === 'configure') return Promise.resolve({ success: true })
      return Promise.resolve({ success: true })
    })
    const client = fakeClient({ invoke: invoke as FengYuClient['invoke'] })
    const handle = mountSplitter(client)
    await chooseSource(handle)
    await next(handle) // configure + estimate fire here
    await flush()

    expect(invoke.mock.calls.filter(([method]) => method === 'estimate'))
      .toEqual([['estimate', { session: 'new-session' }, undefined]])
    expect(handle.container.textContent).toContain('Expected files')
    expect(handle.container.textContent).toContain('3')
  })

  it('shows the concrete split rule on the Output summary for BY_COLUMN', async () => {
    const client = fakeClient()
    const handle = mountSplitter(client)
    await chooseSource(handle)
    await chooseMode(handle, 'BY_COLUMN')
    await changeValue(field(handle, 'splitSheet'), 'Sales')
    await changeValue(field(handle, 'splitColumn'), 'Region')
    await next(handle) // → Output step

    expect(handle.container.textContent).toContain('Mode')
    expect(handle.container.textContent).toContain('By column: Region in Sales')
    expect(handle.container.textContent).toContain('Column “Region” in sheet “Sales”')
  })

  it('keeps an explicit export failure visible on the completed result', async () => {
    const client = fakeClient({
      files: { export: vi.fn().mockRejectedValue(new Error('Export grant expired')) },
    })
    const handle = mountSplitter(client)
    await completeRun(handle)

    await click(handle.container.querySelector('[data-action="export-results"]')!)
    await flush()

    expect(handle.container.textContent).toContain('2 file(s) written')
    expect(alertsContaining(handle, 'Export grant expired')).toHaveLength(1)
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
    const handle = mountSplitter(client)
    await flush()

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
    // While the restored configure is in flight the wizard still sits at Source: Output is
    // not reachable (the React-kit rail is never clickable; forward requires validation).
    expect(status(handle, 'output')).toBe('pending')

    resolveConfigure({ success: true, summary: 'configured mode=BY_COLUMN' })
    await flush()

    expect(status(handle, 'output')).toBe('active')
    expect(status(handle, 'run')).toBe('pending')
    expect(handle.container.textContent).not.toContain('Output: exports')
    expect(invoke.mock.calls.some(([method]) => method === 'split')).toBe(false)

    await next(handle) // Output validates with the restored grant and advances to Run
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
    const handle = mountSplitter(client)
    await flush()

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
    expect(status(handle, 'output')).toBe('active')
  })

  it.each([
    [
      'sheet',
      [{ name: 'Archive', columns: [{ index: '0', header: 'Region' }] }],
      'Choose a sheet from the analyzed workbook',
    ],
    [
      'column',
      [{ name: 'Sales', columns: [{ index: '0', header: 'Customer' }, { index: '1', header: 'Amount' }] }],
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
      const handle = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
      await flush()

      expect(invoke.mock.calls.filter(([method]) => method === 'configure')).toHaveLength(0)
      // The kit's snapshot normalize forces the seeded active step to 'active' — the restored
      // failure surfaces through the step body's own error alert instead of the rail status.
      expect(status(handle, 'mode')).toBe('active')
      expect(status(handle, 'output')).toBe('pending')
      expect(wizardError(handle).textContent).toContain(message)
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
    const handle = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
    await flush()

    expect(status(handle, 'mode')).toBe('active')
    expect(status(handle, 'output')).toBe('pending')
    expect(wizardError(handle).textContent).toContain(
      failure instanceof Error ? failure.message : failure.summary,
    )

    await next(handle)

    expect(configureAttempts).toBe(2)
    expect(status(handle, 'output')).toBe('active')
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
          : { success: true, sheets: [{ name: 'Replacement', columns: [{ index: '0', header: 'Fresh value' }] }] })
      }
      return Promise.reject(new Error(`Unexpected method: ${method}`))
    })
    const handle = mountSplitter(fakeClient({
      invoke: invoke as FengYuClient['invoke'],
      files: { open: vi.fn().mockResolvedValue(replacementSourceRef) },
    }))
    await flush()
    expect(invoke.mock.calls.filter(([method]) => method === 'configure')).toHaveLength(1)

    await chooseSource(handle)
    expect(status(handle, 'mode')).toBe('active')

    resolveConfigure({ success: true, summary: 'configured stale mode' })
    await flush()

    expect(status(handle, 'mode')).toBe('active')
    expect(status(handle, 'output')).toBe('pending')
    expect(invoke.mock.calls.filter(([method]) => method === 'configure')).toHaveLength(1)
    await goBack(handle) // Mode → Source
    expect(handle.container.textContent).toContain('replacement.xlsx')
  })

  it.each(['resolve', 'reject'] as const)(
    'keeps a newly selected source when stale restore analysis later $settlement',
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
          sheets: [{ name: 'Replacement', columns: [{ index: '0', header: 'Fresh value' }] }],
        })
      })
      const handle = mountSplitter(fakeClient({
        invoke: invoke as FengYuClient['invoke'],
        files: { open: vi.fn().mockResolvedValue(replacementSourceRef) },
      }))
      await flush()
      expect(invoke.mock.calls.filter(([method]) => method === 'analyze')).toHaveLength(1)

      await chooseSource(handle)
      expect(invoke.mock.calls.filter(([method]) => method === 'analyze')).toHaveLength(2)
      expect(status(handle, 'mode')).toBe('active')

      if (settlement === 'resolve') {
        resolveRestore({ success: true, sheets: [{ name: 'Stale', columns: [{ index: '0', header: 'Old value' }] }] })
      } else {
        rejectRestore(new Error('Old restore failed'))
      }
      await flush()

      expect(status(handle, 'mode')).toBe('active')
      expect(invoke.mock.calls.filter(([method]) => method === 'analyze')).toHaveLength(2)
      expect(invoke.mock.calls.some(([method]) => method === 'split')).toBe(false)
      expect(loadExcelWizardRecord(sessionStorage)).toMatchObject({
        wizard: { activeStep: 'mode' },
        draft: {
          sourceFileRef: replacementSourceRef,
          sessionId: 'new-session',
        },
      })

      await goBack(handle) // Mode → Source
      expect(handle.container.textContent).toContain('replacement.xlsx')
      expect(handle.container.textContent).not.toContain('Stale')
      expect(handle.container.textContent).not.toContain('Old restore failed')
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
    const handle = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
    await flush()

    // The restore analysis runs in the background; the wizard sits at Source until it settles.
    expect(status(handle, 'source')).toBe('active')
    await click(handle.container.querySelector('[data-wizard-next]')!)
    await flush()
    expect(status(handle, 'source')).toBe('validating')
    expect(invoke.mock.calls.filter(([method]) => method === 'analyze')).toHaveLength(1)

    resolveRestore(analyzeSuccess)
    await flush()
    expect(invoke.mock.calls.filter(([method]) => method === 'analyze')).toHaveLength(1)
    expect(invoke.mock.calls.some(([method]) => method === 'split')).toBe(false)
    expect(status(handle, 'output')).toBe('active')
  })

  it('returns a failed restored source grant to Source and never calls split', async () => {
    saveExcelWizardRecord(sessionStorage, storedRecord('run', true))
    const invoke = vi.fn().mockRejectedValue(new Error('Source permission expired'))
    const handle = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
    await flush()

    expect(status(handle, 'source')).toBe('active')
    expect(wizardError(handle).textContent).toContain('Source permission expired')
    expect(invoke.mock.calls.some(([method]) => method === 'split')).toBe(false)
  })

  it('starts cleanly at Source when the storage record is corrupt', async () => {
    sessionStorage.setItem(EXCEL_WIZARD_STORAGE_KEY, '{not json')
    const invoke = vi.fn()
    const handle = mountSplitter(fakeClient({ invoke: invoke as FengYuClient['invoke'] }))
    await flush()

    expect(status(handle, 'source')).toBe('active')
    expect(invoke).not.toHaveBeenCalled()
  })
})
