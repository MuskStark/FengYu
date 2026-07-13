import { fengyu, createId } from './sdk.js'
import {
  initialState, canContinue, addComplexRule, removeComplexRule,
  configurePayload, confirmationRows,
} from './wizard-state.js'

const elements = {
  panels: [...document.querySelectorAll('.step-panel')],
  indicators: [...document.querySelectorAll('.steps li')],
  message: document.querySelector('#message'),
  pick: document.querySelector('#pick'),
  file: document.querySelector('#file'),
  analysis: document.querySelector('#analysis'),
  sheetList: document.querySelector('#sheet-list'),
  bySheet: document.querySelector('#by-sheet-options'),
  byColumn: document.querySelector('#by-column-options'),
  complex: document.querySelector('#complex-options'),
  columnSheet: document.querySelector('#column-sheet'),
  columnName: document.querySelector('#column-name'),
  complexSheet: document.querySelector('#complex-sheet'),
  copyWhole: document.querySelector('#copy-whole-sheet'),
  headerIndex: document.querySelector('#header-index'),
  columnIndex: document.querySelector('#column-index'),
  addRule: document.querySelector('#add-rule'),
  clearRules: document.querySelector('#clear-rules'),
  complexList: document.querySelector('#complex-list'),
  confirmation: document.querySelector('#confirmation'),
  runState: document.querySelector('#run-state'),
  resultFiles: document.querySelector('#result-files'),
  retry: document.querySelector('#retry'),
  exportAgain: document.querySelector('#export-again'),
  back: document.querySelector('#back'),
  next: document.querySelector('#next'),
}

let state = initialState()
let busy = false
const env = await fengyu.ready()
document.documentElement.dataset.theme = env.theme

function errorText(error) {
  return error instanceof Error ? error.message : String(error)
}

function option(select, value, label) {
  select.add(new Option(label, value))
}

function renderSheetOptions(select, selected) {
  select.replaceChildren()
  for (const name of Object.keys(state.sheets || {})) option(select, name, name)
  if (selected && Object.hasOwn(state.sheets || {}, selected)) select.value = selected
}

function renderModeDetail() {
  elements.bySheet.hidden = state.mode !== 'BY_SHEET'
  elements.byColumn.hidden = state.mode !== 'BY_COLUMN'
  elements.complex.hidden = state.mode !== 'COMPLEX'
  document.querySelector(`input[name="mode"][value="${state.mode}"]`).checked = true

  elements.sheetList.replaceChildren()
  for (const name of Object.keys(state.sheets || {})) {
    const label = document.createElement('label')
    label.className = 'sheet-choice'
    const input = document.createElement('input')
    input.type = 'checkbox'
    input.checked = state.selectedSheets.includes(name)
    input.addEventListener('change', () => {
      state = {
        ...state,
        selectedSheets: input.checked
          ? [...new Set([...state.selectedSheets, name])]
          : state.selectedSheets.filter(sheet => sheet !== name),
      }
      render()
    })
    label.append(input, document.createTextNode(name))
    elements.sheetList.append(label)
  }

  const sheetNames = Object.keys(state.sheets || {})
  const splitSheet = state.sheets?.[state.splitSheet] ? state.splitSheet : (sheetNames[0] || '')
  if (splitSheet !== state.splitSheet) state = { ...state, splitSheet }
  renderSheetOptions(elements.columnSheet, state.splitSheet)
  renderSheetOptions(elements.complexSheet, elements.complexSheet.value || sheetNames[0])

  elements.columnName.replaceChildren()
  const headers = state.sheets?.[state.splitSheet] || {}
  for (const [index, name] of Object.entries(headers)) option(elements.columnName, index, name)
  const currentIndex = String(state.splitColumnIndex)
  if (Object.hasOwn(headers, currentIndex)) elements.columnName.value = currentIndex
  else if (elements.columnName.options.length) {
    const first = elements.columnName.options[0]
    state = {
      ...state, splitColumnIndex: Number(first.value), splitColumn: first.textContent || '',
    }
  }

  elements.headerIndex.disabled = elements.copyWhole.checked
  elements.columnIndex.disabled = elements.copyWhole.checked
  elements.complexList.replaceChildren()
  state.complexEntries.forEach((entry, index) => {
    const item = document.createElement('li')
    item.className = 'rule-row'
    const text = document.createElement('span')
    text.textContent = entry.headerIndex === -1
      ? `${entry.sheetName} — copy whole sheet`
      : `${entry.sheetName} — header ${entry.headerIndex}, column ${entry.columnIndex}`
    const remove = document.createElement('button')
    remove.type = 'button'
    remove.className = 'danger secondary'
    remove.textContent = 'Remove'
    remove.addEventListener('click', () => {
      state = removeComplexRule(state, index)
      render()
    })
    item.append(text, remove)
    elements.complexList.append(item)
  })
}

function renderConfirmation() {
  elements.confirmation.replaceChildren()
  for (const row of confirmationRows(state)) {
    const wrapper = document.createElement('div')
    wrapper.className = 'summary-row'
    const term = document.createElement('dt')
    term.textContent = row.label
    const detail = document.createElement('dd')
    detail.textContent = row.value
    wrapper.append(term, detail)
    elements.confirmation.append(wrapper)
  }
}

function renderResults() {
  const running = state.phase === 'running'
  const success = state.phase === 'success'
  elements.runState.textContent = running
    ? 'Splitting workbook…'
    : success
      ? `Created ${state.result?.fileCount || 0} file(s).`
      : state.phase === 'error' ? state.error : 'Ready to split.'
  elements.resultFiles.replaceChildren()
  for (const file of state.result?.files || []) {
    const item = document.createElement('li')
    item.textContent = file
    elements.resultFiles.append(item)
  }
  elements.retry.hidden = running || success
  elements.exportAgain.hidden = !success
}

