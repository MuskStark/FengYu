<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import {
  mdiAlertCircleOutline,
  mdiAlertOutline,
  mdiArrowLeft,
  mdiCheck,
  mdiCheckCircleOutline,
  mdiDownloadOutline,
  mdiFileExcelOutline,
  mdiFolderCheckOutline,
  mdiFormatColumns,
  mdiInformationOutline,
  mdiPlus,
  mdiProgressClock,
  mdiSitemapOutline,
  mdiTableMultiple,
} from '@mdi/js'
import {
  createWizardStates,
  FyDirectoryPicker,
  FyFilePicker,
  FyIcon,
  FyPluginPage,
  FyPluginShell,
  FyProgress,
  FyStepWizard,
  invalidateWizardStates,
  useFengYuNotify,
  useFengYuClient,
} from '@infinia/plugin-ui'
import type {
  FyWizardSnapshot,
  FyWizardSlotActions,
  FyWizardStep,
  FyWizardStepState,
  FyWizardValidationResult,
} from '@infinia/plugin-ui'
import type { FileRef } from '@infinia/plugin-sdk'
import type { Environment } from '@infinia/plugin-sdk'
import {
  clearExcelWizardRecord,
  loadExcelWizardRecord,
  saveExcelWizardRecord,
  type ExcelWizardDraft,
} from './excelWizardState'
import { useFengYuEnvironment } from './env'
import { createPluginRpc } from './rpc'
import type { AnalyzeOutput, ConfigureInput, SplitInput } from './generated/fengyu-rpc'

const client = useFengYuClient()
const rpc = createPluginRpc(client)
const { t } = useFengYuEnvironment()
const { notify } = useFengYuNotify(client)

type SplitMode = 'BY_SHEET' | 'BY_COLUMN' | 'COMPLEX'

interface ModeOption {
  value: SplitMode
  label: string
  hint: string
  icon: string
}

const modeOptions = computed<ModeOption[]>(() => [
  { value: 'BY_SHEET', label: t('exui.mode.bySheet.label'), hint: t('exui.mode.bySheet.hint'), icon: mdiTableMultiple },
  { value: 'BY_COLUMN', label: t('exui.mode.byColumn.label'), hint: t('exui.mode.byColumn.hint'), icon: mdiFormatColumns },
  { value: 'COMPLEX', label: t('exui.mode.complex.label'), hint: t('exui.mode.complex.hint'), icon: mdiSitemapOutline },
])

/** Analyzed workbook shape from the generated `analyze` Output: one entry per sheet. */
type AnalyzedSheet = NonNullable<NonNullable<AnalyzeOutput['sheets']>[number]>
interface ComplexEntryRow {
  fieldName: string
  sheetName: string
  headerIndex: number
  columnIndex: number
  copyAll: boolean
}

const steps = computed<FyWizardStep[]>(() => [
  { value: 'source', title: t('exui.step.source') },
  { value: 'mode', title: t('exui.step.mode') },
  { value: 'output', title: t('exui.step.output') },
  { value: 'run', title: t('exui.step.run') },
])
const activeStep = ref('source')
const wizardStates = ref<Record<string, FyWizardStepState>>(
  createWizardStates(steps.value, 'source'),
)
const wizardCompleted = ref(false)
const restoreSnapshot = shallowRef<FyWizardSnapshot>()
const latestSnapshot = shallowRef<FyWizardSnapshot>()
const wizardContext = {}
let restoreController: AbortController | undefined
let restoreGeneration = 0
let analyzeGeneration = 0
let persistenceWarningShown = false
let restoreValidation: {
  generation: number
  sourceId: string
  sessionId: string
  promise: Promise<FyWizardValidationResult>
} | undefined

/**
 * FyStepWizard hands the same stable slot-actions object to every step slot. Stashing it lets
 * {@link onWizardTransition} trigger the next advance programmatically without re-implementing
 * wizard navigation in this component. Called from a hidden interpolation in the slots; the
 * return value renders as an empty string.
 */
const wizardActions = ref<FyWizardSlotActions | null>(null)

function captureWizardActions(actions: FyWizardSlotActions): string {
  wizardActions.value = actions
  return ''
}

/**
 * Confirmed design: advancing Output → Run executes the split immediately — no second click on
 * the Run step. The wizard only emits `transition` on a genuine validated advance, so clicking
 * back into Run through the step rail never re-triggers a split.
 *
 * The auto-advance must be deferred past the emitting `advance()` itself: after `transition`
 * fires, FyStepWizard still awaits its focus helper and clears its `transitioning` flag in a
 * `finally`. A macrotask boundary guarantees the wizard has fully settled (another `nextTick`
 * would run while `isBusy` is still true and be dropped).
 */
function onWizardTransition(from: string, to: string): void {
  if (from !== 'output' || to !== 'run') return
  if (wizardCompleted.value || running.value) return
  setTimeout(() => {
    if (activeStep.value !== 'run' || wizardCompleted.value || running.value) return
    void wizardActions.value?.next()
  }, 0)
}

// Step 1 — source. sourceFileRef is the host FileRef (resolved to an absolute path
// by PluginProcessManager before reaching the worker).
const sourceFileRef = ref<FileRef | null>(null)
const session = ref<string | null>(null)
const analyzing = ref(false)
const analyzeError = ref<string | null>(null)
const sheets = ref<AnalyzedSheet[] | null>(null)

// Step 2 — mode + config
const mode = ref<SplitMode>('BY_SHEET')
const selectedSheets = ref<string[]>([])
const splitSheet = ref<string | null>(null)
const splitColumn = ref<string | null>(null)
const filePrefix = ref('')
const complexEntries = ref<ComplexEntryRow[]>([])
const configuring = ref(false)
const configureError = ref<string | null>(null)
const estimating = ref(false)
const estimatedFileCount = ref<number | null>(null)

// Step 3 — output
const outputDirRef = ref<FileRef | null>(null)
const platform = ref<Environment['platform'] | null>(null)

// Step 4 — run
const running = ref(false)
const downloading = ref(false)
const runError = ref<string | null>(null)
const result = ref<{ fileCount: number; files: string[] } | null>(null)

const sheetNames = computed<string[]>(() => (sheets.value ? sheets.value.map((s) => s.name ?? '') : []))

function columnsForSheet(sheetName: string | null): string[] {
  if (!sheetName || !sheets.value) return []
  const sheet = sheets.value.find((s) => s.name === sheetName)
  return (sheet?.columns ?? []).map((c) => c.header ?? '')
}
const columnsForSplitSheet = computed<string[]>(() => columnsForSheet(splitSheet.value))

