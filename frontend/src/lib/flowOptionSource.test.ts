import { describe, expect, it } from 'vitest'
import {
  contextFeedOptions,
  contextRowFieldOptions,
  mapCatalogOptions,
  parseContextFeeds,
  renderContextParams,
} from './flowOptionSource'

describe('mapCatalogOptions', () => {
  it('maps a top-level list through value/label', () => {
    const options = mapCatalogOptions(
      { accounts: [{ id: 'a1', address: 'a@x.com' }, { id: 'a2', address: 'b@x.com' }] },
      { items: 'accounts', value: 'id', label: 'address' },
    )
    expect(options).toEqual([
      { value: 'a1', label: 'a@x.com' },
      { value: 'a2', label: 'b@x.com' },
    ])
  })

  it('joins a secondary label and drops non-object entries', () => {
    const options = mapCatalogOptions(
      [null, { id: 1, name: 'One', note: 'first' }],
      { value: 'id', label: 'name', labelSecondary: 'note' },
    )
    expect(options).toEqual([{ value: 1, label: 'One · first' }])
  })

  it('yields nothing for a missing list', () => {
    expect(mapCatalogOptions({ other: [] }, { items: 'accounts', value: 'id', label: 'id' })).toEqual([])
    expect(mapCatalogOptions('nope', { value: 'id', label: 'id' })).toEqual([])
  })
})

describe('renderContextParams', () => {
  it('templates {{value}} with the triggering value and passes the rest verbatim', () => {
    expect(renderContextParams({ path: '{{value}}', mode: 'fast' }, '/tmp/a.xlsx'))
      .toEqual({ path: '/tmp/a.xlsx', mode: 'fast' })
  })
})

describe('parseContextFeeds', () => {
  const feeds = {
    sheets: { list: 'sheets', item: 'name' },
    columns: { list: 'sheets', key: 'name', items: 'columns', itemField: 'header' },
  }

  it('extracts flat and keyed feeds from one analyze result', () => {
    const result = {
      sheets: [
        { name: 'Q1', columns: [{ header: 'A' }, { header: 'B' }] },
        { name: 'Q2', columns: [{ header: 'C' }] },
      ],
    }
    expect(parseContextFeeds(result, feeds)).toEqual({
      sheets: ['Q1', 'Q2'],
      columns: { Q1: ['A', 'B'], Q2: ['C'] },
    })
  })

  it('skips feeds whose list field is absent', () => {
    expect(parseContextFeeds({}, feeds)).toEqual({})
  })
})

describe('contextFeedOptions', () => {
  const parsed = {
    sheets: ['Q1', 'Q2'],
    columns: { Q1: ['A', 'B'], Q2: ['C'] },
  }

  it('returns the keyed bucket for a matching row key', () => {
    expect(contextFeedOptions(parsed, { set: 'columns', keyedBy: 'sheetName' }, 'Q1'))
      .toEqual(['A', 'B'])
  })

  it('falls back to the union when the key is unset', () => {
    expect(contextFeedOptions(parsed, { set: 'columns', keyedBy: 'sheetName' }, undefined))
      .toEqual(['A', 'B', 'C'])
  })

  it('serves flat lists and unknown sets', () => {
    expect(contextFeedOptions(parsed, { set: 'sheets' })).toEqual(['Q1', 'Q2'])
    expect(contextFeedOptions(parsed, undefined)).toEqual([])
  })
})

describe('contextRowFieldOptions', () => {
  const feeds = {
    sheets: ['Q1', 'Q2'],
    columns: { Q1: ['A', 'B'], Q2: ['C'] },
  }

  it('prefers the explicit context declaration over legacy annotations', () => {
    expect(contextRowFieldOptions(feeds, {
      fromContext: { set: 'columns', keyedBy: 'sheetName' },
      legacySource: 'workbook-columns',
      row: { sheetName: 'Q2' },
    })).toEqual(['C'])
  })

  it('legacy workbook-sheets takes the flat feed', () => {
    expect(contextRowFieldOptions(feeds, { legacySource: 'workbook-sheets' })).toEqual(['Q1', 'Q2'])
  })

  it('legacy workbook-columns keys by row sheetName with union fallback', () => {
    expect(contextRowFieldOptions(feeds, { legacySource: 'workbook-columns', row: { sheetName: 'Q1' } }))
      .toEqual(['A', 'B'])
    expect(contextRowFieldOptions(feeds, { legacySource: 'workbook-columns', row: {} }))
      .toEqual(['A', 'B', 'C'])
  })
})
