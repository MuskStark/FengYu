import { describe, expect, it } from 'vitest'
import {
  humanizeWorkflowField,
  humanizeWorkflowToolName,
  missingRequiredWorkflowInputs,
  reconcileWorkflowArguments,
  topologicallySortWorkflowNodes,
  workflowInputSummaries,
  workflowOutputSummaries,
  workflowToolCategory,
  wouldCreateCycle,
} from './workflow'

describe('workflow graph helpers', () => {
  const nodes = [
    { id: 'b', position: { x: 300, y: 0 } },
    { id: 'a', position: { x: 0, y: 0 } },
    { id: 'c', position: { x: 600, y: 0 } },
  ]

  it('sorts connected nodes in dependency order', () => {
    const sorted = topologicallySortWorkflowNodes(nodes, [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
    ])

    expect(sorted?.map((node) => node.id)).toEqual(['a', 'b', 'c'])
  })

  it('returns null for a cyclic workflow', () => {
    const sorted = topologicallySortWorkflowNodes(nodes, [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'a' },
    ])

    expect(sorted).toBeNull()
  })

  it('rejects an edge that would close a cycle', () => {
    expect(wouldCreateCycle([
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
    ], 'c', 'a')).toBe(true)
    expect(wouldCreateCycle([
      { source: 'a', target: 'b' },
    ], 'b', 'c')).toBe(false)
  })
})

describe('reconcileWorkflowArguments', () => {
  it('preserves authored values and seeds newly required inputs', () => {
    const result = JSON.parse(reconcileWorkflowArguments(
      '{"existing":"kept","legacy":true}',
      '{"type":"object","properties":{"existing":{"type":"string"},"count":{"type":"integer","default":3}},"required":["existing","count"]}',
    ))
    expect(result).toEqual({ existing: 'kept', legacy: true, count: 3 })
  })

  it('does not destroy invalid advanced JSON during a catalog refresh', () => {
    expect(reconcileWorkflowArguments('{unfinished', '{"type":"object"}')).toBe('{unfinished')
  })
})

describe('workflow schema presentation', () => {
  const inputSchema = JSON.stringify({
    type: 'object',
    properties: {
      sourceFile: { type: 'string', description: 'Workbook path' },
      split_mode: { type: 'string', title: 'Split method', enum: ['sheet', 'column'] },
      sendEmail: { type: 'boolean' },
    },
    required: ['sourceFile', 'split_mode'],
  })

  it('turns technical names into readable labels', () => {
    expect(humanizeWorkflowField('sourceFile')).toBe('Source File')
    expect(humanizeWorkflowField('split_mode')).toBe('Split mode')
    expect(humanizeWorkflowField('apiUrl')).toBe('API URL')
    expect(humanizeWorkflowToolName('browser_navigate')).toBe('Browser Navigate')
    expect(workflowToolCategory({ name: 'browser_navigate', pluginId: null })).toBe('browser')
  })

  it('summarizes manual values, workflow inputs, and node outputs', () => {
    const summaries = workflowInputSummaries(inputSchema, JSON.stringify({
      sourceFile: '{{inputs.workbookPath}}',
      split_mode: '{{node.node_2.result.mode}}',
      sendEmail: false,
    }))
    expect(summaries).toEqual([
      expect.objectContaining({ label: 'Source File', source: 'workflow', configured: true, value: 'Workbook Path' }),
      expect.objectContaining({ label: 'Split method', source: 'node', configured: true, value: 'node_2 · Mode' }),
      expect.objectContaining({ label: 'Send Email', source: 'manual', configured: true, value: 'false' }),
    ])
  })

  it('reports missing required values without treating false or zero as empty', () => {
    expect(missingRequiredWorkflowInputs(inputSchema, '{"sourceFile":"","split_mode":"sheet","sendEmail":false}'))
      .toEqual(['sourceFile'])
  })

  it('exposes user-facing output labels while hiding protocol fields', () => {
    const outputs = workflowOutputSummaries(JSON.stringify({
      type: 'object',
      properties: {
        success: { type: 'boolean' },
        summary: { type: 'string' },
        fileCount: { type: 'integer' },
        output_path: { type: 'string', title: 'Saved file' },
      },
    }))
    expect(outputs).toEqual([
      { name: 'fileCount', label: 'File Count', type: 'integer' },
      { name: 'output_path', label: 'Saved file', type: 'string' },
    ])
  })
})