function errMsg(err: unknown): string {
  if (err && typeof err === 'object') {
    const value = err as Record<string, unknown>
    if (typeof value.summary === 'string' && value.summary.trim()) return value.summary
    if (typeof value.message === 'string' && value.message.trim()) return value.message
  }
  return err instanceof Error && err.message ? err.message : String(err)
}

function notifyErr(msg: string): void {
  void notify(msg, { tone: 'error' })
}

function reportPersistenceFailure(): void {
  if (persistenceWarningShown) return
  persistenceWarningShown = true
  notifyErr(t('exui.notify.unableSave'))
}

function responseError(
  response: { error?: string; summary?: string },
  fallback: string,
): string {
  return response.error ?? response.summary ?? fallback
}

function abortIfStale(signal: AbortSignal): void {
  if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
}

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

function currentDraft(): ExcelWizardDraft {
  return {
    sourceFileRef: sourceFileRef.value ? { ...sourceFileRef.value } : null,
    sessionId: session.value ?? '',
    mode: mode.value,
    selectedSheets: [...selectedSheets.value],
    splitSheet: splitSheet.value,
    splitColumn: splitColumn.value,
    filePrefix: filePrefix.value,
    complexEntries: complexEntries.value.map((entry) => ({ ...entry })),
  }
}

function persistSnapshot(snapshot: FyWizardSnapshot): void {
  latestSnapshot.value = snapshot
  if (!sourceFileRef.value || !session.value) return
  const saved = saveExcelWizardRecord(sessionStorage, {
    version: 1,
    wizard: snapshot,
    draft: currentDraft(),
  })
  if (!saved) reportPersistenceFailure()
}

function resetNavigationFrom(changedStep: string): void {
  const changedIndex = steps.value.findIndex((step) => step.value === changedStep)
  if (changedIndex < 0) return
  const sourceSnapshot = latestSnapshot.value
  const allowed = new Set(steps.value.slice(0, changedIndex + 1).map((step) => step.value))
  const visitedPath = sourceSnapshot?.visitedPath.filter((step) => allowed.has(step)) ?? []
  if (!visitedPath.includes(changedStep)) visitedPath.push(changedStep)
  const states = {
    ...wizardStates.value,
    [changedStep]: { status: 'active' } as FyWizardStepState,
  }
  wizardStates.value = states
  const snapshot: FyWizardSnapshot = {
    version: 1,
    activeStep: changedStep,
    visitedPath,
    states,
    completed: false,
  }
  restoreSnapshot.value = snapshot
  persistSnapshot(snapshot)
}

function invalidateDependencies(changedStep: string): void {
  if (changedStep === 'source') configureError.value = null
  if (changedStep !== 'run') runError.value = null
  wizardStates.value = invalidateWizardStates(
    wizardStates.value,
    invalidateFrom(changedStep),
  )
  wizardCompleted.value = false
  result.value = null
  if (changedStep === 'source' || changedStep === 'mode') outputDirRef.value = null
  resetNavigationFrom(changedStep)
}

async function validateSource(signal: AbortSignal): Promise<FyWizardValidationResult> {
  if (!sourceFileRef.value || !session.value) {
    return { valid: false, message: t('exui.validation.chooseExcelFile') }
  }
  const source = sourceFileRef.value
  const sessionId = session.value
  const generation = ++analyzeGeneration
  analyzing.value = true
  analyzeError.value = null
  try {
    abortIfStale(signal)
    const res = await rpc.analyze({
      session: sessionId,
      sourceFile: source as unknown as string,
    }, { signal })
    abortIfStale(signal)
    if (sourceFileRef.value?.id !== source.id || session.value !== sessionId) {
      throw new DOMException('Aborted', 'AbortError')
    }
    if (!res.success) {
      const msg = responseError(res, t('exui.fallback.analyzeFailed'))
      analyzeError.value = msg
      return { valid: false, message: msg }
    }
    sheets.value = res.sheets ?? []
    return { valid: true }
  } catch (err) {
    if (signal.aborted || (err instanceof DOMException && err.name === 'AbortError')) throw err
    const msg = errMsg(err)
    analyzeError.value = msg
    return { valid: false, message: msg }
  } finally {
    if (generation === analyzeGeneration) analyzing.value = false
  }
}

function cancelRestore(): void {
  restoreGeneration += 1
  restoreController?.abort()
  restoreController = undefined
  restoreValidation = undefined
}

async function onFilePicked(
  ref: FileRef | null,
  advance: () => Promise<void>,
): Promise<void> {
  cancelRestore()
  if (!clearExcelWizardRecord(sessionStorage)) reportPersistenceFailure()
  sourceFileRef.value = ref
  session.value = null
  sheets.value = null
  analyzeError.value = null
  outputDirRef.value = null
  invalidateDependencies('source')
  if (ref) {
    session.value = crypto.randomUUID()
    await nextTick()
    await advance()
  }
}

function invalidateModeConfiguration(): void {
  configureError.value = null
  estimatedFileCount.value = null
  invalidateDependencies('mode')
}

function onModeChanged(value: SplitMode | null): void {
  if (!value) return
  mode.value = value
  invalidateModeConfiguration()
}

function onSplitSheetChanged(value: string | null): void {
  splitSheet.value = value
  splitColumn.value = null
  invalidateModeConfiguration()
}

function onOutputPicked(ref: FileRef | null): void {
  outputDirRef.value = ref
  invalidateDependencies('output')
}

function addComplexEntry(): void {
  complexEntries.value.push({
    fieldName: '',
    sheetName: sheetNames.value[0] ?? '',
    headerIndex: 1,
    columnIndex: 1,
    copyAll: false,
  })
  invalidateModeConfiguration()
}

function removeComplexEntry(index: number): void {
  complexEntries.value.splice(index, 1)
  invalidateModeConfiguration()
}

function onCopyAllToggle(entry: ComplexEntryRow): void {
  if (entry.copyAll) {
    entry.headerIndex = -1
    entry.columnIndex = -1
  } else {
    entry.headerIndex = 1
    entry.columnIndex = 1
  }
  invalidateModeConfiguration()
}

let estimateGeneration = 0

/** Pulls the expected output-file count from the worker after configure. Non-fatal: any error
 *  just leaves the count hidden on the Output step. Guarded against stale mode changes. */
