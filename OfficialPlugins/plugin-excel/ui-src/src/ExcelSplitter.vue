<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import {
  mdiAlertCircleOutline,
  mdiCheckCircleOutline,
  mdiDownloadOutline,
  mdiFolderCheckOutline,
  mdiFormatColumns,
  mdiInformationOutline,
  mdiSitemapOutline,
  mdiTableMultiple,
} from '@mdi/js'
import {
  createWizardStates,
  FyDirectoryPicker,
  FyFilePicker,
  FyIcon,
  FyStepWizard,
  invalidateWizardStates,
  useFengYuClient,
} from '@infinia/plugin-ui'
import type {
  FyWizardSnapshot,
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

const client = useFengYuClient()

type SplitMode = 'BY_SHEET' | 'BY_COLUMN' | 'COMPLEX'

interface ModeOption {
  value: SplitMode
  label: string
  hint: string
  icon: string
}

const modeOptions: ModeOption[] = [
  { value: 'BY_SHEET', label: 'By sheet', hint: 'One file per sheet', icon: mdiTableMultiple },
  { value: 'BY_COLUMN', label: 'By column', hint: 'One file per value in a column', icon: mdiFormatColumns },
  { value: 'COMPLEX', label: 'Complex', hint: 'Multiple rules combined', icon: mdiSitemapOutline },
]

interface AnalyzeResponse {
  success: boolean
  summary?: string
  sheets?: Record<string, Record<string, string>>
  error?: string
}
interface ConfigureResponse { success: boolean; summary?: string; error?: string }
interface EstimateResponse { success: boolean; summary?: string; fileCount?: number; exact?: boolean; error?: string }
interface SplitResponse {
  success: boolean
  summary?: string
  fileCount?: number
  files?: string[]
  error?: string
}
interface ComplexEntryRow {
  fieldName: string
  sheetName: string
  headerIndex: number
  columnIndex: number
  copyAll: boolean
}

const steps: FyWizardStep[] = [
  { value: 'source', title: 'Source' },
  { value: 'mode', title: 'Mode' },
  { value: 'output', title: 'Output' },
  { value: 'run', title: 'Run' },
]
const activeStep = ref('source')
const wizardStates = ref<Record<string, FyWizardStepState>>(
  createWizardStates(steps, 'source'),
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

// Step 1 — source. sourceFileRef is the host FileRef (resolved to an absolute path
// by PluginProcessManager before reaching the worker).
const sourceFileRef = ref<FileRef | null>(null)
const session = ref<string | null>(null)
const analyzing = ref(false)
const analyzeError = ref<string | null>(null)
const sheets = ref<Record<string, Record<string, string>> | null>(null)

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

const sheetNames = computed<string[]>(() => (sheets.value ? Object.keys(sheets.value) : []))

function columnsForSheet(sheetName: string | null): string[] {
  if (!sheetName || !sheets.value) return []
  return Object.values(sheets.value[sheetName] ?? {})
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
  void client.notify(msg)
}

function reportPersistenceFailure(): void {
  if (persistenceWarningShown) return
  persistenceWarningShown = true
  notifyErr('Unable to save wizard progress')
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
  const changedIndex = steps.findIndex((step) => step.value === changedStep)
  if (changedIndex < 0) return
  const sourceSnapshot = latestSnapshot.value
  const allowed = new Set(steps.slice(0, changedIndex + 1).map((step) => step.value))
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
    return { valid: false, message: 'Choose an Excel file' }
  }
  const source = sourceFileRef.value
  const sessionId = session.value
  const generation = ++analyzeGeneration
  analyzing.value = true
  analyzeError.value = null
  try {
    abortIfStale(signal)
    const res = await client.invoke<AnalyzeResponse>('analyze', {
      session: sessionId,
      sourceFile: source,
    }, { signal })
    abortIfStale(signal)
    if (sourceFileRef.value?.id !== source.id || session.value !== sessionId) {
      throw new DOMException('Aborted', 'AbortError')
    }
    if (!res.success) {
      const msg = responseError(res, 'Analyze failed')
      analyzeError.value = msg
      return { valid: false, message: msg }
    }
    sheets.value = res.sheets ?? {}
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
    const res = await client.invoke<EstimateResponse>('estimate', { session: expectedSession })
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
      ? `By sheet (${selectedSheets.value.length} selected)`
      : 'By sheet (all)'
    case 'BY_COLUMN': return splitSheet.value && splitColumn.value
      ? `By column: ${splitColumn.value} in ${splitSheet.value}`
      : 'By column'
    case 'COMPLEX': return `Complex (${complexEntries.value.length} rule${complexEntries.value.length === 1 ? '' : 's'})`
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
        ? [`Column “${splitColumn.value}” in sheet “${splitSheet.value}”`]
        : []
    case 'COMPLEX':
      return complexEntries.value.map((entry) => entry.copyAll
        ? `Copy entire sheet “${entry.sheetName}”`
        : `Split sheet “${entry.sheetName}” by column ${entry.columnIndex} (header row ${entry.headerIndex})`)
  }
})

