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

const wizardStatuses = new Set([
  'pending',
  'active',
  'validating',
  'complete',
  'error',
  'skipped',
])

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function isFileRef(value: unknown): value is FileRef {
  if (!isObject(value)) return false
  return typeof value.id === 'string'
    && typeof value.name === 'string'
    && value.kind === 'file'
    && (value.access === 'read' || value.access === 'read-write')
    && typeof value.size === 'number'
    && Number.isFinite(value.size)
    && value.size >= 0
}

function isWizardSnapshot(value: unknown): value is FyWizardSnapshot {
  if (!isObject(value)
    || value.version !== 1
    || typeof value.activeStep !== 'string'
    || !isStringArray(value.visitedPath)
    || !isObject(value.states)
    || typeof value.completed !== 'boolean') return false

  return Object.values(value.states).every((state) => {
    if (!isObject(state) || typeof state.status !== 'string' || !wizardStatuses.has(state.status)) {
      return false
    }
    return state.error === undefined || typeof state.error === 'string'
  })
}

function isComplexEntry(value: unknown): value is ExcelComplexEntry {
  if (!isObject(value)) return false
  if (typeof value.fieldName !== 'string'
    || typeof value.sheetName !== 'string'
    || typeof value.headerIndex !== 'number'
    || typeof value.columnIndex !== 'number'
    || typeof value.copyAll !== 'boolean') return false
  if (value.copyAll) return value.headerIndex === -1 && value.columnIndex === -1
  return Number.isInteger(value.headerIndex)
    && value.headerIndex >= 1
    && Number.isInteger(value.columnIndex)
    && value.columnIndex >= 1
}

function isWizardDraft(value: unknown): value is ExcelWizardDraft {
  if (!isObject(value)) return false
  return (value.sourceFileRef === null || isFileRef(value.sourceFileRef))
    && typeof value.sessionId === 'string'
    && (value.mode === 'BY_SHEET' || value.mode === 'BY_COLUMN' || value.mode === 'COMPLEX')
    && isStringArray(value.selectedSheets)
    && isNullableString(value.splitSheet)
    && isNullableString(value.splitColumn)
    && typeof value.filePrefix === 'string'
    && Array.isArray(value.complexEntries)
    && value.complexEntries.every(isComplexEntry)
}

function isExcelWizardRecord(value: unknown): value is ExcelWizardRecord {
  return isObject(value)
    && value.version === 1
    && isWizardSnapshot(value.wizard)
    && isWizardDraft(value.draft)
}

export function loadExcelWizardRecord(storage: Storage): ExcelWizardRecord | null {
  try {
    const raw = storage.getItem(EXCEL_WIZARD_STORAGE_KEY)
    if (!raw) return null
    const value: unknown = JSON.parse(raw)
    return isExcelWizardRecord(value) ? value : null
  } catch {
    return null
  }
}

export function saveExcelWizardRecord(storage: Storage, record: ExcelWizardRecord): boolean {
  try {
    storage.setItem(EXCEL_WIZARD_STORAGE_KEY, JSON.stringify(record))
    return true
  } catch {
    return false
  }
}

export function clearExcelWizardRecord(storage: Storage): boolean {
  try {
    storage.removeItem(EXCEL_WIZARD_STORAGE_KEY)
    return true
  } catch {
    return false
  }
}
