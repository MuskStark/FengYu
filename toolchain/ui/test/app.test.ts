import { defineComponent } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { mountFengYuApp } from '../src'

describe('mountFengYuApp', () => {
  it('owns mount, environment binding, and idempotent teardown', async () => {
    const target = document.createElement('div')
    document.body.appendChild(target)
    const unsubscribe = vi.fn()
    const disposeClient = vi.fn()
    const environments: unknown[] = []
    const client = {
      ready: vi.fn().mockResolvedValue({ theme: 'light', locale: 'zh-CN' }),
      on: vi.fn().mockReturnValue(unsubscribe),
      dispose: disposeClient,
    }
    const dispose = await mountFengYuApp({
      root: defineComponent({ template: '<p>ready</p>' }),
      client: client as never,
      target,
      onEnvironment: value => environments.push(value),
    })
    expect(target.textContent).toBe('ready')
    expect(environments).toEqual([{ theme: 'light', locale: 'zh-CN' }])

    dispose()
    dispose()
    expect(target.textContent).toBe('')
    expect(unsubscribe).toHaveBeenCalledOnce()
    expect(disposeClient).toHaveBeenCalledOnce()
  })
})