function render() {
  elements.panels.forEach((panel, index) => { panel.hidden = index !== state.step })
  elements.indicators.forEach((indicator, index) => {
    indicator.classList.toggle('active', index === state.step)
    indicator.classList.toggle('done', index < state.step)
  })
  const pending = busy || ['analyzing', 'configuring', 'running'].includes(state.phase)
  elements.back.hidden = state.step === 0
  elements.back.disabled = pending
  elements.next.hidden = state.step === 3
  elements.next.disabled = pending || !canContinue(state)
  elements.next.textContent = state.step === 2 ? 'Choose output & run' : 'Next'
  elements.message.className = `message ${state.phase === 'error' ? 'error' : ''}`
  elements.message.textContent = state.error
  elements.pick.disabled = pending
  elements.file.textContent = state.input?.name || 'No file selected'
  elements.analysis.textContent = state.phase === 'analyzing'
    ? 'Analyzing workbook…'
    : state.sheets
      ? `${Object.keys(state.sheets).length} sheet(s) found.`
      : 'Select an .xlsx or .xls file to begin.'
  renderModeDetail()
  renderConfirmation()
  renderResults()
}

async function chooseSource() {
  if (busy) return
  try {
    const input = await fengyu.files.open({
      extensions: ['xlsx', 'xls'], filters: [{ name: 'Excel', extensions: ['xlsx', 'xls'] }],
    })
    if (!input) return
    state = { ...initialState(), session: createId(), input, phase: 'analyzing' }
    busy = true
    render()
    const result = await fengyu.invoke('analyze', {
      session: state.session, sourceFile: state.input,
    })
    const sheetNames = Object.keys(result.sheets || {})
    state = {
      ...state, sheets: result.sheets, selectedSheets: sheetNames,
      splitSheet: sheetNames[0] || '', phase: 'idle', error: '',
    }
  } catch (error) {
    state = { ...state, phase: 'error', error: errorText(error) }
  } finally {
    busy = false
    render()
  }
}

function addRuleFromForm() {
  try {
    state = addComplexRule(state, {
      fieldName: state.input?.name || '',
      sheetName: elements.complexSheet.value,
      copyWholeSheet: elements.copyWhole.checked,
      headerIndex: Number(elements.headerIndex.value),
      columnIndex: Number(elements.columnIndex.value),
    })
    state = { ...state, error: '' }
  } catch (error) {
    state = { ...state, error: errorText(error) }
  }
  render()
}

async function advance() {
  if (busy || !canContinue(state)) return
  if (state.step === 1) {
    busy = true
    state = { ...state, phase: 'configuring', error: '' }
    render()
    try {
      await fengyu.invoke('configure', configurePayload(state))
      state = { ...state, step: 2, phase: 'idle' }
    } catch (error) {
      state = { ...state, phase: 'error', error: errorText(error) }
    } finally {
      busy = false
      render()
    }
    return
  }
  if (state.step === 2) {
    try {
      const output = await fengyu.files.outputDirectory()
      if (!output) return
      state = { ...state, output, step: 3, error: '' }
      render()
      await runSplit()
    } catch (error) {
      state = { ...state, phase: 'error', error: errorText(error) }
      render()
    }
    return
  }
  state = { ...state, step: state.step + 1, error: '' }
  render()
}

async function runSplit() {
  if (busy) return
  busy = true
  state = { ...state, phase: 'running', error: '' }
  render()
  try {
    const result = await fengyu.invoke('split', {
      session: state.session, sourceFile: state.input, outputDir: state.output,
    })
    state = { ...state, result, phase: 'success' }
    if (env.platform === 'web' && !state.exported) {
      await fengyu.files.export(state.output)
      state = { ...state, exported: true }
    }
  } catch (error) {
    state = { ...state, phase: 'error', error: errorText(error) }
  } finally {
    busy = false
    render()
  }
}

function back() {
  if (busy || state.step === 0) return
  state = { ...state, step: state.step - 1, error: '', phase: 'idle' }
  render()
}

async function retry() {
  if (busy) return
  state = { ...state, result: null, error: '', exported: false, phase: 'idle' }
  await runSplit()
}

elements.pick.addEventListener('click', chooseSource)
document.querySelectorAll('input[name="mode"]').forEach(input => {
  input.addEventListener('change', () => {
    state = { ...state, mode: input.value, error: '' }
    render()
  })
})
elements.columnSheet.addEventListener('change', () => {
  state = { ...state, splitSheet: elements.columnSheet.value, splitColumn: '', splitColumnIndex: -1 }
  render()
})
elements.columnName.addEventListener('change', () => {
  const selected = elements.columnName.selectedOptions[0]
  state = {
    ...state, splitColumnIndex: Number(elements.columnName.value),
    splitColumn: selected?.textContent || '',
  }
  render()
})
elements.copyWhole.addEventListener('change', render)
elements.addRule.addEventListener('click', addRuleFromForm)
elements.clearRules.addEventListener('click', () => {
  state = { ...state, complexEntries: [], error: '' }
  render()
})
elements.back.addEventListener('click', back)
elements.next.addEventListener('click', advance)
elements.retry.addEventListener('click', retry)
elements.exportAgain.addEventListener('click', async () => {
  try { await fengyu.files.export(state.output) }
  catch (error) { state = { ...state, error: errorText(error) }; render() }
})

render()
