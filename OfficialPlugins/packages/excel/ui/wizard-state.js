function createLocalId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `excel-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export function initialState() {
  return {
    step: 0,
    session: createLocalId(),
    input: null,
    sheets: null,
    mode: 'BY_SHEET',
    selectedSheets: [],
    splitSheet: '',
    splitColumn: '',
    splitColumnIndex: -1,
    complexEntries: [],
    output: null,
    phase: 'idle',
    error: '',
    result: null,
    exported: false,
  }
}

function hasAnalysis(state) {
  return Boolean(state.input && state.sheets && Object.keys(state.sheets).length)
}

function validModeConfiguration(state) {
  if (!hasAnalysis(state)) return false
  if (state.mode === 'BY_SHEET') {
    return state.selectedSheets.length > 0
      && state.selectedSheets.every(name => Object.hasOwn(state.sheets, name))
  }
  if (state.mode === 'BY_COLUMN') {
    const headers = state.sheets[state.splitSheet]
    return Boolean(headers
      && Number.isInteger(state.splitColumnIndex)
      && state.splitColumnIndex >= 0
      && String(state.splitColumn) === String(headers[state.splitColumnIndex]))
  }
  if (state.mode === 'COMPLEX') {
    return state.complexEntries.length > 0 && state.complexEntries.every(entry => {
      if (!Object.hasOwn(state.sheets, entry.sheetName)) return false
      const copyWholeSheet = entry.headerIndex === -1 && entry.columnIndex === -1
      return copyWholeSheet || (entry.headerIndex > 0 && entry.columnIndex > 0)
    })
  }
  return false
}

export function canContinue(state) {
  if (state.phase === 'analyzing' || state.phase === 'configuring' || state.phase === 'running') {
    return false
  }
  if (state.step === 0) return hasAnalysis(state)
  if (state.step === 1 || state.step === 2) return validModeConfiguration(state)
  return false
}

export function addComplexRule(state, rule) {
  const copyWholeSheet = Boolean(rule.copyWholeSheet)
  const headerIndex = copyWholeSheet ? -1 : Number(rule.headerIndex)
  const columnIndex = copyWholeSheet ? -1 : Number(rule.columnIndex)
  if (!rule.sheetName) throw new Error('Select a sheet')
  if (!copyWholeSheet
      && (!Number.isInteger(headerIndex) || headerIndex < 1
        || !Number.isInteger(columnIndex) || columnIndex < 1)) {
    throw new Error('Header row and split column must be positive integers')
  }
  const entry = {
    fieldName: rule.fieldName ?? state.input?.name ?? '',
    sheetName: rule.sheetName,
    headerIndex,
    columnIndex,
  }
  return { ...state, complexEntries: [...state.complexEntries, entry] }
}

export function removeComplexRule(state, index) {
  return {
    ...state,
    complexEntries: state.complexEntries.filter((_, entryIndex) => entryIndex !== index),
  }
}

export function configurePayload(state) {
  const payload = { session: state.session, mode: state.mode }
  if (state.mode === 'BY_SHEET') {
    return { ...payload, selectedSheets: [...state.selectedSheets] }
  }
  if (state.mode === 'BY_COLUMN') {
    return {
      ...payload,
      splitSheet: state.splitSheet,
      splitColumn: state.splitColumn,
      splitColumnIndex: state.splitColumnIndex,
    }
  }
  return {
    ...payload,
    complexEntries: state.complexEntries.map(entry => ({ ...entry })),
  }
}

function refLabel(ref, fallback) {
  return ref?.name || ref?.path || fallback
}

export function confirmationRows(state) {
  const rows = [
    { label: 'Source', value: refLabel(state.input, 'Not selected') },
    { label: 'Mode', value: {
      BY_SHEET: 'By sheet', BY_COLUMN: 'By column', COMPLEX: 'Complex rules',
    }[state.mode] || state.mode },
  ]
  if (state.mode === 'BY_SHEET') {
    rows.push({ label: 'Sheets', value: state.selectedSheets.join(', ') })
  } else if (state.mode === 'BY_COLUMN') {
    rows.push({ label: 'Sheet', value: state.splitSheet })
    rows.push({ label: 'Column', value: state.splitColumn })
  } else {
    rows.push({ label: 'Rules', value: String(state.complexEntries.length) })
  }
  rows.push({ label: 'Output', value: refLabel(state.output, 'Choose on next step') })
  return rows
}