async function refreshEstimate(expectedSession: string, expectedMode: SplitMode): Promise<void> {
  const generation = ++estimateGeneration
  estimating.value = true
  try {
    const res = await rpc.estimate({ session: expectedSession })
    if (generation !== estimateGeneration) return
    if (session.value !== expectedSession || mode.value !== expectedMode) return
    estimatedFileCount.value = res.success && typeof res.fileCount === 'number' ? res.fileCount : null
  } catch {
    if (generation === estimateGeneration) estimatedFileCount.value = null
  } finally {
    if (generation === estimateGeneration) estimating.value = false
  }
}

const modeLabel = computed(() => {
  switch (mode.value) {
    case 'BY_SHEET': return selectedSheets.value.length > 0
      ? t('exui.modeLabel.bySheetSelected', selectedSheets.value.length)
      : t('exui.modeLabel.bySheetAll')
    case 'BY_COLUMN': return splitSheet.value && splitColumn.value
      ? t('exui.modeLabel.byColumn', splitColumn.value, splitSheet.value)
      : t('exui.modeLabel.byColumnPlain')
    case 'COMPLEX': return complexEntries.value.length === 1
      ? t('exui.modeLabel.complex', complexEntries.value.length)
      : t('exui.modeLabel.complexPlural', complexEntries.value.length)
  }
})

/** Concrete split rules for the Output summary, one string per rule. */
const configDetails = computed<string[]>(() => {
  switch (mode.value) {
    case 'BY_SHEET':
      return selectedSheets.value.length > 0
        ? selectedSheets.value
        : sheetNames.value
    case 'BY_COLUMN':
      return splitSheet.value && splitColumn.value
        ? [t('exui.detail.columnInSheet', splitColumn.value, splitSheet.value)]
        : []
    case 'COMPLEX':
      return complexEntries.value.map((entry) => entry.copyAll
        ? t('exui.detail.copyEntireSheet', entry.sheetName)
        : t('exui.detail.splitSheetByColumn', entry.sheetName, entry.columnIndex, entry.headerIndex))
  }
})

async function validateMode(signal: AbortSignal): Promise<FyWizardValidationResult> {
  if (!session.value) return { valid: false, message: t('exui.validation.chooseExcelFile') }
  if (mode.value === 'BY_COLUMN') {
    if (!splitSheet.value || !splitColumn.value) {
      return { valid: false, message: t('exui.validation.chooseSheetAndColumn') }
    }
    if (!sheetNames.value.includes(splitSheet.value)) {
      return { valid: false, message: t('exui.validation.chooseSheetFromWorkbook') }
    }
    if (!columnsForSheet(splitSheet.value).includes(splitColumn.value)) {
      return { valid: false, message: t('exui.validation.chooseColumnFromSheet') }
    }
  }
  if (
    mode.value === 'COMPLEX'
    && (complexEntries.value.length === 0 || complexEntries.value.some((entry) => !entry.sheetName))
  ) {
    return { valid: false, message: t('exui.validation.addOneRule') }
  }
  if (
    mode.value === 'COMPLEX'
    && complexEntries.value.some((entry) => entry.copyAll
      && (entry.headerIndex !== -1 || entry.columnIndex !== -1))
  ) {
    return { valid: false, message: t('exui.validation.copyAllIndices') }
  }
  if (
    mode.value === 'COMPLEX'
    && complexEntries.value.some((entry) => !entry.copyAll && (
      !Number.isInteger(entry.headerIndex)
      || entry.headerIndex < 1
      || !Number.isInteger(entry.columnIndex)
      || entry.columnIndex < 1
    ))
  ) {
    return { valid: false, message: t('exui.validation.positiveIndices') }
  }

  configuring.value = true
  configureError.value = null
  try {
    const args: ConfigureInput = {
      session: session.value,
      mode: mode.value,
      filePrefix: filePrefix.value,
    }
    if (mode.value === 'BY_SHEET') {
      if (selectedSheets.value.length > 0) args.selectedSheets = [...selectedSheets.value]
    } else if (mode.value === 'BY_COLUMN') {
      if (splitSheet.value) args.splitSheet = splitSheet.value
      if (splitColumn.value) args.splitColumn = splitColumn.value
    } else if (mode.value === 'COMPLEX') {
      args.complexEntries = complexEntries.value.map((e) => ({
        fieldName: e.fieldName,
        sheetName: e.sheetName,
        headerIndex: e.headerIndex,
        columnIndex: e.columnIndex,
      }))
    }
    abortIfStale(signal)
    const res = await rpc.configure(args, { signal })
    abortIfStale(signal)
    if (!res.success) {
      const msg = responseError(res, t('exui.fallback.configureFailed'))
      configureError.value = msg
      return { valid: false, message: msg }
    }
    // Configure succeeded — refresh the expected file count shown on Output. Fire-and-forget:
    // the estimate must not gate step validation; a failure just leaves the count hidden.
    void refreshEstimate(session.value, mode.value)
    return { valid: true }
  } catch (err) {
    if (signal.aborted || (err instanceof DOMException && err.name === 'AbortError')) throw err
    const msg = errMsg(err)
    configureError.value = msg
    return { valid: false, message: msg }
  } finally {
    configuring.value = false
  }
}

async function runSplit(signal: AbortSignal): Promise<FyWizardValidationResult> {
  if (!session.value || !sourceFileRef.value) {
    return { valid: false, message: t('exui.validation.chooseExcelFile') }
  }
  if (!outputDirRef.value) return { valid: false, message: t('exui.validation.chooseOutputFolder') }
  running.value = true
  runError.value = null
  result.value = null
  try {
    abortIfStale(signal)
    // The split call carries the full split config so the worker can re-apply it. The host tears
    // down and relaunches a plugin worker whenever its file-grant version changes — picking the
    // output folder on the Output step grants the output dir and bumps that version, so the worker
    // serving `split` is a fresh process that never saw the earlier `configure`. Without re-sending
    // the config, split falls back to the default BY_SHEET mode and just copies the source file.
    const splitArgs: SplitInput = {
      session: session.value,
      sourceFile: sourceFileRef.value as unknown as string,
      outputDir: outputDirRef.value as unknown as string,
      mode: mode.value,
      filePrefix: filePrefix.value,
    }
    if (mode.value === 'BY_SHEET') {
      if (selectedSheets.value.length > 0) splitArgs.selectedSheets = [...selectedSheets.value]
    } else if (mode.value === 'BY_COLUMN') {
      if (splitSheet.value) splitArgs.splitSheet = splitSheet.value
      if (splitColumn.value) splitArgs.splitColumn = splitColumn.value
    } else if (mode.value === 'COMPLEX') {
      splitArgs.complexEntries = complexEntries.value.map((e) => ({
        fieldName: e.fieldName,
        sheetName: e.sheetName,
        headerIndex: e.headerIndex,
        columnIndex: e.columnIndex,
      }))
    }
    const res = await rpc.split(splitArgs, { signal })
    abortIfStale(signal)
    if (!res.success) {
      const msg = responseError(res, t('exui.fallback.splitFailed'))
      runError.value = msg
      return { valid: false, message: msg }
    }
    result.value = { fileCount: res.fileCount ?? 0, files: res.files ?? [] }
    return { valid: true }
  } catch (err) {
    if (signal.aborted || (err instanceof DOMException && err.name === 'AbortError')) throw err
    const msg = errMsg(err)
    runError.value = msg
    return { valid: false, message: msg }
  } finally {
    running.value = false
  }
}