async function validateMode(signal: AbortSignal): Promise<FyWizardValidationResult> {
  if (!session.value) return { valid: false, message: 'Choose an Excel file' }
  if (mode.value === 'BY_COLUMN') {
    if (!splitSheet.value || !splitColumn.value) {
      return { valid: false, message: 'Choose a sheet and column' }
    }
    if (!sheetNames.value.includes(splitSheet.value)) {
      return { valid: false, message: 'Choose a sheet from the analyzed workbook' }
    }
    if (!columnsForSheet(splitSheet.value).includes(splitColumn.value)) {
      return { valid: false, message: 'Choose a column from the analyzed sheet' }
    }
  }
  if (
    mode.value === 'COMPLEX'
    && (complexEntries.value.length === 0 || complexEntries.value.some((entry) => !entry.sheetName))
  ) {
    return { valid: false, message: 'Add at least one complete split rule' }
  }
  if (
    mode.value === 'COMPLEX'
    && complexEntries.value.some((entry) => entry.copyAll
      && (entry.headerIndex !== -1 || entry.columnIndex !== -1))
  ) {
    return { valid: false, message: 'Copy-all rules require both indices to be -1' }
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
    return { valid: false, message: 'Use whole-number indices of 1 or greater' }
  }

  configuring.value = true
  configureError.value = null
  try {
    const args: Record<string, unknown> = {
      session: session.value,
      mode: mode.value,
      filePrefix: filePrefix.value,
    }
    if (mode.value === 'BY_SHEET') {
      if (selectedSheets.value.length > 0) args.selectedSheets = selectedSheets.value
    } else if (mode.value === 'BY_COLUMN') {
      args.splitSheet = splitSheet.value
      args.splitColumn = splitColumn.value
    } else if (mode.value === 'COMPLEX') {
      args.complexEntries = complexEntries.value.map((e) => ({
        fieldName: e.fieldName,
        sheetName: e.sheetName,
        headerIndex: e.headerIndex,
        columnIndex: e.columnIndex,
      }))
    }
    abortIfStale(signal)
    const res = await client.invoke<ConfigureResponse>('configure', args, { signal })
    abortIfStale(signal)
    if (!res.success) {
      const msg = responseError(res, 'Configure failed')
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
    return { valid: false, message: 'Choose an Excel file' }
  }
  if (!outputDirRef.value) return { valid: false, message: 'Choose an output folder' }
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
    const splitArgs: Record<string, unknown> = {
      session: session.value,
      sourceFile: sourceFileRef.value,
      outputDir: outputDirRef.value,
      mode: mode.value,
      filePrefix: filePrefix.value,
    }
    if (mode.value === 'BY_SHEET') {
      if (selectedSheets.value.length > 0) splitArgs.selectedSheets = selectedSheets.value
    } else if (mode.value === 'BY_COLUMN') {
      splitArgs.splitSheet = splitSheet.value
      splitArgs.splitColumn = splitColumn.value
    } else if (mode.value === 'COMPLEX') {
      splitArgs.complexEntries = complexEntries.value.map((e) => ({
        fieldName: e.fieldName,
        sheetName: e.sheetName,
        headerIndex: e.headerIndex,
        columnIndex: e.columnIndex,
      }))
    }
    const res = await client.invoke<SplitResponse>('split', splitArgs, { signal })
    abortIfStale(signal)
    if (!res.success) {
      const msg = responseError(res, 'Split failed')
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
      : { valid: false, message: 'Choose an output folder' }
  }
  if (step === 'run') return runSplit(signal)
  return { valid: false, message: `Unknown wizard step: ${step}` }
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
    ...createWizardStates(steps, 'source'),
    source: { status: 'validating' },
  }
  wizardCompleted.value = false
  const sourceId = record.draft.sourceFileRef?.id ?? ''
  const sessionId = record.draft.sessionId
  const restoredActive = record.wizard.activeStep === 'run'
    ? 'output'
    : record.wizard.activeStep
  const restoredIndex = steps.findIndex((step) => step.value === restoredActive)
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
        ...createWizardStates(steps, 'source'),
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

    if (restoredIndex >= steps.findIndex((step) => step.value === 'output')) {
      const modeResult = await validateMode(controller.signal)
      if (
        controller.signal.aborted
        || generation !== restoreGeneration
        || sourceFileRef.value?.id !== sourceId
        || session.value !== sessionId
      ) return
      if (!modeResult.valid) {
        const states: Record<string, FyWizardStepState> = {
          ...createWizardStates(steps, 'mode'),
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
      const index = steps.findIndex((step) => step.value === value)
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
  <v-app>
    <v-main>
      <v-container class="excel-splitter">
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
          <template #source="{ actions }">
            <v-card variant="flat">
              <v-card-text>
                <div class="excel-source">
                  <FyFilePicker
                    :model-value="sourceFileRef"
                    :extensions="['xlsx', 'xls']"
                    :filters="[{ name: 'Excel', extensions: ['xlsx', 'xls'] }]"
                    label="Choose Excel file"
                    @update:model-value="(ref: FileRef | null) => onFilePicked(ref, actions.next)"
                  />

                  <div v-if="sourceFileRef" class="mt-2 text-body-2">
                    Selected: {{ sourceFileRef.name }}
                  </div>

                  <v-alert v-if="analyzing" :icon="false" type="info" class="mt-3" density="compact">
                    <template #prepend><FyIcon :path="mdiInformationOutline" :size="20" class="mr-3" /></template>
                    Analyzing…
                  </v-alert>
                </div>
              </v-card-text>
            </v-card>
          </template>

          <template #mode>
            <v-card variant="flat">
              <v-card-text>
                <div class="excel-mode-cards" role="radiogroup" aria-label="Split mode">
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

                <v-select
                  v-if="mode === 'BY_SHEET'"
                  v-model="selectedSheets"
                  :items="sheetNames"
                  label="Sheets (leave empty for all)"
                  multiple
                  chips
                  clearable
                  @update:model-value="invalidateModeConfiguration"
                />

                <template v-else-if="mode === 'BY_COLUMN'">
                  <v-select
                    :model-value="splitSheet"
                    :items="sheetNames"
                    label="Sheet"
                    @update:model-value="onSplitSheetChanged"
                  />
                  <v-select
                    v-model="splitColumn"
                    :items="columnsForSplitSheet"
                    label="Column"
                    :disabled="!splitSheet"
                    @update:model-value="invalidateModeConfiguration"
                  />
                </template>

                <template v-else-if="mode === 'COMPLEX'">
                  <v-table density="compact">
                    <thead>
                      <tr>
                        <th>Sheet</th>
                        <th>Header row</th>
                        <th>Column</th>
                        <th>Copy entire sheet</th>
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
                            Remove
                          </v-btn>
                        </td>
                      </tr>
                    </tbody>
                  </v-table>
                  <v-btn class="mt-2" variant="tonal" @click="addComplexEntry">Add rule</v-btn>
                </template>

                <v-text-field
                  v-model="filePrefix"
                  label="Output file prefix (optional)"
                  class="mt-4"
                  @update:model-value="invalidateModeConfiguration"
                />

              </v-card-text>
            </v-card>
          </template>

          <template #output>
            <v-card variant="flat">
              <v-card-text>
                <div class="excel-config-summary mb-4">
                  <div class="text-subtitle-2 mb-1">Split configuration</div>
                  <div class="excel-config-summary__row">
                    <span class="excel-config-summary__label">Mode</span>
                    <span>{{ modeLabel }}</span>
                  </div>
                  <div v-if="configDetails.length" class="excel-config-summary__row">
                    <span class="excel-config-summary__label">Rules</span>
                    <ul class="excel-config-summary__list">
                      <li v-for="(detail, i) in configDetails" :key="i">{{ detail }}</li>
                    </ul>
                  </div>
                  <div class="excel-config-summary__row">
                    <span class="excel-config-summary__label">Expected files</span>
                    <span v-if="estimating" class="d-inline-flex align-center">
                      <v-progress-circular indeterminate size="14" width="2" class="mr-2" />
                      estimating…
                    </span>
                    <strong v-else-if="estimatedFileCount !== null">{{ estimatedFileCount }}</strong>
                    <span v-else class="text-medium-emphasis">—</span>
                  </div>
                </div>

                <FyDirectoryPicker
                  :model-value="outputDirRef"
                  mode="output"
                  label="Choose output folder"
                  @update:model-value="onOutputPicked"
                />
                <div v-if="outputDirRef" class="mt-2 text-body-2">
                  Output: {{ outputDirRef.name }}
                </div>
                <v-alert
                  v-if="platform === 'desktop'"
                  :icon="false"
                  type="info"
                  density="compact"
                  class="mt-3"
                >
                  <template #prepend><FyIcon :path="mdiFolderCheckOutline" :size="20" class="mr-3" /></template>
                  Files are written directly into this folder — no download step needed.
                </v-alert>
                <v-alert v-else :icon="false" type="info" density="compact" class="mt-3">
                  <template #prepend><FyIcon :path="mdiDownloadOutline" :size="20" class="mr-3" /></template>
                  Results are staged in a temporary folder; after the split you can download them as a zip.
                </v-alert>
              </v-card-text>
            </v-card>
          </template>

          <template #run>
            <v-card variant="flat">
              <v-card-text>
                <div v-if="running" class="d-flex align-center">
                  <v-progress-circular indeterminate size="24" class="mr-2" />
                  Splitting…
                </div>

              </v-card-text>
            </v-card>
          </template>

          <template #complete>
            <v-card variant="flat">
              <v-card-text>
                <v-alert v-if="result" :icon="false" type="success" density="compact" class="mb-3">
                  <template #prepend><FyIcon :path="mdiCheckCircleOutline" :size="20" class="mr-3" /></template>
                  {{ result.fileCount }} file(s) written to {{ outputDirRef?.name ?? 'the output folder' }}
                </v-alert>
                <v-alert v-if="runError" :icon="false" type="error" density="compact" class="mb-3">
                  <template #prepend><FyIcon :path="mdiAlertCircleOutline" :size="20" class="mr-3" /></template>
                  {{ runError }}
                </v-alert>
                <div v-if="outputDirRef" class="text-body-2 mb-2">
                  Output folder: {{ outputDirRef.name }}
                </div>
                <v-list v-if="result" density="compact">
                  <v-list-item v-for="f in result.files" :key="f">{{ f }}</v-list-item>
                </v-list>
                <v-btn
                  v-if="result && outputDirRef && platform === 'web'"
                  color="primary"
                  variant="tonal"
                  :loading="downloading"
                  :disabled="downloading"
                  data-action="export-results"
                  @click="downloadResult"
                >
                  Download results
                </v-btn>
              </v-card-text>
            </v-card>
          </template>
        </FyStepWizard>
      </v-container>
    </v-main>
  </v-app>
</template>

<style scoped>
.excel-splitter {
  max-width: 960px;
}
/* Source step: keep the picker and its feedback centered in the card. */
.excel-source {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}
/* Mode step: selectable cards with a clear selected state. */
.excel-mode-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 8px;
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
.excel-mode-card--active .excel-mode-card__icon { color: rgb(var(--v-theme-primary)); }
/* Output step: split-configuration summary + expected file count. */
.excel-config-summary {
  padding: 12px 14px;
  background: rgb(var(--v-theme-surface-container-low));
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: var(--fy-radius-md, 10px);
}
.excel-config-summary__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 2px 0;
  font-size: 0.875rem;
}
.excel-config-summary__label {
  flex: 0 0 120px;
  color: rgb(var(--v-theme-secondary));
}
.excel-config-summary__list {
  margin: 0;
  padding-left: 18px;
  max-height: 140px;
  overflow-y: auto;
}
.excel-config-summary__list li { padding: 1px 0; }
.excel-config-summary__list li + li { margin-top: 2px; }
</style>
