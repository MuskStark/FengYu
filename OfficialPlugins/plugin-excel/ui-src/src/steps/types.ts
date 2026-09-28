import type { Environment, FileRef } from '@infinia/plugin-sdk'
import type { AnalyzeOutput } from '../generated/fengyu-rpc'

/** Split modes offered by the worker (`configure` / `split` `mode` field). */
export type SplitMode = 'BY_SHEET' | 'BY_COLUMN' | 'COMPLEX'

/** Analyzed workbook shape from the generated `analyze` Output: one entry per sheet. */
export type AnalyzedSheet = NonNullable<NonNullable<AnalyzeOutput['sheets']>[number]>

/** One COMPLEX rule row (UI shape; `copyAll` rules carry -1 sentinel indices). */
export interface ComplexEntryRow {
  fieldName: string
  sheetName: string
  headerIndex: number
  columnIndex: number
  copyAll: boolean
}

/** A split run tracked for the T2 拆分历史 table (session-local). */
export interface SplitTask {
  id: string
  file: string
  rule: string
  state: 'running' | 'done' | 'failed'
  fileCount: number | null
  elapsedMs: number | null
}

/**
 * Caller-owned wizard context (the "form state" half of the old Vue component's refs).
 * `ExcelSplitter` rebuilds it every render and hands it to `StepWizard`, which passes
 * it to each step's `validate` and `render`.
 */
export interface SplitterContext {
  // form state (Source / Mode / Output)
  sourceFile: FileRef | null
  outputDir: FileRef | null
  sheets: AnalyzedSheet[] | null
  sheetNames: string[]
  columnsForSplitSheet: string[]
  mode: SplitMode
  selectedSheets: string[]
  splitSheet: string | null
  splitColumn: string | null
  filePrefix: string
  complexEntries: ComplexEntryRow[]
  // async display state
  analyzing: boolean
  configuring: boolean
  estimating: boolean
  estimatedFileCount: number | null
  /** Last analyze/configure failure texts. Rendered by the step body only when the wizard
   *  itself is not already showing the failure (wizard-validated vs restore-validated). */
  analyzeError: string | null
  configureError: string | null
  running: boolean
  downloading: boolean
  runError: string | null
  result: { fileCount: number; files: string[] } | null
  completed: boolean
  platform: Environment['platform'] | null
  // composed summaries (Output step)
  modeLabel: string
  configDetails: string[]
  // actions (each invalidates downstream wizard steps as appropriate)
  pickSource: (ref: FileRef | null) => void
  pickOutput: (ref: FileRef | null) => void
  changeMode: (mode: SplitMode) => void
  toggleSheet: (name: string) => void
  changeSplitSheet: (name: string) => void
  changeSplitColumn: (column: string) => void
  changeFilePrefix: (value: string) => void
  addComplexEntry: () => void
  removeComplexEntry: (index: number) => void
  updateComplexEntry: (index: number, patch: Partial<ComplexEntryRow>) => void
  toggleCopyAll: (index: number) => void
  downloadResult: () => void
  restartWizard: () => void
  adjustMode: () => void
}