async function validateStep(
  step: string,
  _context: unknown,
  signal: AbortSignal,
): Promise<FyWizardValidationResult> {
  if (step === 'source') {
    const pending = restoreValidation
    if (
      pending
      && pending.sourceId === sourceFileRef.value?.id
      && pending.sessionId === session.value
    ) {
      const result = await pending.promise
      abortIfStale(signal)
      return result
    }
    return validateSource(signal)
  }
  if (step === 'mode') return validateMode(signal)
  if (step === 'output') {
    return outputDirRef.value
      ? { valid: true }
      : { valid: false, message: t('exui.validation.chooseOutputFolder') }
  }
  if (step === 'run') return runSplit(signal)
  return { valid: false, message: t('exui.validation.unknownStep', step) }
}

function onValidationError(_step: string, message?: string): void {
  if (message) notifyErr(message)
}

async function downloadResult(): Promise<void> {
  if (!outputDirRef.value || downloading.value) return
  downloading.value = true
  runError.value = null
  try {
    await client.files.export(outputDirRef.value)
  } catch (err) {
    const msg = errMsg(err)
    runError.value = msg
    notifyErr(msg)
  } finally {
    downloading.value = false
  }
}

/** "Split another file" on the completed screen: reset every wizard state and return to Source. */
function restartWizard(): void {
  cancelRestore()
  if (!clearExcelWizardRecord(sessionStorage)) reportPersistenceFailure()
  sourceFileRef.value = null
  session.value = null
  sheets.value = null
  analyzeError.value = null
  mode.value = 'BY_SHEET'
  selectedSheets.value = []
  splitSheet.value = null
  splitColumn.value = null
  filePrefix.value = ''
  complexEntries.value = []
  outputDirRef.value = null
  result.value = null
  runError.value = null
  estimatedFileCount.value = null
  configureError.value = null
  wizardCompleted.value = false
  const states = createWizardStates(steps.value, 'source')
  const snapshot: FyWizardSnapshot = {
    version: 1,
    activeStep: 'source',
    visitedPath: ['source'],
    states,
    completed: false,
  }
  wizardStates.value = states
  restoreSnapshot.value = snapshot
  latestSnapshot.value = snapshot
}

function applyDraft(draft: ExcelWizardDraft): void {
  sourceFileRef.value = draft.sourceFileRef ? { ...draft.sourceFileRef } : null
  session.value = draft.sessionId
  mode.value = draft.mode
  selectedSheets.value = [...draft.selectedSheets]
  splitSheet.value = draft.splitSheet
  splitColumn.value = draft.splitColumn
  filePrefix.value = draft.filePrefix
  complexEntries.value = draft.complexEntries.map((entry) => ({ ...entry }))
}

async function restoreProgress(): Promise<void> {
  const record = loadExcelWizardRecord(sessionStorage)
  if (!record) return
  cancelRestore()
  const generation = restoreGeneration
  const controller = new AbortController()
  restoreController = controller
  applyDraft(record.draft)
  outputDirRef.value = null
  result.value = null
  activeStep.value = 'source'
  wizardStates.value = {
    ...createWizardStates(steps.value, 'source'),
    source: { status: 'validating' },
  }
  wizardCompleted.value = false
  const sourceId = record.draft.sourceFileRef?.id ?? ''
  const sessionId = record.draft.sessionId
  const restoredActive = record.wizard.activeStep === 'run'
    ? 'output'
    : record.wizard.activeStep
  const restoredIndex = steps.value.findIndex((step) => step.value === restoredActive)
  const promise = validateSource(controller.signal)
  restoreValidation = { generation, sourceId, sessionId, promise }
  try {
    const sourceResult = await promise
    if (restoreValidation?.generation === generation) restoreValidation = undefined
    if (
      controller.signal.aborted
      || generation !== restoreGeneration
      || sourceFileRef.value?.id !== sourceId
      || session.value !== sessionId
    ) return
    if (!sourceResult.valid) {
      activeStep.value = 'source'
      wizardStates.value = {
        ...createWizardStates(steps.value, 'source'),
        source: { status: 'error', error: sourceResult.message },
      }
      wizardCompleted.value = false
      latestSnapshot.value = {
        version: 1,
        activeStep: 'source',
        visitedPath: ['source'],
        states: wizardStates.value,
        completed: false,
      }
      if (sourceResult.message) notifyErr(sourceResult.message)
      return
    }

    if (restoredIndex >= steps.value.findIndex((step) => step.value === 'output')) {
      const modeResult = await validateMode(controller.signal)
      if (
        controller.signal.aborted
        || generation !== restoreGeneration
        || sourceFileRef.value?.id !== sourceId
        || session.value !== sessionId
      ) return
      if (!modeResult.valid) {
        const states: Record<string, FyWizardStepState> = {
          ...createWizardStates(steps.value, 'mode'),
          source: { status: 'complete' },
          mode: modeResult.message
            ? { status: 'error', error: modeResult.message }
            : { status: 'error' },
        }
        const snapshot: FyWizardSnapshot = {
          version: 1,
          activeStep: 'mode',
          visitedPath: ['source', 'mode'],
          states,
          completed: false,
        }
        activeStep.value = 'mode'
        wizardStates.value = states
        wizardCompleted.value = false
        latestSnapshot.value = snapshot
        if (modeResult.message) notifyErr(modeResult.message)
        return
      }
    }

    const restoredVisitedPath = record.wizard.visitedPath.filter((value) => {
      const index = steps.value.findIndex((step) => step.value === value)
      return index >= 0 && index <= restoredIndex
    })
    if (!restoredVisitedPath.includes(restoredActive)) restoredVisitedPath.push(restoredActive)
    restoreSnapshot.value = {
      ...record.wizard,
      activeStep: restoredActive,
      visitedPath: [...new Set(restoredVisitedPath)],
      states: invalidateWizardStates(record.wizard.states, ['output', 'run']),
      completed: false,
    }
  } catch (err) {
    if (
      controller.signal.aborted
      || generation !== restoreGeneration
      || (err instanceof DOMException && err.name === 'AbortError')
    ) return
    throw err
  } finally {
    if (restoreValidation?.generation === generation) restoreValidation = undefined
    if (restoreController === controller) restoreController = undefined
  }
}

