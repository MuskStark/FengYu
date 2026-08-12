import { defineComponent } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { mountFengYuApp } from '../src'
import { HOST_CAPABILITIES, PROTOCOL_VERSION } from '@infinia/plugin-sdk'

describe('mountFengYuApp', () => {
  it('owns mount, environment binding, and idempotent teardown', async () => {
    const target = document.createElement('div')
    document.body.appendChild(target)
    const unsubscribe = vi.fn()
    const disposeClient = vi.fn()
    const environments: unknown[] = []
    const client = {
      ready: vi.fn().mockResolvedValue({
        protocolVersion: PROTOCOL_VERSION,
        theme: 'light',
        locale: 'zh-CN',
        platform: 'web',
        capabilities: HOST_CAPABILITIES,
        pluginId: 'fan.summer.test',
        pluginVersion: '1.0.0',
        permissions: [],
      }),
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
    expect(environments).toEqual([{
      protocolVersion: PROTOCOL_VERSION,
      theme: 'light',
      locale: 'zh-CN',
      platform: 'web',
      capabilities: HOST_CAPABILITIES,
      pluginId: 'fan.summer.test',
      pluginVersion: '1.0.0',
      permissions: [],
    }])

    dispose()
    dispose()
    expect(target.textContent).toBe('')
    expect(unsubscribe).toHaveBeenCalledOnce()
    expect(disposeClient).toHaveBeenCalledOnce()
  })
})
