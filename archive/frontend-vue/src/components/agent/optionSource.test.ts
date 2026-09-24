import { describe, expect, it } from 'vitest'
import {
  ContextFeedController,
  contextFeedOptions,
  contextRowFieldOptions,
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

describe('contextRowFieldOptions', () => {
  const parsed = parseContextFeeds(analyzeResult, {
    columns: { list: 'sheets', key: 'name', items: 'columns', itemField: 'header' },
    sheets: { list: 'sheets', item: 'name' },
  })

  it('uses the explicit sheet feed without mixing in legacy column candidates', () => {
    expect(contextRowFieldOptions(parsed, {
      fromContext: { set: 'sheets' },
      legacySource: 'workbook-sheets',
      row: { sheetName: '华东' },
    })).toEqual(['华东', '汇总'])
  })

  it('finds legacy sheet and column feeds by shape rather than object key order', () => {
    expect(contextRowFieldOptions(parsed, { legacySource: 'workbook-sheets' }))
      .toEqual(['华东', '汇总'])
    expect(contextRowFieldOptions(parsed, {
      legacySource: 'workbook-columns',
      row: { sheetName: '华东' },
    })).toEqual(['城市', '金额'])
  })
})

describe('ContextFeedController (§8.4 concurrency + invalidation)', () => {
  const CTX = { method: 'analyze', feeds: {} } as Parameters<ContextFeedController['start']>[1]
  const feeds = (names: string[]) => ({ sheets: names })

  it('drops a stale response so a slow older analyze never overwrites a newer one', async () => {
    let resolveFirst!: (v: Record<string, string[]>) => void
    const controller = new ContextFeedController((value) => {
      if (value === 'old') return new Promise((resolve) => { resolveFirst = resolve })
      return Promise.resolve(feeds(['new-sheet']))
    })
    const first = controller.start('old', CTX)
    const second = controller.start('new', CTX)
    await second
    resolveFirst(feeds(['old-sheet']))
    await first
    expect(controller.state.feeds).toEqual(feeds(['new-sheet']))
    expect(controller.state.running).toBe(false)
  })

  it('invalidate clears feeds immediately and marks candidates as needing re-analysis', () => {
    const controller = new ContextFeedController(() => Promise.resolve(feeds(['a'])))
    controller.state.feeds = feeds(['a'])
    controller.state.stale = false
    controller.invalidate()
    expect(controller.state.feeds).toEqual({})
    expect(controller.state.stale).toBe(true)
    expect(controller.state.error).toBeNull()
  })

  it('a failed analysis clears candidates and records the error, never restoring stale ones', async () => {
    const controller = new ContextFeedController(() => Promise.reject(new Error('boom')))
    controller.state.feeds = feeds(['stale'])
    await controller.start('x', CTX)
    expect(controller.state.feeds).toEqual({})
    expect(controller.state.error).toBe('boom')
    expect(controller.state.running).toBe(false)
  })

  it('a successful run clears the stale flag set by invalidate', async () => {
    const controller = new ContextFeedController(() => Promise.resolve(feeds(['s'])))
    controller.invalidate()
    expect(controller.state.stale).toBe(true)
    await controller.start('x', CTX)
    expect(controller.state.stale).toBe(false)
    expect(controller.state.feeds).toEqual(feeds(['s']))
  })

  it('notify fires onChange listeners on state transitions', async () => {
    const events: string[] = []
    const controller = new ContextFeedController(() => Promise.resolve(feeds(['s'])))
    controller.onChange(() => events.push('change'))
    await controller.start('x', CTX)
    expect(events.length).toBeGreaterThanOrEqual(2) // start + settle
  })

  it('reset (node switch) clears feeds and the stale flag together', () => {
    const controller = new ContextFeedController(() => Promise.resolve(feeds(['a'])))
    controller.state.feeds = feeds(['a'])
    controller.state.stale = true
    controller.state.error = 'boom'
    controller.reset()
    expect(controller.state).toEqual({ feeds: {}, running: false, error: null, stale: false })
  })

  it('invalidate during an in-flight start drops the pending response entirely', async () => {
    let resolveRun!: (v: Record<string, string[]>) => void
    const controller = new ContextFeedController(() => new Promise((resolve) => { resolveRun = resolve }))
    const pending = controller.start('x', CTX)
    controller.invalidate() // the source value changed while the analyze was in flight
    resolveRun(feeds(['late']))
    await pending
    expect(controller.state.feeds).toEqual({})
    expect(controller.state.running).toBe(false)
    expect(controller.state.stale).toBe(true)
  })

  it('a stale FAILURE never clobbers a newer successful run', async () => {
    let rejectOld!: (e: Error) => void
    const controller = new ContextFeedController((value) => {
      if (value === 'old') return new Promise((_, reject) => { rejectOld = reject })
      return Promise.resolve(feeds(['fresh']))
    })
    const first = controller.start('old', CTX)
    const second = controller.start('new', CTX)
    await second
    rejectOld(new Error('old failed'))
    await first.catch(() => {})
    expect(controller.state.feeds).toEqual(feeds(['fresh']))
    expect(controller.state.error).toBeNull()
  })

  it('onChange unsubscribe stops notifications for that listener', async () => {
    let fired = 0
    const controller = new ContextFeedController(() => Promise.resolve(feeds(['s'])))
    const unsubscribe = controller.onChange(() => { fired++ })
    unsubscribe()
    await controller.start('x', CTX)
    expect(fired).toBe(0)
  })
})
