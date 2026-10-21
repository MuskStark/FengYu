import { describe, expect, it } from 'vitest'
import {
  connectionDraftFor,
  parseConnectionAnnotation,
  profileValueFor,
  selectedProfileId,
} from './connectionField'
import type { ConnectionProfile } from '@/services/connections'

const profile = (id: string, fields: Record<string, string>): ConnectionProfile => ({
  id, name: id, type: 'email', fields, createdAt: '', updatedAt: '',
})

describe('parseConnectionAnnotation', () => {
  it('reads and normalizes the type, with an optional field', () => {
    expect(parseConnectionAnnotation({ 'x-fengyu-connection-type': ' Email ' }))
      .toEqual({ type: 'email', field: null })
    expect(parseConnectionAnnotation({
      'x-fengyu-connection-type': 'api-key', 'x-fengyu-connection-field': 'token',
    })).toEqual({ type: 'api-key', field: 'token' })
  })

  it('ignores fields without the annotation or with an empty type', () => {
    expect(parseConnectionAnnotation({ type: 'string' })).toBeNull()
    expect(parseConnectionAnnotation({ 'x-fengyu-connection-type': '!!!' })).toBeNull()
    expect(parseConnectionAnnotation(null)).toBeNull()
  })
})

describe('profileValueFor', () => {
  it('uses the annotated field when the profile has it, else the first field', () => {
    const p = profile('p', { account: 'a@x', token: 't0k' })
    expect(profileValueFor(p, { type: 'email', field: 'token' })).toBe('t0k')
    expect(profileValueFor(p, { type: 'email', field: 'missing' })).toBe('a@x')
    expect(profileValueFor(p, { type: 'email', field: null })).toBe('a@x')
  })

  it('returns null for profiles with nothing usable', () => {
    expect(profileValueFor(profile('p', {}), { type: 'email', field: null })).toBeNull()
    expect(profileValueFor(profile('p', { token: '' }), { type: 'email', field: 'token' })).toBeNull()
  })
})

describe('selectedProfileId', () => {
  const profiles = [profile('a', { token: 'one' }), profile('b', { token: 'two' })]
  const annotation = { type: 'email', field: 'token' }

  it('resolves the profile whose value is currently in the argument', () => {
    expect(selectedProfileId(profiles, annotation, 'two')).toBe('b')
  })

  it('treats anything else as manual', () => {
    expect(selectedProfileId(profiles, annotation, 'three')).toBeNull()
    expect(selectedProfileId(profiles, annotation, undefined)).toBeNull()
  })
})

describe('connectionDraftFor', () => {
  it('shapes the in-place create request around the annotated field', () => {
    expect(connectionDraftFor({ type: 'api-key', field: 'token' }, ' Work key ', 'xyz'))
      .toEqual({ name: 'Work key', type: 'api-key', fields: { token: 'xyz' } })
    expect(connectionDraftFor({ type: 'email', field: null }, 'Mail', 'v').fields)
      .toEqual({ value: 'v' })
  })
})
