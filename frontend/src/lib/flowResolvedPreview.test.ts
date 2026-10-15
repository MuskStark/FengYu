import { describe, expect, it } from 'vitest'
import { resolveArgsPreview, resolveFieldValuePreview } from './flowResolvedPreview'

const upstream = [
  {
    id: 'node_1',
    args: { path: '/in/report.xlsx', mode: 'fast' },
    lastRun: { rows: [{ name: 'Ada' }, { name: 'Lin' }], count: 2 },
    inputs: [
      { path: '.path', name: 'path', title: 'Path', type: 'string' as const, examples: [] },
      { path: '.mode', name: 'mode', title: 'Mode', type: 'string' as const, examples: [] },
    ],
    outputs: [
      { path: '.rows', name: 'rows', title: 'Rows', type: 'array' as const, examples: [] },
      { path: '.count', name: 'count', title: 'Count', type: 'number' as const, examples: [] },
    ],
  },
  {
    id: 'node_2',
    args: {},
    lastRun: undefined,
    inputs: [],
    outputs: [
      { path: '.label', name: 'label', title: 'Label', type: 'string' as const, examples: ['hello'] },
    ],
  },
]

const workflowInputs = [
  { name: 'topic', default: 'quarterly report' },
  { name: 'pending', example: 'example value' },
]

describe('resolveArgsPreview', () => {
  it('resolves input-channel references from the upstream CONFIGURED args, not its result', () => {
    const preview = resolveArgsPreview(
      { source: '{{node.node_1.input.path}}', mode: '{{node.node_1.input.mode}}' },
      upstream,
      workflowInputs,
    )
    expect(preview.value).toEqual({ source: '/in/report.xlsx', mode: 'fast' })
  })

  it('resolves exact node references to last-run values', () => {
    const preview = resolveArgsPreview(
      { count: '{{node.node_1.result.count}}', names: '{{node.node_1.result.rows}}' },
      upstream,
      workflowInputs,
    )
    expect(preview.value).toEqual({ count: 2, names: [{ name: 'Ada' }, { name: 'Lin' }] })
    expect(preview.unresolved).toEqual([])
  })

  it('degrades to declared examples when the upstream never ran', () => {
    const preview = resolveArgsPreview({ label: '{{node.node_2.result.label}}' }, upstream, workflowInputs)
    expect(preview.value).toEqual({ label: 'hello' })
  })

  it('substitutes references embedded in longer strings', () => {
    const preview = resolveArgsPreview(
      { message: 'count={{node.node_1.result.count}}!' },
      upstream,
      workflowInputs,
    )
    expect(preview.value).toEqual({ message: 'count=2!' })
  })

  it('binds exact workflow-input references to defaults, then examples', () => {
    const preview = resolveArgsPreview(
      { a: '{{inputs.topic}}', b: '{{inputs.pending}}' },
      [],
      workflowInputs,
    )
    expect(preview.value).toEqual({ a: 'quarterly report', b: 'example value' })
  })

  it('collects unresolved references and keeps their source text', () => {
    const preview = resolveArgsPreview(
      { x: '{{node.node_9.result.missing}}' },
      upstream,
      workflowInputs,
    )
    expect(preview.value).toEqual({ x: '{{node.node_9.result.missing}}' })
    expect(preview.unresolved).toEqual(['{{node.node_9.result.missing}}'])
  })

  it('walks nested structures', () => {
    const preview = resolveArgsPreview(
      { list: [{ n: '{{node.node_1.result.count}}' }], nested: { deep: '{{inputs.topic}}' } },
      upstream,
      workflowInputs,
    )
    expect(preview.value).toEqual({ list: [{ n: 2 }], nested: { deep: 'quarterly report' } })
  })
})

describe('resolveFieldValuePreview', () => {
  it('passes literals and non-strings through as resolved', () => {
    expect(resolveFieldValuePreview('plain', upstream, workflowInputs)).toEqual({ resolved: true, value: 'plain' })
    expect(resolveFieldValuePreview(42, upstream, workflowInputs)).toEqual({ resolved: true, value: 42 })
  })

  it('resolves a bound reference to the upstream last-run value', () => {
    expect(resolveFieldValuePreview('{{node.node_1.result.count}}', upstream, workflowInputs))
      .toEqual({ resolved: true, value: 2 })
  })

  it('resolves workflow-input references through default then example', () => {
    expect(resolveFieldValuePreview('{{inputs.topic}}', upstream, workflowInputs))
      .toEqual({ resolved: true, value: 'quarterly report' })
    expect(resolveFieldValuePreview('{{inputs.pending}}', upstream, workflowInputs))
      .toEqual({ resolved: true, value: 'example value' })
  })

  it('flags unresolved references and keeps the literal pending', () => {
    const pending = resolveFieldValuePreview('{{node.node_9.result.x}}', upstream, workflowInputs)
    expect(pending.resolved).toBe(false)
    expect(pending.value).toBe('{{node.node_9.result.x}}')
  })

  it('substitutes embedded references inside an expression string', () => {
    const mixed = resolveFieldValuePreview('count={{node.node_1.result.count}} topic={{inputs.topic}}', upstream, workflowInputs)
    expect(mixed.resolved).toBe(true)
    expect(mixed.value).toBe('count=2 topic=quarterly report')
  })
})
