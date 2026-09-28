import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  Chip,
  EmptyState,
  GlowingEffect,
  GoldButton,
  LoadingState,
  PluginHeader,
  StatusBar,
  StatusChip,
  StepWizard,
  createWizardStates,
  invalidateWizardStates,
  useFengYuClient,
  useFengYuI18n,
  useFengYuNotify,
  type Environment,
  type FyWizardSnapshot,
  type FyWizardSlotActions,
  type FyWizardStepState,
  type FyWizardValidationResult,
  type StepWizardLabels,
  type StepWizardStep,
  type StatusTone,
} from '@infinia/plugin-ui'
import type { FileRef } from '@infinia/plugin-sdk'
import {
  IconFileSpreadsheet,
  IconHistory,
  IconPaperclip,
  IconPlus,
  IconShieldCheck,
} from '@tabler/icons-react'
import {
  clearExcelWizardRecord,
  loadExcelWizardRecord,
  saveExcelWizardRecord,
  type ExcelWizardDraft,
} from './excelWizardState'
import { createPluginRpc } from './rpc'
import type { ConfigureInput, SplitInput } from './generated/fengyu-rpc'
import { SourceStep } from './steps/SourceStep'
import { ModeStep } from './steps/ModeStep'
import { OutputStep } from './steps/OutputStep'
import { RunStep } from './steps/RunStep'
import type { AnalyzedSheet, ComplexEntryRow, SplitMode, SplitTask, SplitterContext } from './steps/types'

const STEP_ORDER = ['source', 'mode', 'output', 'run'] as const
type StepValue = (typeof STEP_ORDER)[number]

const stepDefinitions = STEP_ORDER.map((value) => ({ value, title: value }))

function invalidateFrom(changedStep: string): string[] {
  const dependencies: Record<string, string[]> = {
    source: ['mode', 'output', 'run'],
    mode: ['output', 'run'],
    output: ['run'],
    run: [],
  }
  return dependencies[changedStep] ?? []
}

/** State whose latest value is also readable synchronously from a ref (async guards read the
 *  ref, render reads the value — the React equivalent of the old Vue component's refs). */
function useRefState<T>(initial: T) {
  const [value, setValue] = useState<T>(initial)
  const ref = useRef<T>(initial)
  const set = useCallback((next: T) => {
    ref.current = next
    setValue(next)
  }, [])
  return [value, set, ref] as const
}

function errMsg(err: unknown): string {
  if (err && typeof err === 'object') {
    const value = err as Record<string, unknown>
    if (typeof value.summary === 'string' && value.summary.trim()) return value.summary
    if (typeof value.message === 'string' && value.message.trim()) return value.message
  }
  return err instanceof Error && err.message ? err.message : String(err)
}

function responseError(response: { error?: string; summary?: string }, fallback: string): string {
  return response.error ?? response.summary ?? fallback
}

function abortIfStale(signal: AbortSignal): void {
  if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
}

/**
 * The stateful Excel Splitter workspace: the 4-step StepWizard (source → mode → output → run)
 * plus the T2 流水线 chrome — stat cards, task history, run settings, permissions, header and
 * statusbar. The wizard lives in the 拆分任务 nav; the same instance stays mounted (hidden)
 * behind the other nav pages so wizard progress survives page switches.
 */