onMounted(() => {
  void client.ready().then((env) => { platform.value = env.platform ?? 'web' }).catch(() => {})
  void restoreProgress()
})
onBeforeUnmount(cancelRestore)
</script>

<template>
  <FyPluginShell :title="t('exui.title')">
    <FyPluginPage fluid class="excel-splitter">
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
          :back-text="t('exui.wizard.back')"
          :next-text="t('exui.wizard.next')"
          :finish-text="t('exui.wizard.finish')"
          :retry-text="t('exui.wizard.retry')"
          :optional-text="t('exui.wizard.optional')"
          @snapshot="persistSnapshot"
          @validation-error="onValidationError"
          @transition="onWizardTransition"
        >
          <!-- Vuetify-style stepper header: numbered avatar + title, green when complete. -->
          <template #step-label="{ step, index, state, statusLabel }">
            <span
              class="excel-avatar"
              :class="`excel-avatar--${state.status}`"
              aria-hidden="true"
            >
              <FyIcon v-if="state.status === 'complete'" :path="mdiCheck" :size="16" />
              <FyIcon v-else-if="state.status === 'validating'" :path="mdiProgressClock" :size="16" />
              <FyIcon v-else-if="state.status === 'error'" :path="mdiAlertOutline" :size="16" />
              <template v-else>{{ index + 1 }}</template>
            </span>
            <span class="excel-step-title">{{ step.title }}</span>
            <span class="excel-sr-only">({{ statusLabel }})</span>
          </template>

          <template #source="{ actions }">
            <span hidden>{{ captureWizardActions(actions) }}</span>
            <div class="excel-card-title">{{ t('exui.source.cardTitle') }}</div>
            <div class="excel-source-grid">
              <div class="excel-drop-zone">
                <FyIcon :path="mdiFileExcelOutline" :size="34" class="excel-drop-zone__icon" />
                <div class="excel-drop-zone__copy">
                  <div class="excel-drop-zone__title">{{ t('exui.source.zoneTitle') }}</div>
                  <div class="excel-drop-zone__sub">{{ t('exui.source.zoneSub') }}</div>
                </div>
                <FyFilePicker
                  class="excel-drop-zone__picker"
                  :model-value="sourceFileRef"
                  :extensions="['xlsx', 'xls']"
                  :filters="[{ name: 'Excel', extensions: ['xlsx', 'xls'] }]"
                  :label="t('exui.source.browse')"
                  @update:model-value="(ref: FileRef | null) => onFilePicked(ref, actions.next)"
                />
              </div>
              <div class="excel-source-tips">
                <strong>{{ t('exui.source.tipsTitle') }}</strong>
                <span class="excel-tip">
                  <FyIcon :path="mdiCheck" :size="15" class="excel-tip__icon" />
                  {{ t('exui.source.tip1') }}
                </span>
                <span class="excel-tip">
                  <FyIcon :path="mdiCheck" :size="15" class="excel-tip__icon" />
                  {{ t('exui.source.tip2') }}
                </span>
                <span class="excel-tip">
                  <FyIcon :path="mdiCheck" :size="15" class="excel-tip__icon" />
                  {{ t('exui.source.tip3') }}
                </span>
              </div>
            </div>

            <FyProgress v-if="analyzing" :label="t('exui.source.analyzing')" class="mt-4 w-100" />
          </template>

          <template #mode>
            <div class="excel-card-title">{{ t('exui.mode.cardTitle') }}</div>
            <div class="excel-mode-cards" role="radiogroup" :aria-label="t('exui.source.ariaSplitMode')">
              <button
                v-for="option in modeOptions"
                :key="option.value"
                type="button"
                role="radio"
                :aria-checked="mode === option.value"
                :class="['excel-mode-card', { 'excel-mode-card--active': mode === option.value }]"
                :data-mode="option.value"
                @click="onModeChanged(option.value)"
              >
                <FyIcon :path="option.icon" :size="22" class="excel-mode-card__icon" />
                <span class="excel-mode-card__label">{{ option.label }}</span>
                <span class="excel-mode-card__hint">{{ option.hint }}</span>
              </button>
            </div>

            <div v-if="mode === 'BY_COLUMN'" class="excel-mode-note">
              <FyIcon :path="mdiInformationOutline" :size="16" />
              {{ t('exui.mode.noteColumn') }}
            </div>
            <div v-else-if="mode === 'COMPLEX'" class="excel-mode-note">
              <FyIcon :path="mdiInformationOutline" :size="16" />
              {{ t('exui.mode.noteComplex') }}
            </div>

            <template v-if="mode === 'BY_SHEET'">
              <div class="excel-grid-2">
                <v-select
                  v-model="selectedSheets"
                  :items="sheetNames"
                  :label="t('exui.mode.sheets')"
                  multiple
                  chips
                  clearable
                  @update:model-value="invalidateModeConfiguration"
                />
                <v-text-field
                  v-model="filePrefix"
                  :label="t('exui.mode.filePrefix')"
                  @update:model-value="invalidateModeConfiguration"
                />
              </div>
            </template>

            <template v-else-if="mode === 'BY_COLUMN'">
              <div class="excel-grid-2">
                <v-select
                  :model-value="splitSheet"
                  :items="sheetNames"
                  :label="t('exui.mode.sheet')"
                  @update:model-value="onSplitSheetChanged"
                />
                <v-select
                  v-model="splitColumn"
                  :items="columnsForSplitSheet"
                  :label="t('exui.mode.column')"
                  :disabled="!splitSheet"
                  @update:model-value="invalidateModeConfiguration"
                />
              </div>
              <v-text-field
                v-model="filePrefix"
                :label="t('exui.mode.filePrefix')"
                class="mt-4"
                @update:model-value="invalidateModeConfiguration"
              />
            </template>

            <template v-else-if="mode === 'COMPLEX'">
              <div class="fy-responsive-table">
              <v-table density="compact" class="excel-rules-table">
                <thead>
                  <tr>
                    <th>{{ t('exui.complex.sheet') }}</th>
                    <th>{{ t('exui.complex.headerRow') }}</th>
                    <th>{{ t('exui.complex.column') }}</th>
                    <th>{{ t('exui.complex.copyEntire') }}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(entry, i) in complexEntries" :key="i">
                    <td>
                      <v-select
                        v-model="entry.sheetName"
                        :items="sheetNames"
                        density="compact"
                        hide-details
                        @update:model-value="invalidateModeConfiguration"
                      />
                    </td>
                    <td>
                      <v-text-field
                        v-model.number="entry.headerIndex"
                        type="number"
                        density="compact"
                        hide-details
                        :disabled="entry.copyAll"
                        @update:model-value="invalidateModeConfiguration"
                      />
                    </td>
                    <td>
                      <v-text-field
                        v-model.number="entry.columnIndex"
                        type="number"
                        density="compact"
                        hide-details
                        :disabled="entry.copyAll"
                        @update:model-value="invalidateModeConfiguration"
                      />
                    </td>
                    <td>
                      <v-checkbox
                        v-model="entry.copyAll"
                        density="compact"
                        hide-details
                        @update:model-value="onCopyAllToggle(entry)"
                      />
                    </td>
                    <td>
                      <v-btn variant="text" size="small" @click="removeComplexEntry(i)">
                        {{ t('exui.complex.remove') }}
                      </v-btn>
                    </td>
                  </tr>
                </tbody>
              </v-table>
              </div>
              <div class="excel-complex-foot">
                <v-btn variant="tonal" color="tertiary" rounded="pill" @click="addComplexEntry">
                  <template #prepend><FyIcon :path="mdiPlus" :size="15" /></template>
                  {{ t('exui.complex.addRule') }}
                </v-btn>
              </div>
              <v-text-field
                v-model="filePrefix"
                :label="t('exui.mode.filePrefix')"
                class="mt-4"
                @update:model-value="invalidateModeConfiguration"
              />
            </template>
          </template>

          <template #output>
            <div class="excel-card-title">{{ t('exui.output.cardTitle') }}</div>
            <div class="excel-output-grid">
              <div class="excel-panel">
                <div class="excel-panel__title">{{ t('exui.output.configTitle') }}</div>
                <div class="excel-kv">
                  <span class="excel-kv__k">{{ t('exui.output.mode') }}</span>
                  <span class="excel-kv__v">{{ modeLabel }}</span>
                </div>
                <div v-if="configDetails.length" class="excel-kv">
                  <span class="excel-kv__k">{{ t('exui.output.rules') }}</span>
                  <span class="excel-kv__v">
                    <span class="excel-chip-row">
                      <span v-for="(detail, i) in configDetails" :key="i" class="excel-chip">
                        {{ detail }}
                      </span>
                    </span>
                  </span>
                </div>
                <div class="excel-kv">
                  <span class="excel-kv__k">{{ t('exui.output.expectedFiles') }}</span>
                  <span class="excel-kv__v">
                    <strong
                      v-if="!estimating && estimatedFileCount !== null"
                      class="excel-count"
                    >{{ estimatedFileCount }}</strong>
                    <span v-else class="text-medium-emphasis">
                      {{ estimating ? t('exui.output.estimating') : '—' }}
                    </span>
                  </span>
                </div>
                <div class="excel-kv">
                  <span class="excel-kv__k">{{ t('exui.output.prefixLabel') }}</span>
                  <span class="excel-kv__v">{{ filePrefix || '—' }}</span>
                </div>
              </div>
              <div class="excel-panel">
                <div class="excel-panel__title">{{ t('exui.output.dirPanel') }}</div>
                <FyDirectoryPicker
                  :model-value="outputDirRef"
                  mode="output"
                  :label="t('exui.output.chooseFolder')"
                  @update:model-value="onOutputPicked"
                />
                <div class="excel-hintbox">
                  <FyIcon
                    :path="platform === 'desktop' ? mdiFolderCheckOutline : mdiDownloadOutline"
                    :size="18"
                  />
                  <span>{{ t(platform === 'desktop' ? 'exui.output.desktopHint' : 'exui.output.webHint') }}</span>
                </div>
              </div>
            </div>
          </template>

          <template #run="{ actions }">
            <span hidden>{{ captureWizardActions(actions) }}</span>
            <div class="excel-run-box">
              <div class="excel-run-box__label">
                {{ running ? t('exui.run.splitting') : t('exui.run.starting') }}
              </div>
              <v-progress-linear
                class="excel-run-box__bar"
                indeterminate
                color="tertiary"
                rounded
                height="8"
              />
              <div class="excel-run-box__sub">
                {{ t('exui.run.detail', sourceFileRef?.name ?? '', modeLabel, outputDirRef?.name ?? '') }}
              </div>
            </div>
          </template>

          <template #complete>
            <v-alert v-if="runError" :icon="false" type="error" density="compact" class="mb-4">
              <template #prepend><FyIcon :path="mdiAlertCircleOutline" :size="20" class="mr-3" /></template>
              {{ runError }}
            </v-alert>
            <div v-if="result" class="excel-done-alert">
              <FyIcon :path="mdiCheckCircleOutline" :size="22" />
              <span>
                <strong>{{ t('exui.complete.title') }}</strong>
                · {{ t('exui.complete.written', result.fileCount, outputDirRef?.name ?? t('exui.complete.outputFolderFallback')) }}
              </span>
            </div>
            <div class="excel-done-grid">
              <div class="excel-panel">
                <div class="excel-panel__title">{{ t('exui.complete.filesPanel') }}</div>
                <ul v-if="result" class="excel-file-list">
                  <li v-for="f in result.files" :key="f">
                    <FyIcon :path="mdiFileExcelOutline" :size="16" class="excel-file-icon" />
                    {{ f }}
                  </li>
                </ul>
              </div>
              <div class="excel-panel">
                <div class="excel-panel__title">{{ t('exui.complete.actionsPanel') }}</div>
                <div class="excel-kv">
                  <span class="excel-kv__k">{{ t('exui.complete.outputFolderLabel') }}</span>
                  <span class="excel-kv__v">
                    {{ outputDirRef?.name ?? t('exui.complete.outputFolderFallback') }}
                  </span>
                </div>
                <div class="excel-done-actions">
                  <v-btn
                    v-if="result && outputDirRef && platform === 'web'"
                    color="tertiary"
                    variant="flat"
                    rounded="pill"
                    :loading="downloading"
                    :disabled="downloading"
                    data-action="export-results"
                    @click="downloadResult"
                  >
                    <template #prepend><FyIcon :path="mdiDownloadOutline" :size="16" /></template>
                    {{ t('exui.complete.download') }}
                  </v-btn>
                  <v-btn variant="text" rounded="pill" @click="restartWizard">
                    <template #prepend><FyIcon :path="mdiArrowLeft" :size="16" /></template>
                    {{ t('exui.complete.restart') }}
                  </v-btn>
                </div>
              </div>
            </div>
          </template>
        </FyStepWizard>
    </FyPluginPage>
  </FyPluginShell>
