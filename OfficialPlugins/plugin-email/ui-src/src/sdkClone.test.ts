import { describe, expect, it } from 'vitest'
import { cloneableParams } from './sdk'

describe('SDK parameter boundary', () => {
  it('turns store state into structured-clone-safe JSON data', () => {
    const params = { query: '', tagIds: [3, 7], nested: { enabled: true } }
    const plain = cloneableParams(params)

    expect(plain).toEqual({ query: '', tagIds: [3, 7], nested: { enabled: true } })
    expect(structuredClone(plain)).toEqual(plain)
  })
})
