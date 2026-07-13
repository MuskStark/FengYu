import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  initialState, canContinue, addComplexRule, removeComplexRule,
  configurePayload, confirmationRows,
} from './wizard-state.js'

test('analysis gates step one', () => {
  assert.equal(canContinue({ ...initialState(), step: 0 }), false)
  assert.equal(canContinue({
    ...initialState(), step: 0, input: { id: 'ref' }, sheets: { A: { 0: 'id' } },
  }), true)
})

test('by sheet requires one analyzed selected sheet', () => {
  const base = {
    ...initialState(), step: 1, input: { id: 'ref' }, sheets: { A: { 0: 'id' } },
  }
  assert.equal(canContinue({ ...base, mode: 'BY_SHEET', selectedSheets: [] }), false)
  assert.equal(canContinue({ ...base, mode: 'BY_SHEET', selectedSheets: ['A'] }), true)
  assert.equal(canContinue({ ...base, mode: 'BY_SHEET', selectedSheets: ['Missing'] }), false)
})

test('by column requires an analyzed sheet and column', () => {
  const base = {
    ...initialState(), step: 1, input: { id: 'ref' }, sheets: { A: { 0: 'id' } },
    mode: 'BY_COLUMN',
  }
  assert.equal(canContinue({ ...base, splitSheet: 'A' }), false)
  assert.equal(canContinue({
    ...base, splitSheet: 'A', splitColumn: 'id', splitColumnIndex: 0,
  }), true)
  assert.equal(canContinue({
    ...base, splitSheet: 'A', splitColumn: 'missing', splitColumnIndex: 1,
  }), false)
})

test('complex whole sheet stores -1 sentinels and rules are removable', () => {
  const next = addComplexRule(initialState(), { sheetName: 'A', copyWholeSheet: true })
  assert.deepEqual(next.complexEntries[0], {
    fieldName: '', sheetName: 'A', headerIndex: -1, columnIndex: -1,
  })
  assert.deepEqual(removeComplexRule(next, 0).complexEntries, [])
})

test('complex normal rules require positive indexes', () => {
  assert.throws(() => addComplexRule(initialState(), {
    sheetName: 'A', headerIndex: 0, columnIndex: 1,
  }), /positive/i)
})

test('configure payload contains only active mode fields', () => {
  assert.deepEqual(configurePayload({
    ...initialState(), session: 's', mode: 'BY_SHEET', selectedSheets: ['A'],
  }), { session: 's', mode: 'BY_SHEET', selectedSheets: ['A'] })
  const complexEntries = [
    { fieldName: 'in.xlsx', sheetName: 'A', headerIndex: -1, columnIndex: -1 },
  ]
  assert.deepEqual(configurePayload({
    ...initialState(), session: 's', mode: 'COMPLEX', complexEntries,
  }), { session: 's', mode: 'COMPLEX', complexEntries })
})

test('confirmation rows are plain label/value data', () => {
  assert.deepEqual(confirmationRows({
    ...initialState(), input: { name: 'in.xlsx' }, mode: 'BY_SHEET',
    selectedSheets: ['A', 'B'], output: { name: 'out' },
  }), [
    { label: 'Source', value: 'in.xlsx' },
    { label: 'Mode', value: 'By sheet' },
    { label: 'Sheets', value: 'A, B' },
    { label: 'Output', value: 'out' },
  ])
})

test('test module does not depend on a DOM', async () => {
  const source = await readFile(new URL('./wizard-state.js', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /\bdocument\b|\bwindow\b/)
})