export function ExcelSplitter({ nav }: { nav: string }) {
  const client = useFengYuClient()
  const rpc = useMemo(() => createPluginRpc(client), [client])
  const i18n = useFengYuI18n()
  // useFengYuI18n() re-binds t every render; keep one stable bound t (the runtime
  // underneath is stable) so every downstream useCallback/useEffect identity holds.
  const tRef = useRef(i18n.t)
  const t = tRef.current
  const { notify } = useFengYuNotify()

  // ── Form state (write-through: refs are the async-readable source of truth) ─────────
  const [sourceFile, setSourceFile, sourceFileRef] = useRefState<FileRef | null>(null)
  const [session, setSession, sessionRef] = useRefState<string | null>(null)
  const [sheets, setSheets, sheetsRef] = useRefState<AnalyzedSheet[] | null>(null)
  const [mode, setModeValue, modeRef] = useRefState<SplitMode>('BY_SHEET')
  const [selectedSheets, setSelectedSheets, selectedSheetsRef] = useRefState<string[]>([])
  const [splitSheet, setSplitSheetValue, splitSheetRef] = useRefState<string | null>(null)
  const [splitColumn, setSplitColumnValue, splitColumnRef] = useRefState<string | null>(null)
  const [filePrefix, setFilePrefixValue, filePrefixRef] = useRefState('')
  const [complexEntries, setComplexEntries, complexEntriesRef] = useRefState<ComplexEntryRow[]>([])
  const [outputDir, setOutputDirValue, outputDirRef] = useRefState<FileRef | null>(null)
  const [running, setRunning, runningRef] = useRefState(false)
  const [downloading, setDownloading, downloadingRef] = useRefState(false)

  // ── Display-only async state ────────────────────────────────────────────────────────
  // The wizard's own ErrorState renders step failures it detects; analyzeError/configureError
  // additionally back the step-body error shown when RESTORE fails (the kit's normalize forces
  // a seeded active step to 'active', so a restored failure cannot seed the wizard error state).
  const [analyzeError, setAnalyzeError] = useState<string | null>(null)
  const [configureError, setConfigureError] = useState<string | null>(null)
  const [estimating, setEstimating] = useState(false)
  const [estimatedFileCount, setEstimatedFileCount] = useState<number | null>(null)
  const [runError, setRunError] = useState<string | null>(null)
  const [result, setResult] = useState<{ fileCount: number; files: string[] } | null>(null)
  const [analyzing, setAnalyzing] = useState(false)
  const [configuring, setConfiguring] = useState(false)
  const [environment, setEnvironment] = useState<Environment | null>(null)
  const [platform, setPlatform] = useState<Environment['platform'] | null>(null)
  const [history, setHistory] = useState<SplitTask[]>([])

  // ── Wizard plumbing ─────────────────────────────────────────────────────────────────
  /** Remount seed: bumping `key` replays `snapshot` through StepWizard's mount initializer —
   *  the React-kit equivalent of the old Vue wizard's watched `snapshot` prop (restore,
   *  invalidate-and-return, restart). */
  const [seed, setSeed] = useState<{ key: number; snapshot?: FyWizardSnapshot }>({ key: 0 })
  const [wizardDone, setWizardDoneState] = useState(false)
  const wizardDoneRef = useRef(false)
  const setWizardDone = useCallback((value: boolean) => {
    wizardDoneRef.current = value
    setWizardDoneState(value)
  }, [])

  const wizardActionsRef = useRef<FyWizardSlotActions | null>(null)
  const activeStepRef = useRef<StepValue>('source')
  const latestSnapshotRef = useRef<FyWizardSnapshot | undefined>(undefined)
  const autoAdvanceRef = useRef(false)
  const restoreControllerRef = useRef<AbortController | undefined>(undefined)
  const restoreGenerationRef = useRef(0)
  const restoreValidationRef = useRef<{
    generation: number
    sourceId: string
    sessionId: string
    promise: Promise<FyWizardValidationResult>
  } | undefined>(undefined)
  const validateControllerRef = useRef<AbortController | undefined>(undefined)
  const persistenceWarnedRef = useRef(false)
  const analyzeGenerationRef = useRef(0)
  const estimateGenerationRef = useRef(0)
  const taskSequenceRef = useRef(0)

  const notifyErr = useCallback(
    (message: string) => {
      void notify(message, { tone: 'error' })
    },
    [notify],
  )

  const reportPersistenceFailure = useCallback(() => {
    if (persistenceWarnedRef.current) return
    persistenceWarnedRef.current = true
    notifyErr(t('exui.notify.unableSave'))
  }, [notifyErr, t])

  // ── Derived data ────────────────────────────────────────────────────────────────────
  const sheetNames = useMemo(
    () => (sheets ? sheets.map((sheet) => sheet.name ?? '') : []),
    [sheets],
  )
  /** Sheet names read straight off the ref — restore/validation flows continue past the
   *  analyze await without an intervening render, so they must not wait for the memo. */
  const sheetNamesOf = useCallback(
    () => (sheetsRef.current ?? []).map((sheet) => sheet.name ?? ''),
    [sheetsRef],
  )

  const columnsForSheet = useCallback((sheetName: string | null): string[] => {
    if (!sheetName || !sheetsRef.current) return []
    const sheet = sheetsRef.current.find((entry) => entry.name === sheetName)
    return (sheet?.columns ?? []).map((column) => column.header ?? '')
  }, [sheetsRef])
  const columnsForSplitSheet = useMemo(
    () => columnsForSheet(splitSheetRef.current),
    // splitSheet (the value) drives recomputation; the ref provides the fresh read.
    [columnsForSheet, splitSheet],
  )

  const computeModeLabel = useCallback((): string => {
    switch (modeRef.current) {
      case 'BY_SHEET':
        return selectedSheetsRef.current.length > 0
          ? t('exui.modeLabel.bySheetSelected', selectedSheetsRef.current.length)
          : t('exui.modeLabel.bySheetAll')
      case 'BY_COLUMN':
        return splitSheetRef.current && splitColumnRef.current
          ? t('exui.modeLabel.byColumn', splitColumnRef.current, splitSheetRef.current)
          : t('exui.modeLabel.byColumnPlain')
      case 'COMPLEX':
        return complexEntriesRef.current.length === 1
          ? t('exui.modeLabel.complex', complexEntriesRef.current.length)
          : t('exui.modeLabel.complexPlural', complexEntriesRef.current.length)
    }
  }, [t])
  const modeLabel = computeModeLabel()

  const configDetails = useMemo<string[]>(() => {
    switch (mode) {
      case 'BY_SHEET':
        return selectedSheets.length > 0 ? selectedSheets : sheetNames
      case 'BY_COLUMN':
        return splitSheet && splitColumn
          ? [t('exui.detail.columnInSheet', splitColumn, splitSheet)]
          : []
      case 'COMPLEX':
        return complexEntries.map((entry) => entry.copyAll
          ? t('exui.detail.copyEntireSheet', entry.sheetName)
          : t('exui.detail.splitSheetByColumn', entry.sheetName, entry.columnIndex, entry.headerIndex))
    }
  }, [mode, selectedSheets, sheetNames, splitSheet, splitColumn, complexEntries, t])

  // ── Snapshot persistence (identical contract to the Vue version) ────────────────────
  const currentDraft = useCallback((): ExcelWizardDraft => {
    return {
      sourceFileRef: sourceFileRef.current ? { ...sourceFileRef.current } : null,
      sessionId: sessionRef.current ?? '',
      mode: modeRef.current,
      selectedSheets: [...selectedSheetsRef.current],
      splitSheet: splitSheetRef.current,
      splitColumn: splitColumnRef.current,
      filePrefix: filePrefixRef.current,
      complexEntries: complexEntriesRef.current.map((entry) => ({ ...entry })),
    }
  }, [])

  const persistSnapshot = useCallback((snapshot: FyWizardSnapshot) => {
    latestSnapshotRef.current = snapshot
    if (!sourceFileRef.current || !sessionRef.current) return
    const saved = saveExcelWizardRecord(sessionStorage, {
      version: 1,
      wizard: snapshot,
      draft: currentDraft(),
    })
    if (!saved) reportPersistenceFailure()
  }, [currentDraft, reportPersistenceFailure])

  /** Snapshot that returns navigation to `changedStep` with every downstream step pending. */
  const buildResetSnapshot = useCallback((changedStep: string): FyWizardSnapshot => {
    const changedIndex = STEP_ORDER.indexOf(changedStep as StepValue)
    if (changedIndex < 0) throw new Error(t('exui.validation.unknownStep', changedStep))
    const sourceSnapshot = latestSnapshotRef.current
    const allowed = new Set<string>(STEP_ORDER.slice(0, changedIndex + 1))
    const visitedPath = (sourceSnapshot?.visitedPath ?? []).filter((step) => allowed.has(step))
    if (!visitedPath.includes(changedStep)) visitedPath.push(changedStep)
    const states = invalidateWizardStates(
      sourceSnapshot?.states ?? createWizardStates(stepDefinitions, changedStep),
      invalidateFrom(changedStep),
    )
    return {
      version: 1,
      activeStep: changedStep,
      visitedPath,
      states: { ...states, [changedStep]: { status: 'active' } as FyWizardStepState },
      completed: false,
    }
  }, [t])

  const applySeed = useCallback((snapshot: FyWizardSnapshot | undefined, persist: boolean) => {
    setSeed((previous) => ({ key: previous.key + 1, snapshot }))
    latestSnapshotRef.current = snapshot
    if (persist && snapshot) persistSnapshot(snapshot)
  }, [persistSnapshot])

  // ── Invalidation ────────────────────────────────────────────────────────────────────
  const invalidateDependencies = useCallback((changedStep: string) => {
    if (changedStep === 'source') setConfigureError(null)
    if (changedStep !== 'run') setRunError(null)
    setResult(null)
    setWizardDone(false)
    if (changedStep === 'source' || changedStep === 'mode') setOutputDirValue(null)
    const snapshot = buildResetSnapshot(changedStep)
    if (activeStepRef.current === changedStep && !wizardDoneRef.current) {
      // Same-step edit: invalidate downstream states in place (no remount — keeps input focus).
      latestSnapshotRef.current = snapshot
      wizardActionsRef.current?.invalidate(changedStep)
      persistSnapshot(snapshot)
    } else {
      // Jump back (or completed → reopen): remount the wizard at the changed step.
      applySeed(snapshot, true)
    }
  }, [applySeed, buildResetSnapshot, persistSnapshot, setOutputDirValue, setWizardDone])

  const invalidateModeConfiguration = useCallback(() => {
    setConfigureError(null)
    setEstimatedFileCount(null)
    invalidateDependencies('mode')
  }, [invalidateDependencies])

  // ── Restore ─────────────────────────────────────────────────────────────────────────
  const cancelRestore = useCallback(() => {
    restoreGenerationRef.current += 1
    restoreControllerRef.current?.abort()
    restoreControllerRef.current = undefined
    restoreValidationRef.current = undefined
  }, [])

  const runSourceValidation = useCallback(async (signal: AbortSignal): Promise<FyWizardValidationResult> => {
    if (!sourceFileRef.current || !sessionRef.current) {
      return { valid: false, message: t('exui.validation.chooseExcelFile') }
    }
    const source = sourceFileRef.current
    const sessionId = sessionRef.current
    const generation = ++analyzeGenerationRef.current
    setAnalyzing(true)
    setAnalyzeError(null)
    try {
      abortIfStale(signal)
      const res = await rpc.analyze({
        session: sessionId,
        sourceFile: source as unknown as string,
      }, { signal })
      abortIfStale(signal)
      if (sourceFileRef.current?.id !== source.id || sessionRef.current !== sessionId) {
        throw new DOMException('Aborted', 'AbortError')
      }
      if (!res.success) {
        const message = responseError(res, t('exui.fallback.analyzeFailed'))
        setAnalyzeError(message)
        return { valid: false, message }
      }
      setSheets(res.sheets ?? [])
      return { valid: true }
    } catch (error) {
      if (signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) throw error
      const message = errMsg(error)
      setAnalyzeError(message)
      return { valid: false, message }
    } finally {
      if (generation === analyzeGenerationRef.current) setAnalyzing(false)
    }
  }, [rpc, t])

  /** Pulls the expected output-file count from the worker after configure. Non-fatal: any
   *  error just leaves the count hidden on the Output step. Guarded against stale mode
   *  changes. No AbortSignal — it intentionally outlives the Mode step validation. */
  const refreshEstimate = useCallback(async (expectedSession: string, expectedMode: SplitMode): Promise<void> => {
    const generation = ++estimateGenerationRef.current
    setEstimating(true)
    try {
      const res = await rpc.estimate({ session: expectedSession })
      if (generation !== estimateGenerationRef.current) return
      if (sessionRef.current !== expectedSession || modeRef.current !== expectedMode) return
      setEstimatedFileCount(res.success && typeof res.fileCount === 'number' ? res.fileCount : null)
    } catch {
      if (generation === estimateGenerationRef.current) setEstimatedFileCount(null)
    } finally {
      if (generation === estimateGenerationRef.current) setEstimating(false)
    }
  }, [rpc])

  const validateModeBody = useCallback(async (signal: AbortSignal): Promise<FyWizardValidationResult> => {
    // Local (pre-RPC) checks. Set configureError on every failure so the step body can show it
    // when the failure comes from RESTORE (the wizard rail cannot seed an error status).
    const localCheck = (): FyWizardValidationResult | undefined => {
      if (modeRef.current === 'BY_COLUMN') {
        if (!splitSheetRef.current || !splitColumnRef.current) {
          return { valid: false, message: t('exui.validation.chooseSheetAndColumn') }
        }
        if (!sheetNamesOf().includes(splitSheetRef.current)) {
          return { valid: false, message: t('exui.validation.chooseSheetFromWorkbook') }
        }
        if (!columnsForSheet(splitSheetRef.current).includes(splitColumnRef.current)) {
          return { valid: false, message: t('exui.validation.chooseColumnFromSheet') }
        }
      }
      if (
        modeRef.current === 'COMPLEX'
        && (complexEntriesRef.current.length === 0 || complexEntriesRef.current.some((entry) => !entry.sheetName))
      ) {
        return { valid: false, message: t('exui.validation.addOneRule') }
      }
      if (
        modeRef.current === 'COMPLEX'
        && complexEntriesRef.current.some((entry) => entry.copyAll
          && (entry.headerIndex !== -1 || entry.columnIndex !== -1))
      ) {
        return { valid: false, message: t('exui.validation.copyAllIndices') }
      }
      if (
        modeRef.current === 'COMPLEX'
        && complexEntriesRef.current.some((entry) => !entry.copyAll && (
          !Number.isInteger(entry.headerIndex)
          || entry.headerIndex < 1
          || !Number.isInteger(entry.columnIndex)
          || entry.columnIndex < 1
        ))
      ) {
        return { valid: false, message: t('exui.validation.positiveIndices') }
      }
      return undefined
    }
    if (!sessionRef.current) return { valid: false, message: t('exui.validation.chooseExcelFile') }
    const sessionId = sessionRef.current
    const localVerdict = localCheck()
    if (localVerdict) {
      setConfigureError(localVerdict.message ?? null)
      return localVerdict
    }

    setConfiguring(true)
    setConfigureError(null)
    try {
      const args: ConfigureInput = {
        session: sessionId,
        mode: modeRef.current,
        filePrefix: filePrefixRef.current,
      }
      if (modeRef.current === 'BY_SHEET') {
        if (selectedSheetsRef.current.length > 0) args.selectedSheets = [...selectedSheetsRef.current]
      } else if (modeRef.current === 'BY_COLUMN') {
        if (splitSheetRef.current) args.splitSheet = splitSheetRef.current
        if (splitColumnRef.current) args.splitColumn = splitColumnRef.current
      } else if (modeRef.current === 'COMPLEX') {
        args.complexEntries = complexEntriesRef.current.map((entry) => ({
          fieldName: entry.fieldName,
          sheetName: entry.sheetName,
          headerIndex: entry.headerIndex,
          columnIndex: entry.columnIndex,
        }))
      }
      abortIfStale(signal)
      const res = await rpc.configure(args, { signal })
      abortIfStale(signal)
      if (!res.success) {
        const message = responseError(res, t('exui.fallback.configureFailed'))
        setConfigureError(message)
        return { valid: false, message }
      }
      // Configure succeeded — refresh the expected file count shown on Output.
      void refreshEstimate(sessionId, modeRef.current)
      return { valid: true }
    } catch (error) {
      if (signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) throw error
      const message = errMsg(error)
      setConfigureError(message)
      return { valid: false, message }
    } finally {
      setConfiguring(false)
    }
  }, [columnsForSheet, refreshEstimate, rpc, sheetNamesOf, t])

  const runSplit = useCallback(async (signal: AbortSignal): Promise<FyWizardValidationResult> => {
    if (!sessionRef.current || !sourceFileRef.current) {
      return { valid: false, message: t('exui.validation.chooseExcelFile') }
    }
    if (!outputDirRef.current) return { valid: false, message: t('exui.validation.chooseOutputFolder') }
    const taskId = `${sessionRef.current}:${++taskSequenceRef.current}`
    const fileName = sourceFileRef.current.name
    const ruleLabel = computeModeLabel()
    const startedAt = Date.now()
    setHistory((previous) => [...previous, {
      id: taskId,
      file: fileName,
      rule: ruleLabel,
      state: 'running',
      fileCount: null,
      elapsedMs: null,
    }])
    setRunning(true)
    setRunError(null)
    setResult(null)
    try {
      abortIfStale(signal)
      // The split call carries the full split config so the worker can re-apply it. The host tears
      // down and relaunches a plugin worker whenever its file-grant version changes — picking the
      // output folder on the Output step grants the output dir and bumps that version, so the worker
      // serving `split` is a fresh process that never saw the earlier `configure`. Without re-sending
      // the config, split falls back to the default BY_SHEET mode and just copies the source file.
      const splitArgs: SplitInput = {
        session: sessionRef.current,
        sourceFile: sourceFileRef.current as unknown as string,
        outputDir: outputDirRef.current as unknown as string,
        mode: modeRef.current,
        filePrefix: filePrefixRef.current,
      }
      if (modeRef.current === 'BY_SHEET') {
        if (selectedSheetsRef.current.length > 0) splitArgs.selectedSheets = [...selectedSheetsRef.current]
      } else if (modeRef.current === 'BY_COLUMN') {
        if (splitSheetRef.current) splitArgs.splitSheet = splitSheetRef.current
        if (splitColumnRef.current) splitArgs.splitColumn = splitColumnRef.current
      } else if (modeRef.current === 'COMPLEX') {
        splitArgs.complexEntries = complexEntriesRef.current.map((entry) => ({
          fieldName: entry.fieldName,
          sheetName: entry.sheetName,
          headerIndex: entry.headerIndex,
          columnIndex: entry.columnIndex,
        }))
      }
      const res = await rpc.split(splitArgs, { signal })
      abortIfStale(signal)
      if (!res.success) {
        const message = responseError(res, t('exui.fallback.splitFailed'))
        setRunError(message)
        setHistory((previous) => previous.map((task) => task.id === taskId
          ? { ...task, state: 'failed' as const, elapsedMs: Date.now() - startedAt }
          : task))
        return { valid: false, message }
      }
      setResult({ fileCount: res.fileCount ?? 0, files: res.files ?? [] })
      setHistory((previous) => previous.map((task) => task.id === taskId
        ? { ...task, state: 'done' as const, fileCount: res.fileCount ?? 0, elapsedMs: Date.now() - startedAt }
        : task))
      return { valid: true }
    } catch (error) {
      if (signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) throw error
      const message = errMsg(error)
      setRunError(message)
      setHistory((previous) => previous.map((task) => task.id === taskId
        ? { ...task, state: 'failed' as const, elapsedMs: Date.now() - startedAt }
        : task))
      return { valid: false, message }
    } finally {
      setRunning(false)
    }
  }, [computeModeLabel, rpc, t])

  /** One AbortController per wizard validation; a new validation or unmount aborts the
   *  previous in-flight call (the React-kit StepWizard has no built-in validate signal). */
  const nextValidateSignal = useCallback((): AbortSignal => {
    validateControllerRef.current?.abort()
    const controller = new AbortController()
    validateControllerRef.current = controller
    return controller.signal
  }, [])

  const validateSourceStep = useCallback(async (): Promise<FyWizardValidationResult> => {
    const pending = restoreValidationRef.current
    if (
      pending
      && pending.sourceId === sourceFileRef.current?.id
      && pending.sessionId === sessionRef.current
    ) {
      // Share the in-flight restore analysis so Next never fires a second analyze.
      const shared = await pending.promise
      if (!shared.valid && shared.message) notifyErr(shared.message)
      return shared
    }
    const verdict = await runSourceValidation(nextValidateSignal())
    if (!verdict.valid && verdict.message) notifyErr(verdict.message)
    return verdict
  }, [notifyErr, nextValidateSignal, runSourceValidation])

  const validateModeStep = useCallback(async (): Promise<FyWizardValidationResult> => {
    const verdict = await validateModeBody(nextValidateSignal())
    if (!verdict.valid && verdict.message) notifyErr(verdict.message)
    return verdict
  }, [nextValidateSignal, notifyErr, validateModeBody])

  const validateOutputStep = useCallback((): FyWizardValidationResult => {
    const verdict = outputDirRef.current
      ? { valid: true }
      : { valid: false, message: t('exui.validation.chooseOutputFolder') }
    if (!verdict.valid && verdict.message) notifyErr(verdict.message)
    return verdict
  }, [notifyErr, t])

  const validateRunStep = useCallback(async (): Promise<FyWizardValidationResult> => {
    const verdict = await runSplit(nextValidateSignal())
    if (!verdict.valid && verdict.message) notifyErr(verdict.message)
    return verdict
  }, [nextValidateSignal, notifyErr, runSplit])

  // ── User actions ────────────────────────────────────────────────────────────────────
  const pickSource = useCallback((ref: FileRef | null) => {
    cancelRestore()
    if (!clearExcelWizardRecord(sessionStorage)) reportPersistenceFailure()
    setSourceFile(ref)
    setSession(null)
    setSheets(null)
    setAnalyzeError(null)
    setOutputDirValue(null)
    invalidateDependencies('source')
    if (ref) {
      setSession(crypto.randomUUID())
      // Auto-advance Source → Mode once the re-seeded wizard commits (effect below calls next()).
      autoAdvanceRef.current = true
    }
  }, [cancelRestore, invalidateDependencies, reportPersistenceFailure])

  const pickOutput = useCallback((ref: FileRef | null) => {
    setOutputDirValue(ref)
    invalidateDependencies('output')
  }, [invalidateDependencies])

  const changeMode = useCallback((value: SplitMode) => {
    setModeValue(value)
    invalidateModeConfiguration()
  }, [invalidateModeConfiguration])

  const toggleSheet = useCallback((name: string) => {
    const current = selectedSheetsRef.current
    setSelectedSheets(current.includes(name) ? current.filter((sheet) => sheet !== name) : [...current, name])
    invalidateModeConfiguration()
  }, [invalidateModeConfiguration])

  const changeSplitSheet = useCallback((name: string) => {
    setSplitSheetValue(name || null)
    setSplitColumnValue(null)
    invalidateModeConfiguration()
  }, [invalidateModeConfiguration])

  const changeSplitColumn = useCallback((name: string) => {
    setSplitColumnValue(name || null)
    invalidateModeConfiguration()
  }, [invalidateModeConfiguration])

  const changeFilePrefix = useCallback((value: string) => {
    setFilePrefixValue(value)
    invalidateModeConfiguration()
  }, [invalidateModeConfiguration])

  const addComplexEntry = useCallback(() => {
    setComplexEntries([...complexEntriesRef.current, {
      fieldName: '',
      sheetName: sheetNamesOf()[0] ?? '',
      headerIndex: 1,
      columnIndex: 1,
      copyAll: false,
    }])
    invalidateModeConfiguration()
  }, [invalidateModeConfiguration])

  const removeComplexEntry = useCallback((index: number) => {
    setComplexEntries(complexEntriesRef.current.filter((_, i) => i !== index))
    invalidateModeConfiguration()
  }, [invalidateModeConfiguration])

  const updateComplexEntry = useCallback((index: number, patch: Partial<ComplexEntryRow>) => {
    setComplexEntries(complexEntriesRef.current.map((entry, i) => (i === index ? { ...entry, ...patch } : entry)))
    invalidateModeConfiguration()
  }, [invalidateModeConfiguration])

  const toggleCopyAll = useCallback((index: number) => {
    setComplexEntries(complexEntriesRef.current.map((entry, i) => {
      if (i !== index) return entry
      // Checking copy-all moves to -1/-1 sentinels; unchecking restores 1/1.
      return entry.copyAll
        ? { ...entry, copyAll: false, headerIndex: 1, columnIndex: 1 }
        : { ...entry, copyAll: true, headerIndex: -1, columnIndex: -1 }
    }))
    invalidateModeConfiguration()
  }, [invalidateModeConfiguration])

  const downloadResult = useCallback(async () => {
    if (!outputDirRef.current || downloadingRef.current) return
    setDownloading(true)
    setRunError(null)
    try {
      await client.files.export(outputDirRef.current)
    } catch (error) {
      const message = errMsg(error)
      setRunError(message)
      notifyErr(message)
    } finally {
      setDownloading(false)
    }
  }, [client, notifyErr])

  /** "Split another file" on the completed screen: reset every wizard state and return to Source. */
  const restartWizard = useCallback(() => {
    cancelRestore()
    if (!clearExcelWizardRecord(sessionStorage)) reportPersistenceFailure()
    setSourceFile(null)
    setSession(null)
    setSheets(null)
    setAnalyzeError(null)
    setModeValue('BY_SHEET')
    setSelectedSheets([])
    setSplitSheetValue(null)
    setSplitColumnValue(null)
    setFilePrefixValue('')
    setComplexEntries([])
    setOutputDirValue(null)
    setResult(null)
    setRunError(null)
    setEstimatedFileCount(null)
    setConfigureError(null)
    setWizardDone(false)
    const states = createWizardStates(stepDefinitions, 'source')
    const snapshot: FyWizardSnapshot = {
      version: 1,
      activeStep: 'source',
      visitedPath: ['source'],
      states,
      completed: false,
    }
    applySeed(snapshot, false)
  }, [applySeed, cancelRestore, reportPersistenceFailure])

  /** Result-screen escape hatch back into Mode (the wizard freezes navigation when
   *  completed, so reopening Mode goes through the same invalidation path as the old
   *  rail click). */
  const adjustMode = useCallback(() => {
    invalidateDependencies('mode')
  }, [invalidateDependencies])

  // ── Wizard context + steps ──────────────────────────────────────────────────────────
  const context = useMemo<SplitterContext>(() => ({
    sourceFile,
    outputDir,
    sheets,
    sheetNames,
    columnsForSplitSheet,
    mode,
    selectedSheets,
    splitSheet,
    splitColumn,
    filePrefix,
    complexEntries,
    analyzing,
    configuring,
    estimating,
    estimatedFileCount,
    analyzeError,
    configureError,
    running,
    downloading,
    runError,
    result,
    completed: wizardDone,
    platform,
    modeLabel,
    configDetails,
    pickSource,
    pickOutput,
    changeMode,
    toggleSheet,
    changeSplitSheet,
    changeSplitColumn,
    changeFilePrefix,
    addComplexEntry,
    removeComplexEntry,
    updateComplexEntry,
    toggleCopyAll,
    downloadResult,
    restartWizard,
    adjustMode,
  }), [sourceFile, outputDir, sheets, sheetNames, columnsForSplitSheet, mode, selectedSheets, splitSheet, splitColumn, filePrefix, complexEntries, analyzing, configuring, estimating, estimatedFileCount, analyzeError, configureError, running, downloading, runError, result, wizardDone, platform, modeLabel, configDetails, pickSource, pickOutput, changeMode, toggleSheet, changeSplitSheet, changeSplitColumn, changeFilePrefix, addComplexEntry, removeComplexEntry, updateComplexEntry, toggleCopyAll, downloadResult, restartWizard, adjustMode])

  /** Only the ACTIVE step's render runs, so recording step+actions here keeps live handles
   *  into the wizard for the auto-advance/auto-run effects (same trick as the Vue version's
   *  captureWizardActions, minus the hidden interpolation). */
  const trackStep = useCallback((step: string, actions: FyWizardSlotActions, content: ReactNode) => {
    activeStepRef.current = step as StepValue
    wizardActionsRef.current = actions
    return content
  }, [])

  const steps = useMemo<StepWizardStep<SplitterContext>[]>(() => [
    {
      value: 'source',
      title: t('exui.step.source'),
      validate: () => validateSourceStep(),
      render: (props) => trackStep(props.step.value, props.actions, (
        <SourceStep context={props.context} showStepError={props.state.status === 'active'} />
      )),
    },
    {
      value: 'mode',
      title: t('exui.step.mode'),
      validate: () => validateModeStep(),
      render: (props) => trackStep(props.step.value, props.actions, (
        <ModeStep context={props.context} showStepError={props.state.status === 'active'} />
      )),
    },
    {
      value: 'output',
      title: t('exui.step.output'),
      validate: () => validateOutputStep(),
      render: (props) => trackStep(props.step.value, props.actions, <OutputStep context={props.context} />),
    },
    {
      value: 'run',
      title: t('exui.step.run'),
      validate: () => validateRunStep(),
      render: (props) => trackStep(props.step.value, props.actions, <RunStep context={props.context} />),
    },
  ], [t, trackStep, validateSourceStep, validateModeStep, validateOutputStep, validateRunStep])

  const wizardLabels = useMemo<StepWizardLabels>(() => ({
    next: t('exui.wizard.next'),
    finish: t('exui.wizard.finish'),
    back: t('exui.wizard.back'),
    status: {
      pending: t('exui.wizard.status.pending'),
      active: t('exui.wizard.status.active'),
      validating: t('exui.wizard.status.validating'),
      complete: t('exui.wizard.status.complete'),
      error: t('exui.wizard.status.error'),
      skipped: t('exui.wizard.status.skipped'),
    },
    compactProgress: (index, total) => t('exui.wizard.compactProgress', index, total),
  }), [t])

  const handleSnapshot = useCallback((snapshot: FyWizardSnapshot) => {
    latestSnapshotRef.current = snapshot
    if (snapshot.completed) setWizardDone(true)
    if (sourceFileRef.current && sessionRef.current) {
      const saved = saveExcelWizardRecord(sessionStorage, {
        version: 1,
        wizard: snapshot,
        draft: currentDraft(),
      })
      if (!saved) reportPersistenceFailure()
    }
    if (snapshot.activeStep === 'run' && !snapshot.completed) {
      // Confirmed design: advancing Output → Run executes the split immediately — no second
      // click on the Run step. Deferred past the wizard's own advance so the post-commit
      // actions drive the finish.
      setTimeout(() => {
        if (activeStepRef.current !== 'run' || runningRef.current || wizardDoneRef.current) return
        void wizardActionsRef.current?.next()
      }, 0)
    }
  }, [currentDraft, reportPersistenceFailure, setWizardDone])

  const applyDraft = useCallback((draft: ExcelWizardDraft) => {
    setSourceFile(draft.sourceFileRef ? { ...draft.sourceFileRef } : null)
    setSession(draft.sessionId)
    setModeValue(draft.mode)
    setSelectedSheets([...draft.selectedSheets])
    setSplitSheetValue(draft.splitSheet)
    setSplitColumnValue(draft.splitColumn)
    setFilePrefixValue(draft.filePrefix)
    setComplexEntries(draft.complexEntries.map((entry) => ({ ...entry })))
  }, [])

  const restoreProgress = useCallback(async (): Promise<void> => {
    const record = loadExcelWizardRecord(sessionStorage)
    if (!record) return
    cancelRestore()
    const generation = restoreGenerationRef.current
    const controller = new AbortController()
    restoreControllerRef.current = controller
    applyDraft(record.draft)
    setOutputDirValue(null)
    setResult(null)
    setWizardDone(false)
    const sourceId = record.draft.sourceFileRef?.id ?? ''
    const sessionId = record.draft.sessionId
    // 'run' never restores directly: Output must be re-confirmed (its grant may be gone).
    const restoredActive = record.wizard.activeStep === 'run' ? 'output' : record.wizard.activeStep
    const restoredIndex = STEP_ORDER.indexOf(restoredActive as StepValue)
    const promise = runSourceValidation(controller.signal)
    restoreValidationRef.current = { generation, sourceId, sessionId, promise }
    try {
      const sourceResult = await promise
      if (restoreValidationRef.current?.generation === generation) restoreValidationRef.current = undefined
      if (
        controller.signal.aborted
        || generation !== restoreGenerationRef.current
        || sourceFileRef.current?.id !== sourceId
        || sessionRef.current !== sessionId
      ) return
      if (!sourceResult.valid) {
        const states = {
          ...createWizardStates(stepDefinitions, 'source'),
          source: { status: 'error' as const, error: sourceResult.message },
        }
        const snapshot: FyWizardSnapshot = {
          version: 1,
          activeStep: 'source',
          visitedPath: ['source'],
          states,
          completed: false,
        }
        applySeed(snapshot, false)
        if (sourceResult.message) notifyErr(sourceResult.message)
        return
      }

      if (restoredIndex >= STEP_ORDER.indexOf('output')) {
        const modeResult = await validateModeBody(controller.signal)
        if (
          controller.signal.aborted
          || generation !== restoreGenerationRef.current
          || sourceFileRef.current?.id !== sourceId
          || sessionRef.current !== sessionId
        ) return
        if (!modeResult.valid) {
          const states: Record<string, FyWizardStepState> = {
            ...createWizardStates(stepDefinitions, 'mode'),
            source: { status: 'complete' },
            mode: modeResult.message ? { status: 'error', error: modeResult.message } : { status: 'error' },
          }
          const snapshot: FyWizardSnapshot = {
            version: 1,
            activeStep: 'mode',
            visitedPath: ['source', 'mode'],
            states,
            completed: false,
          }
          applySeed(snapshot, false)
          if (modeResult.message) notifyErr(modeResult.message)
          return
        }
      }

      const restoredVisitedPath = record.wizard.visitedPath.filter((value) => {
        const index = STEP_ORDER.indexOf(value as StepValue)
        return index >= 0 && index <= restoredIndex
      })
      if (!restoredVisitedPath.includes(restoredActive)) restoredVisitedPath.push(restoredActive)
      applySeed({
        ...record.wizard,
        activeStep: restoredActive,
        visitedPath: [...new Set(restoredVisitedPath)],
        states: invalidateWizardStates(record.wizard.states, ['output', 'run']),
        completed: false,
      }, false)
    } catch (error) {
      if (
        controller.signal.aborted
        || generation !== restoreGenerationRef.current
        || (error instanceof DOMException && error.name === 'AbortError')
      ) return
      throw error
    } finally {
      if (restoreValidationRef.current?.generation === generation) restoreValidationRef.current = undefined
      if (restoreControllerRef.current === controller) restoreControllerRef.current = undefined
    }
  }, [applyDraft, applySeed, cancelRestore, notifyErr, runSourceValidation, validateModeBody])

  // ── Lifecycle ───────────────────────────────────────────────────────────────────────
  useEffect(() => {
    let cancelled = false
    void client.ready()
      .then((env) => {
        if (cancelled) return
        setEnvironment(env)
        setPlatform(env.platform ?? 'web')
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [client])

  useEffect(() => {
    void restoreProgress()
    return () => {
      // Unmount aborts every in-flight step/restore RPC so late resolutions never land.
      cancelRestore()
      validateControllerRef.current?.abort()
    }
  }, [cancelRestore, restoreProgress])

  // Deferred auto-advance after pickSource (runs once the re-seeded wizard has committed).
  useEffect(() => {
    if (!autoAdvanceRef.current) return
    autoAdvanceRef.current = false
    void wizardActionsRef.current?.next()
  })

  // ── T2 chrome data ──────────────────────────────────────────────────────────────────
  const runningCount = history.filter((task) => task.state === 'running').length
  const doneTasks = history.filter((task) => task.state === 'done')
  const totalWorkbooks = doneTasks.reduce((sum, task) => sum + (task.fileCount ?? 0), 0)
  const durations = doneTasks.map((task) => task.elapsedMs ?? 0).filter((ms) => ms > 0)
  const avgSeconds = durations.length > 0
    ? (durations.reduce((sum, ms) => sum + ms, 0) / durations.length / 1000).toFixed(1)
    : null
  const stats = [
    { label: t('exui.stats.splits'), value: String(doneTasks.length) },
    { label: t('exui.stats.workbooks'), value: String(totalWorkbooks) },
    { label: t('exui.stats.avgTime'), value: avgSeconds !== null ? `${avgSeconds} s` : '—' },
  ]
  const headerTone: StatusTone = runningCount > 0 ? 'warning' : 'idle'
  const pluginVersion = environment?.pluginVersion

  return (
    <main className="flex min-w-0 flex-1 flex-col">
      <PluginHeader
        icon={<IconFileSpreadsheet size={14} stroke={1.8} />}
        name={t('exui.title')}
        category="file"
        version={pluginVersion ? `v${pluginVersion}` : undefined}
        right={
          <>
            <StatusChip tone={headerTone}>
              {runningCount > 0 ? t('exui.header.processing', runningCount) : t('exui.header.idle')}
            </StatusChip>
            <GoldButton data-action="new-split" onClick={restartWizard}>
              <IconPlus size={15} stroke={1.7} />
              {t('exui.header.newSplit')}
            </GoldButton>
          </>
        }
      />

      {/* 拆分任务：指标行 + 向导。hidden（而非卸载）保证切换导航不丢向导进度。 */}
      <div hidden={nav !== 'split'} className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto w-full px-6 py-6" style={{ maxWidth: 980 }}>
          <div className="mb-5 grid gap-4" style={{ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }} data-stat-cards>
            {stats.map((stat) => (
              <div key={stat.label} className="rounded-xl border border-line bg-panel px-4 py-3">
                <div className="text-xs" style={{ color: 'var(--c-ink-2)' }}>{stat.label}</div>
                <div className="mt-1 font-mono text-xl font-semibold tracking-tight text-ink">{stat.value}</div>
              </div>
            ))}
          </div>
          <div className="rounded-xl border border-line bg-panel px-5 py-4">
            <StepWizard
              key={seed.key}
              steps={steps}
              context={context}
              completed={wizardDone}
              snapshot={seed.snapshot}
              onSnapshot={handleSnapshot}
              labels={wizardLabels}
            />
          </div>
        </div>
      </div>

      {nav === 'history' ? <HistoryPage history={history} /> : null}
      {nav === 'settings' ? <SettingsPage environment={environment} session={session} /> : null}
      {nav === 'permissions' ? <PermissionsPage environment={environment} /> : null}

      <StatusBar
        left={
          <>
            <span>{session ? t('exui.status.worker') : t('exui.status.workerIdle')}</span>
            <span>{t('exui.status.tasks', runningCount, doneTasks.length)}</span>
          </>
        }
        right={
          <span>
            {environment?.pluginId || 'fan.summer.excel'}
            {pluginVersion ? ` · v${pluginVersion}` : ''}
          </span>
        }
      />
    </main>
  )
}

/** 拆分历史：本会话的拆分任务表（运行中行带 GlowingEffect 流光描边）。 */
function HistoryPage({ history }: { history: SplitTask[] }) {
  const { t } = useFengYuI18n()
  const columns = 'minmax(0, 2.2fr) minmax(0, 1.4fr) 64px 72px 88px'
  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full px-6 py-6" style={{ maxWidth: 980 }}>
        <header className="mb-4">
          <h2 className="text-[15px] font-semibold tracking-tight">{t('exui.history.title')}</h2>
          <p className="mt-1 text-[13px]" style={{ color: 'var(--c-ink-2)' }}>
            {t('exui.history.description')}
          </p>
        </header>
        {history.length === 0 ? (
          <div className="rounded-xl border border-line bg-panel">
            <EmptyState icon={<IconHistory size={20} stroke={1.6} />} title={t('exui.history.empty')} message={t('exui.history.emptyHint')} />
          </div>
        ) : (
          <div className="overflow-hidden rounded-xl border border-line bg-panel" data-history-table>
            <div
              className="grid items-center gap-3 border-b border-line px-4 py-2 text-xs"
              style={{ gridTemplateColumns: columns, background: 'var(--c-muted-surface)', color: 'var(--c-ink-2)' }}
            >
              <span>{t('exui.history.file')}</span>
              <span>{t('exui.history.rule')}</span>
              <span style={{ textAlign: 'right' }}>{t('exui.history.files')}</span>
              <span style={{ textAlign: 'right' }}>{t('exui.history.elapsed')}</span>
              <span style={{ textAlign: 'right' }}>{t('exui.history.status')}</span>
            </div>
            {history.map((task) => {
              const running = task.state === 'running'
              return (
                <div
                  key={task.id}
                  data-task-state={task.state}
                  className="relative grid items-center gap-3 border-b border-line px-4 text-[13px]"
                  style={{ gridTemplateColumns: columns, paddingTop: '0.625rem', paddingBottom: '0.625rem' }}
                >
                  {running ? (
                    <div className="pointer-events-none absolute inset-0 rounded-lg">
                      <GlowingEffect spread={40} borderWidth={1.5} glow variant="default" disabled={false} />
                    </div>
                  ) : null}
                  <span className="flex min-w-0 items-center gap-2">
                    <IconPaperclip size={14} stroke={1.6} className="shrink-0" style={{ color: 'var(--c-ink-3)' }} />
                    <span className="truncate font-mono text-[12.5px]">{task.file}</span>
                  </span>
                  <span className="truncate" style={{ color: 'var(--c-ink-2)' }}>{task.rule}</span>
                  <span style={{ textAlign: 'right' }} className="font-mono text-ink-2">
                    {task.fileCount ?? '—'}
                  </span>
                  <span style={{ textAlign: 'right' }} className="font-mono text-ink-2">
                    {task.elapsedMs !== null ? `${(task.elapsedMs / 1000).toFixed(1)} s` : '—'}
                  </span>
                  <span className="flex justify-end">
                    {running ? (
                      <span className="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px]" style={{ background: 'var(--c-warning-bg)', color: 'var(--c-warning)' }}>
                        <span className="relative inline-block h-1 w-6 overflow-hidden rounded-full" style={{ background: 'rgba(24,24,27,0.10)' }}>
                          <span
                            className="absolute inset-y-0 block h-full w-1/3 rounded-full"
                            style={{ background: 'var(--c-warning)', animation: 'fy-indeterminate 1.4s ease-in-out infinite' }}
                          />
                        </span>
                        {t('exui.history.running')}
                      </span>
                    ) : task.state === 'done' ? (
                      <span className="rounded-full px-2 py-0.5 text-[11px]" style={{ background: 'var(--c-success-bg)', color: 'var(--c-success)' }}>
                        {t('exui.history.done')}
                      </span>
                    ) : (
                      <span className="rounded-full px-2 py-0.5 text-[11px]" style={{ background: 'var(--c-danger-bg)', color: 'var(--c-danger)' }}>
                        {t('exui.history.failed')}
                      </span>
                    )}
                  </span>
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}

/** 运行设置：宿主环境的实时回显（真实数据，非装饰）。 */
function SettingsPage({ environment, session }: { environment: Environment | null; session: string | null }) {
  const { t } = useFengYuI18n()
  const rows: Array<[string, string]> = [
    [t('exui.settings.platform'), environment?.platform ?? '—'],
    [t('exui.settings.locale'), environment?.locale ?? '—'],
    [t('exui.settings.theme'), environment?.theme ?? '—'],
    [t('exui.settings.plugin'), environment?.pluginId ? `${environment.pluginId}${environment.pluginVersion ? ` · v${environment.pluginVersion}` : ''}` : '—'],
    [t('exui.settings.session'), session ?? t('exui.settings.sessionPending')],
  ]
  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full px-6 py-6" style={{ maxWidth: 980 }}>
        <header className="mb-4">
          <h2 className="text-[15px] font-semibold tracking-tight">{t('exui.settings.title')}</h2>
          <p className="mt-1 text-[13px]" style={{ color: 'var(--c-ink-2)' }}>
            {t('exui.settings.description')}
          </p>
        </header>
        {environment ? (
          <div className="rounded-xl border border-line bg-panel px-4 py-1">
            {rows.map(([label, value]) => (
              <div key={label} className="flex gap-3 border-b border-line text-[13px]" style={{ borderColor: 'var(--c-line)', paddingTop: '0.625rem', paddingBottom: '0.625rem' }}>
                <span className="shrink-0" style={{ flexBasis: '140px', color: 'var(--c-ink-2)' }}>{label}</span>
                <span className="min-w-0 font-mono text-[12.5px]">{value}</span>
              </div>
            ))}
          </div>
        ) : (
          <LoadingState />
        )}
      </div>
    </div>
  )
}

/** 权限：宿主 ready 握手上报的授权列表。 */
function PermissionsPage({ environment }: { environment: Environment | null }) {
  const { t } = useFengYuI18n()
  const permissions = environment?.permissions ?? []
  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full px-6 py-6" style={{ maxWidth: 980 }}>
        <header className="mb-4">
          <h2 className="text-[15px] font-semibold tracking-tight">{t('exui.permissions.title')}</h2>
          <p className="mt-1 text-[13px]" style={{ color: 'var(--c-ink-2)' }}>
            {t('exui.permissions.description')}
          </p>
        </header>
        {environment ? (
          permissions.length > 0 ? (
            <div className="flex flex-wrap gap-2">
              {permissions.map((permission) => (
                <Chip key={permission}>
                  <span className="font-mono text-[11px]">{permission}</span>
                </Chip>
              ))}
            </div>
          ) : (
            <div className="rounded-xl border border-line bg-panel">
              <EmptyState icon={<IconShieldCheck size={20} stroke={1.6} />} title={t('exui.permissions.empty')} message={t('exui.permissions.emptyHint')} />
            </div>
          )
        ) : (
          <LoadingState />
        )}
      </div>
    </div>
  )
}
