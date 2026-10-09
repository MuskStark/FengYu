import { describe, expect, it } from 'vitest'
import { arrayItemParseError, parseManualNumber, validateManualField } from './flowFieldValidation'

describe('parseManualNumber', () => {
  it('accepts plain and decimal numbers', () => {
    expect(parseManualNumber('42')).toEqual({ ok: true, value: 42 })
    expect(parseManualNumber(' -3.5 ')).toEqual({ ok: true, value: -3.5 })
  })

  it('rejects garbage and non-finite text instead of coercing to 0', () => {
    expect(parseManualNumber('abc')).toEqual({ ok: false })
    expect(parseManualNumber('')).toEqual({ ok: false })
    expect(parseManualNumber('12px')).toEqual({ ok: false })
  })
})

describe('validateManualField', () => {
  it('passes empty values through (required-ness is node-level)', () => {
    expect(validateManualField({ type: 'string' }, undefined)).toBeNull()
    expect(validateManualField({ type: 'string' }, '')).toBeNull()
  })

  it('flags non-numeric input for number fields', () => {
    expect(validateManualField({ type: 'number' }, 'oops')).not.toBeNull()
    expect(validateManualField({ type: 'number' }, 7)).toBeNull()
  })

  it('enforces integer typing and numeric bounds', () => {
    expect(validateManualField({ type: 'integer' }, 1.5)).not.toBeNull()
    expect(validateManualField({ type: 'number', minimum: 2 }, 1)).not.toBeNull()
    expect(validateManualField({ type: 'number', maximum: 2 }, 3)).not.toBeNull()
    expect(validateManualField({ type: 'number', exclusiveMinimum: 0 }, 0)).not.toBeNull()
    expect(validateManualField({ type: 'number', exclusiveMaximum: 10 }, 10)).not.toBeNull()
    expect(validateManualField({ type: 'number', multipleOf: 0.5 }, 1.25)).not.toBeNull()
    expect(validateManualField({ type: 'number', minimum: 2 }, 4)).toBeNull()
  })

  it('enforces string constraints', () => {
    expect(validateManualField({ type: 'string', maxLength: 3 }, 'abcd')).not.toBeNull()
    expect(validateManualField({ type: 'string', pattern: '^\\d+$' }, '12a')).not.toBeNull()
    expect(validateManualField({ type: 'string', pattern: '^\\d+$' }, '12')).toBeNull()
  })

  it('checks enum membership for string values', () => {
    const schema = { type: 'string', enum: ['a', 'b'] }
    expect(validateManualField(schema, 'c')).not.toBeNull()
    expect(validateManualField(schema, 'a')).toBeNull()
    // {value,label} object options compare by value.
    expect(validateManualField({ type: 'string', enum: [{ value: 'x', label: 'X' }] }, 'x')).toBeNull()
  })

  it('ignores an invalid authored pattern rather than breaking input', () => {
    expect(validateManualField({ type: 'string', pattern: '([unclosed' }, 'anything')).toBeNull()
  })
})

describe('arrayItemParseError', () => {
  it('validates numeric array items', () => {
    expect(arrayItemParseError({ type: 'array', items: { type: 'number' } }, '1, oops')).not.toBeNull()
    expect(arrayItemParseError({ type: 'array', items: { type: 'integer' } }, '1.5')).not.toBeNull()
    expect(arrayItemParseError({ type: 'array', items: { type: 'number' } }, '1, 2')).toBeNull()
  })

  it('ignores string-typed arrays', () => {
    expect(arrayItemParseError({ type: 'array', items: { type: 'string' } }, 'a, b')).toBeNull()
  })
})
