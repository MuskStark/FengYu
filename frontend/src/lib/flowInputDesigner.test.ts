import { describe, expect, it } from 'vitest'
import {
  buildDesignerSchemaText,
  designerNameProblem,
  normalizeDesignerName,
  parseDesignerFields,
} from './flowInputDesigner'

describe('parseDesignerFields → buildDesignerSchemaText round-trip', () => {
  it('round-trips every designer type with its facets', () => {
    const schemaText = JSON.stringify({
      type: 'object',
      properties: {
        plain: { type: 'string' },
        paragraph: { type: 'string', 'x-fengyu-multiline': true },
        count: { type: 'number', default: 3 },
        flag: { type: 'boolean', default: true },
        choice: { type: 'string', enum: ['a', 'b'] },
        upload: { type: 'string', format: 'fengyu-file', 'x-fengyu-file-access': 'read' },
        outDir: { type: 'string', format: 'fengyu-directory', 'x-fengyu-file-access': 'read-write' },
        list: { type: 'array' },
        blob: { type: 'object' },
      },
      required: ['plain'],
    }, null, 2)
    const fields = parseDesignerFields(schemaText)
    expect(fields.map((field) => `${field.name}:${field.designerType}`)).toEqual([
      'plain:string',
      'paragraph:textarea',
      'count:number',
      'flag:boolean',
      'choice:select',
      'upload:file',
      'outDir:directory',
      'list:array',
      'blob:object',
    ])
    // The rebuild always materializes display titles, so the contract is the
    // DESIGNER-level round-trip: re-parsing the rebuilt text yields the same
    // rows (types, defaults, required, file access) — not byte-identical JSON.
    const rebuilt = parseDesignerFields(buildDesignerSchemaText(schemaText, fields))
    expect(rebuilt.map((field) => `${field.name}:${field.designerType}:${field.required}:${field.defaultText}`))
      .toEqual(fields.map((field) => `${field.name}:${field.designerType}:${field.required}:${field.defaultText}`))
    expect(rebuilt.map((field) => field.fileAccess)).toEqual(fields.map((field) => field.fileAccess))
  })

  it('preserves annotations the designer does not model', () => {
    const schemaText = JSON.stringify({
      type: 'object',
      properties: {
        weird: { type: 'number', enum: [1, 2], 'x-fengyu-auto': true, description: 'keep me' },
      },
    })
    const fields = parseDesignerFields(schemaText)
    // enum-on-number is NOT designer-owned — a title edit must keep it.
    fields[0]!.title = 'Renamed'
    const next = JSON.parse(buildDesignerSchemaText(schemaText, fields))
    expect(next.properties.weird).toMatchObject({
      type: 'number', enum: [1, 2], 'x-fengyu-auto': true, description: 'keep me', title: 'Renamed',
    })
  })

  it('clears stale facets on type switches', () => {
    const schemaText = JSON.stringify({
      type: 'object',
      properties: {
        wasFile: { type: 'string', format: 'fengyu-file', 'x-fengyu-file-access': 'read' },
        wasSelect: { type: 'string', enum: ['x'] },
      },
    })
    const fields = parseDesignerFields(schemaText)
    fields[0]!.designerType = 'number'
    fields[1]!.designerType = 'string'
    const next = JSON.parse(buildDesignerSchemaText(schemaText, fields))
    expect(next.properties.wasFile).toEqual({ type: 'number', title: 'Was File' })
    expect(next.properties.wasSelect).toEqual({ type: 'string', title: 'Was Select' })
  })

  it('persists example/help text and structured defaults', () => {
    const fields = parseDesignerFields('{}')
    fields.push(
      { name: 'topic', originalName: '', title: '', designerType: 'string', fileAccess: 'read', required: false, options: [], example: 'sales', helpText: 'what to summarize', hasDefault: true, defaultText: 'Q1' },
      { name: 'limits', originalName: '', title: '', designerType: 'array', fileAccess: 'read', required: true, options: [], example: '', helpText: '', hasDefault: true, defaultText: '[1,2]' },
    )
    const next = JSON.parse(buildDesignerSchemaText('{}', fields))
    expect(next.properties.topic).toMatchObject({ type: 'string', default: 'Q1', examples: ['sales'], description: 'what to summarize' })
    expect(next.properties.limits).toMatchObject({ type: 'array', default: [1, 2] })
    expect(next.required).toEqual(['limits'])
  })

  it('drops an unparseable structured default instead of persisting text', () => {
    const fields = parseDesignerFields('{}')
    fields.push({ name: 'blob', originalName: '', title: '', designerType: 'object', fileAccess: 'read', required: false, options: [], example: '', helpText: '', hasDefault: true, defaultText: '{oops' })
    const next = JSON.parse(buildDesignerSchemaText('{}', fields))
    expect(next.properties.blob.default).toBeUndefined()
  })
})

describe('name helpers', () => {
  const fields = parseDesignerFields(JSON.stringify({ properties: { a: { type: 'string' } } }))

  it('normalizes to the reference-grammar alphabet', () => {
    expect(normalizeDesignerName('my input!')).toBe('myinput')
    expect(normalizeDesignerName(' ok_1 ')).toBe('ok_1')
  })

  it('detects empty, invalid, and duplicate names', () => {
    expect(designerNameProblem(fields, 1, '')).toBe('empty')
    expect(designerNameProblem(fields, 1, 'bad name')).toBe('invalid')
    expect(designerNameProblem(fields, 1, 'a')).toBe('duplicate')
    expect(designerNameProblem(fields, 1, 'fresh')).toBeNull()
  })
})
