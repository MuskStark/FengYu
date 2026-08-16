import { describe, expect, it } from 'vitest'
import {
  contextFeedOptions,
  mapCatalogOptions,
  parseContextFeeds,
  renderContextParams,
} from './optionSource'

const analyzeResult = {
  success: true,
  sheets: [
    { name: '华东', columns: [{ header: '城市' }, { header: '金额' }] },
    { name: '汇总', columns: [{ header: '总计' }] },
  ],
}

describe('mapCatalogOptions', () => {
  it('maps a plugin list result onto value/label pairs with the secondary label', () => {
    const options = mapCatalogOptions(
      { accounts: [{ id: 3, email: 'a@x.com', displayName: 'Alice' }, { id: 7, email: 'b@x.com' }] },
      { items: 'accounts', value: 'id', label: 'email', labelSecondary: 'displayName' },
    )
    expect(options).toEqual([
      { value: 3, label: 'a@x.com · Alice' },
      { value: 7, label: 'b@x.com' },
    ])
  })

  it('tolerates missing lists and non-object entries', () => {
    expect(mapCatalogOptions({ tags: 'nope' }, { items: 'tags', value: 'id', label: 'name' })).toEqual([])
    expect(mapCatalogOptions([1, 2], { value: 'id', label: 'name' })).toEqual([])
  })
})

describe('renderContextParams', () => {
  it('templates {{value}} with the triggering input value, verbatim otherwise', () => {
    expect(renderContextParams({ sourceFile: '{{value}}', mode: 'full' }, '/tmp/a.xlsx')).toEqual({
      sourceFile: '/tmp/a.xlsx',
      mode: 'full',
    })
    expect(renderContextParams(undefined, 'x')).toEqual({})
  })
})

describe('parseContextFeeds', () => {
  const feeds = {
    sheets: { list: 'sheets', item: 'name' },
    columns: { list: 'sheets', key: 'name', items: 'columns', itemField: 'header' },
  }

  it('flat feeds yield the entry field list; keyed feeds group by the key field', () => {
    const parsed = parseContextFeeds(analyzeResult, feeds)
    expect(parsed.sheets).toEqual(['华东', '汇总'])
    expect(parsed.columns).toEqual({ 华东: ['城市', '金额'], 汇总: ['总计'] })
  })

  it('missing lists and malformed entries are skipped, not fatal', () => {
    expect(parseContextFeeds({}, feeds)).toEqual({})
    expect(parseContextFeeds({ sheets: [null, 5, { name: 'A' }] }, feeds).sheets).toEqual(['A'])
  })
})

describe('contextFeedOptions', () => {
  const parsed = parseContextFeeds(analyzeResult, {
    sheets: { list: 'sheets', item: 'name' },
    columns: { list: 'sheets', key: 'name', items: 'columns', itemField: 'header' },
  })

  it('a keyed reference selects the bucket matching the row key', () => {
    expect(contextFeedOptions(parsed, { set: 'columns', keyedBy: 'sheetName' }, '华东'))
      .toEqual(['城市', '金额'])
  })

  it('an unpicked row key falls back to the union of all buckets', () => {
    const union = contextFeedOptions(parsed, { set: 'columns', keyedBy: 'sheetName' }, '')
    expect(union).toEqual(['城市', '金额', '总计'])
  })

  it('a flat reference returns the list; unknown sets return empty', () => {
    expect(contextFeedOptions(parsed, { set: 'sheets' }, undefined)).toEqual(['华东', '汇总'])
    expect(contextFeedOptions(parsed, { set: 'nope' }, undefined)).toEqual([])
    expect(contextFeedOptions(parsed, undefined, undefined)).toEqual([])
  })
})