</template>

<style scoped>
main.excel-splitter {
  min-width: 0;
}

/* ===== Stepper header — Vuetify v-stepper look (desktop path only; the wizard's
   compact mobile path keeps its own layout). Green (tertiary) marks completed steps. ===== */
.excel-avatar {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 28px;
  height: 28px;
  font-size: 12.5px;
  font-weight: 500;
  color: rgb(var(--v-theme-secondary));
  background: rgb(var(--v-theme-surface-variant));
  border-radius: 50%;
  transition: background-color 0.18s ease, color 0.18s ease;
}
.excel-avatar--complete {
  color: rgb(var(--v-theme-on-tertiary));
  background: rgb(var(--v-theme-tertiary));
}
.excel-avatar--active,
.excel-avatar--validating {
  color: rgb(var(--v-theme-on-primary));
  background: rgb(var(--v-theme-primary));
}
.excel-avatar--error {
  color: rgb(var(--v-theme-on-error));
  background: rgb(var(--v-theme-error));
}
.excel-step-title {
  font-size: 0.875rem;
  line-height: 1.45;
  color: rgb(var(--v-theme-secondary));
}
.excel-splitter :deep(.fy-wizard__step-button[data-status='complete']) .excel-step-title {
  color: rgb(var(--v-theme-tertiary));
  font-weight: 600;
}
.excel-splitter :deep(
    .fy-wizard__step-button[data-status='active'] .excel-step-title,
    .fy-wizard__step-button[data-status='validating'] .excel-step-title,
    .fy-wizard__step-button[data-status='error'] .excel-step-title
  ) {
  color: rgb(var(--v-theme-on-surface));
  font-weight: 700;
}
.excel-splitter :deep(
    .fy-wizard__step-button[data-status='pending'] .excel-step-title,
    .fy-wizard__step-button[data-status='skipped'] .excel-step-title
  ) {
  opacity: 0.75;
}
.excel-sr-only {
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

@media (min-width: 721px) {
  main.excel-splitter {
    padding: 20px 24px 40px;
  }

  .excel-splitter :deep(.fy-step-wizard) {
    gap: 20px;
  }

  .excel-splitter :deep(.fy-step-wizard .fy-wizard__desktop-path) {
    display: flex;
    padding: 6px 4px 16px;
    box-shadow: inset 0 -1px 0 rgba(var(--v-border-color), var(--v-border-opacity));
  }
  .excel-splitter :deep(.fy-step-wizard .fy-wizard__desktop-list) {
    display: flex;
    align-items: center;
    width: 100%;
    margin: 0;
    padding: 0;
  }
  .excel-splitter :deep(.fy-step-wizard .fy-wizard__step) {
    display: flex;
    flex: 1 1 auto;
    align-items: center;
    min-width: 0;
  }
  .excel-splitter :deep(.fy-step-wizard .fy-wizard__step:last-child) {
    flex: 0 0 auto;
  }
  /* Connector line between steps; turns green once the step on its left completes. */
  .excel-splitter :deep(.fy-step-wizard .fy-wizard__step:not(:last-child))::after {
    content: "";
    flex: 1 1 0;
    height: 1px;
    margin: 0 2px;
    background: rgba(var(--v-border-color), var(--v-border-opacity));
  }
  .excel-splitter :deep(
      .fy-step-wizard .fy-wizard__step:has(> .fy-wizard__step-button[data-status='complete'])
    )::after {
    background: rgb(var(--v-theme-tertiary));
  }

  /* Strip the toolchain's card chrome off each step button. */
  .excel-splitter :deep(.fy-step-wizard .fy-wizard__step-button) {
    display: inline-flex;
    align-items: center;
    gap: 12px;
    width: auto;
    min-height: 0;
    padding: 10px 14px;
    text-align: start;
    background: transparent;
    border: 0;
    border-radius: 6px;
    box-shadow: none;
  }
  .excel-splitter :deep(.fy-step-wizard .fy-wizard__step-button:not(:disabled):hover) {
    background: rgba(var(--v-theme-on-surface), 0.05);
  }
  .excel-splitter :deep(.fy-step-wizard .fy-wizard__step-button--active) {
    background: transparent;
    border-color: transparent;
    box-shadow: none;
  }
  .excel-splitter :deep(.fy-step-wizard .fy-wizard__step-button:disabled) {
    cursor: default;
    opacity: 0.75;
  }
  .excel-splitter :deep(
      .fy-step-wizard .fy-wizard__desktop-path .fy-wizard__status-icon,
      .fy-step-wizard .fy-wizard__desktop-path .fy-wizard__step-number,
      .fy-step-wizard .fy-wizard__desktop-path .fy-wizard__status-label
    ) {
    display: none;
  }
  .excel-splitter :deep(.fy-step-wizard .fy-wizard__step-copy) {
    display: flex;
    align-items: center;
    gap: 12px;
    min-width: 0;
  }

  /* Pill-shaped wizard action buttons (confirmed design). Vuetify's rounded-* utilities
     (applied via the toolchain's global Vuetify defaults) carry !important, so matching
     them requires !important here. */
  .excel-splitter :deep(.fy-wizard__actions .v-btn),
  .excel-splitter :deep(.excel-drop-zone .fy-file-picker .v-btn) {
    padding-inline: 20px;
    border-radius: 999px !important;
  }
}

/* ===== Shared screen pieces ===== */
.excel-card-title {
  margin-bottom: 16px;
  font-size: 0.9375rem;
  font-weight: 600;
}
.excel-grid-2 {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}
.excel-panel {
  padding: 16px 18px;
  background: rgb(var(--v-theme-surface));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 10px;
}
.excel-panel__title {
  margin-bottom: 12px;
  font-size: 0.8125rem;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: rgb(var(--v-theme-secondary));
}
.excel-kv {
  display: flex;
  gap: 12px;
  padding: 5px 0;
  font-size: 0.875rem;
}
.excel-kv__k {
  flex: 0 0 96px;
  color: rgb(var(--v-theme-secondary));
}
.excel-kv__v {
  min-width: 0;
}
.excel-count {
  font-size: 1rem;
  font-weight: 700;
  color: rgb(var(--v-theme-tertiary));
}

/* ===== Source ===== */
.excel-source-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: 18px;
  align-items: stretch;
}
.excel-drop-zone {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 22px 24px;
  background: rgba(var(--v-theme-surface), 0.6);
  border: 2px dashed rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 12px;
  transition: border-color 0.15s ease;
}
.excel-drop-zone:hover {
  border-color: rgba(var(--v-theme-on-surface), 0.45);
}
.excel-drop-zone__icon {
  flex: 0 0 auto;
  color: rgb(var(--v-theme-tertiary));
}
.excel-drop-zone__copy {
  display: grid;
  flex: 1 1 auto;
  gap: 3px;
  min-width: 0;
}
.excel-drop-zone__title {
  font-size: 0.9375rem;
  font-weight: 600;
}
.excel-drop-zone__sub {
  font-size: 0.78rem;
  color: rgb(var(--v-theme-secondary));
}
.excel-drop-zone__picker {
  margin-left: auto;
}
/* Blend the picker's selected-file row into the dashed zone. */
.excel-splitter :deep(.excel-drop-zone__picker .fy-picker__selection) {
  width: auto;
  max-width: 320px;
  min-height: 0;
  padding: 4px 6px 4px 8px;
  background: transparent;
  border: 0;
}
.excel-source-tips {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  padding: 16px 18px;
  font-size: 0.8125rem;
  line-height: 1.7;
  color: rgb(var(--v-theme-on-tertiary-container));
  background: rgb(var(--v-theme-tertiary-container));
  border-radius: 12px;
}
.excel-source-tips strong {
  margin-bottom: 2px;
  font-size: 0.84rem;
}
.excel-tip {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.excel-tip__icon {
  flex: 0 0 auto;
  margin-top: 3px;
}

/* ===== Mode ===== */
.excel-mode-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}
.excel-mode-card {
  display: grid;
  grid-template-areas: "icon label" "icon hint";
  grid-template-columns: auto 1fr;
  align-items: center;
  column-gap: 12px;
  padding: 14px 16px;
  text-align: start;
  cursor: pointer;
  background: rgb(var(--v-theme-surface-container-low));
  border: 2px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: var(--fy-radius-md, 10px);
  color: rgb(var(--v-theme-on-surface));
  transition: border-color 0.15s ease, background 0.15s ease;
}
.excel-mode-card:hover { border-color: rgba(var(--v-theme-primary), 0.5); }
.excel-mode-card:focus-visible {
  outline: 2px solid rgb(var(--v-theme-primary));
  outline-offset: 2px;
}
.excel-mode-card__icon { grid-area: icon; color: rgb(var(--v-theme-secondary)); }
.excel-mode-card__label { grid-area: label; font-weight: 600; font-size: 0.9375rem; }
.excel-mode-card__hint {
  grid-area: hint;
  color: rgb(var(--v-theme-secondary));
  font-size: 0.75rem;
}
.excel-mode-card--active {
  border-color: rgb(var(--v-theme-primary));
  background: rgba(var(--v-theme-primary), 0.08);
}
.excel-mode-card--active .excel-mode-card__icon { color: rgb(var(--v-theme-tertiary)); }
.excel-mode-note {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  padding: 9px 14px;
  font-size: 0.8125rem;
  color: rgb(var(--v-theme-tertiary));
  background: rgba(var(--v-theme-tertiary), 0.1);
  border: 1px solid rgba(var(--v-theme-tertiary), 0.35);
  border-radius: 10px;
}
.excel-rules-table :deep(th:nth-child(1)) { width: 30%; }
.excel-rules-table :deep(th:nth-child(2)) { width: 16%; }
.excel-rules-table :deep(th:nth-child(3)) { width: 16%; }
.excel-rules-table :deep(th:nth-child(4)) { width: 14%; }
.excel-complex-foot {
  display: flex;
  justify-content: center;
  margin-top: 14px;
}

