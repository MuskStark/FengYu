import { reactive } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { client, cloneableParams, files } from './sdk'

describe('SDK parameter boundary', () => {
  it('turns nested Vue proxies into structured-clone-safe JSON data', () => {
    const params = reactive({ query: '', tagIds: [3, 7], nested: { enabled: true } })
    const plain = cloneableParams(params)

    expect(plain).toEqual({ query: '', tagIds: [3, 7], nested: { enabled: true } })
    expect(structuredClone(plain)).toEqual(plain)
  })
})

describe('files.openMany legacy-host normalization', () => {
  it('wraps a pre-4.0.4 host single FileRef into the array shape', async () => {
    const legacy = { id: 'f1', name: 'only.pdf', kind: 'file', access: 'read', size: 5 } as never
    const spy = vi.spyOn(client.files, 'openMany').mockResolvedValue(legacy)

    expect(await files.openMany()).toEqual([legacy])
    spy.mockRestore()
  })

  it('passes the multi-ref array and cancellation through unchanged', async () => {
    const refs = [
      { id: 'a', name: 'a.pdf', kind: 'file', access: 'read', size: 1 },
      { id: 'b', name: 'b.pdf', kind: 'file', access: 'read', size: 2 },
    ] as never[]
    const spy = vi.spyOn(client.files, 'openMany')
    spy.mockResolvedValue(refs)
    expect(await files.openMany()).toEqual(refs)
    spy.mockResolvedValue(null)
    expect(await files.openMany()).toBeNull()
    spy.mockRestore()
  })
})
