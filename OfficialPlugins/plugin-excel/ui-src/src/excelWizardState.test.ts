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

function storageThatThrows(method: 'getItem' | 'setItem' | 'removeItem'): Storage {
  return {
    length: 0,
    clear() {},
    key: () => null,
    getItem: () => {
      if (method === 'getItem') throw new DOMException('Storage unavailable', 'SecurityError')
      return null
    },
    setItem: () => {
      if (method === 'setItem') throw new DOMException('Storage full', 'QuotaExceededError')
    },
    removeItem: () => {
      if (method === 'removeItem') throw new DOMException('Storage unavailable', 'SecurityError')
    },
  }
}

function storeUnknown(value: unknown): void {
  sessionStorage.setItem(EXCEL_WIZARD_STORAGE_KEY, JSON.stringify(value))
}

describe('Excel wizard persistence', () => {
  beforeEach(() => sessionStorage.clear())

  it('round-trips a versioned record', () => {
    expect(saveExcelWizardRecord(sessionStorage, record)).toBe(true)
    expect(loadExcelWizardRecord(sessionStorage)).toEqual(record)
  })

  it('drops corrupt and unsupported records', () => {
    sessionStorage.setItem(EXCEL_WIZARD_STORAGE_KEY, '{bad json')
    expect(loadExcelWizardRecord(sessionStorage)).toBeNull()
    sessionStorage.setItem(EXCEL_WIZARD_STORAGE_KEY, JSON.stringify({ version: 2 }))
    expect(loadExcelWizardRecord(sessionStorage)).toBeNull()
  })

  it('drops structurally invalid records', () => {
    const invalid = structuredClone(record) as unknown as Record<string, unknown>
    ;(invalid.draft as Record<string, unknown>).complexEntries = [{ fieldName: 'region' }]
    sessionStorage.setItem(EXCEL_WIZARD_STORAGE_KEY, JSON.stringify(invalid))

    expect(loadExcelWizardRecord(sessionStorage)).toBeNull()
  })

  it.each([
    ['a directory source grant', () => {
      const invalid = structuredClone(record)
      invalid.draft.sourceFileRef = {
        ...invalid.draft.sourceFileRef!,
        kind: 'directory',
        access: 'write',
      }
      return invalid
    }],
    ['an unknown wizard status', () => {
      const invalid = structuredClone(record) as unknown as Record<string, unknown>
      const wizard = invalid.wizard as Record<string, unknown>
      wizard.states = { source: { status: 'unknown' } }
      return invalid
    }],
    ['an array draft', () => ({ ...structuredClone(record), draft: [] })],
    ['a primitive selectedSheets field', () => ({
      ...structuredClone(record),
      draft: { ...structuredClone(record.draft), selectedSheets: 'Q1' },
    })],
    ['a primitive complexEntries field', () => ({
      ...structuredClone(record),
      draft: { ...structuredClone(record.draft), complexEntries: 1 },
    })],
  ])('drops records with %s', (_description, createInvalid) => {
    storeUnknown(createInvalid())
    expect(loadExcelWizardRecord(sessionStorage)).toBeNull()
  })

  it.each([
    ['a fractional header index', { headerIndex: 1.9, columnIndex: 1, copyAll: false }],
    ['a non-positive column index', { headerIndex: 1, columnIndex: 0, copyAll: false }],
    ['a negative header index', { headerIndex: -2, columnIndex: 1, copyAll: false }],
    ['copy-all with a non--1 index', { headerIndex: -1, columnIndex: 1, copyAll: true }],
    ['non-copy-all with sentinel indices', { headerIndex: -1, columnIndex: -1, copyAll: false }],
  ])('drops COMPLEX records with %s', (_description, indices) => {
    storeUnknown({
      ...structuredClone(record),
      draft: {
        ...structuredClone(record.draft),
        mode: 'COMPLEX',
        complexEntries: [{
          fieldName: 'region',
          sheetName: 'Sales',
          ...indices,
        }],
      },
    })

    expect(loadExcelWizardRecord(sessionStorage)).toBeNull()
  })

  it.each([
    ['indexed', { headerIndex: 1, columnIndex: 2, copyAll: false }],
    ['copy-all', { headerIndex: -1, columnIndex: -1, copyAll: true }],
  ])('round-trips a valid %s COMPLEX rule', (_description, indices) => {
    const valid: ExcelWizardRecord = {
      ...structuredClone(record),
      draft: {
        ...structuredClone(record.draft),
        mode: 'COMPLEX',
        complexEntries: [{
          fieldName: 'region',
          sheetName: 'Sales',
          ...indices,
        }],
      },
    }

    expect(saveExcelWizardRecord(sessionStorage, valid)).toBe(true)
    expect(loadExcelWizardRecord(sessionStorage)).toEqual(valid)
  })

  it('returns false instead of throwing for cyclic records', () => {
    const cyclic = structuredClone(record) as ExcelWizardRecord & { self?: unknown }
    cyclic.self = cyclic

    expect(saveExcelWizardRecord(sessionStorage, cyclic)).toBe(false)
    expect(sessionStorage.getItem(EXCEL_WIZARD_STORAGE_KEY)).toBeNull()
  })

  it('contains Storage access failures at every boundary', () => {
    expect(loadExcelWizardRecord(storageThatThrows('getItem'))).toBeNull()
    expect(saveExcelWizardRecord(storageThatThrows('setItem'), record)).toBe(false)
    expect(clearExcelWizardRecord(storageThatThrows('removeItem'))).toBe(false)
  })

  it('clears the owned key only', () => {
    sessionStorage.setItem('unrelated', 'keep')
    expect(saveExcelWizardRecord(sessionStorage, record)).toBe(true)
    expect(clearExcelWizardRecord(sessionStorage)).toBe(true)
    expect(sessionStorage.getItem(EXCEL_WIZARD_STORAGE_KEY)).toBeNull()
    expect(sessionStorage.getItem('unrelated')).toBe('keep')
  })
})