/* ===== Output ===== */
.excel-output-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 18px;
}
.excel-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.excel-chip {
  padding: 3px 10px;
  font-size: 0.75rem;
  color: rgb(var(--v-theme-tertiary));
  background: rgba(var(--v-theme-tertiary), 0.12);
  border: 1px solid rgba(var(--v-theme-tertiary), 0.35);
  border-radius: 999px;
}
.excel-hintbox {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 14px;
  padding: 12px 14px;
  font-size: 0.8125rem;
  line-height: 1.6;
  color: rgb(var(--v-theme-on-tertiary-container));
  background: rgb(var(--v-theme-tertiary-container));
  border-radius: 10px;
}

/* ===== Run ===== */
.excel-run-box {
  display: grid;
  justify-items: center;
  gap: 18px;
  padding: 46px 0 40px;
  text-align: center;
}
.excel-run-box__label {
  font-size: 0.9375rem;
  font-weight: 600;
}
.excel-run-box__bar {
  width: min(560px, 100%);
}
.excel-run-box__sub {
  font-size: 0.8125rem;
  color: rgb(var(--v-theme-secondary));
}

/* ===== Complete ===== */
.excel-done-alert {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  margin-bottom: 18px;
  font-size: 0.875rem;
  color: rgb(var(--v-theme-on-tertiary-container));
  background: rgb(var(--v-theme-tertiary-container));
  border-radius: 12px;
}
.excel-done-alert strong {
  font-size: 0.9375rem;
}
.excel-done-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: 18px;
}
.excel-file-list {
  max-height: 220px;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}
.excel-file-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 6px;
  font-size: 0.84rem;
  border-bottom: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
}
.excel-file-list li:last-child { border-bottom: 0; }
.excel-file-icon {
  flex: 0 0 auto;
  color: rgb(var(--v-theme-tertiary));
}
.excel-done-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 14px;
}

@media (max-width: 600px) {
  .excel-mode-cards { grid-template-columns: 1fr; }
  .excel-drop-zone { flex-wrap: wrap; }
  .excel-kv {
    flex-direction: column;
    gap: 2px;
    align-items: flex-start;
  }
  .excel-kv__k { flex-basis: auto; }
}

@container fy-plugin-page (max-width: 960px) {
  .excel-source-grid,
  .excel-output-grid,
  .excel-done-grid,
  .excel-grid-2 { grid-template-columns: 1fr; }
}
@container fy-plugin-page (max-width: 600px) {
  .excel-mode-cards { grid-template-columns: 1fr; }
  .excel-drop-zone { flex-wrap: wrap; }
}

@media (prefers-reduced-motion: reduce) {
  .excel-avatar { transition: none; }
}
</style>
