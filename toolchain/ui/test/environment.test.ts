import { describe, expect, it, vi } from 'vitest'
import { bindFengYuEnvironment, createFengYuVuetify } from '../src'
import { HOST_CAPABILITIES, PROTOCOL_VERSION } from '@infinia/plugin-sdk'

const readyEnvironment = {
  protocolVersion: PROTOCOL_VERSION,
  theme: 'light' as const,
  locale: 'zh-CN',
  platform: 'web' as const,
  capabilities: HOST_CAPABILITIES,
}

it('applies ready state, reacts to environment events, and unsubscribes', async () => {
  let environmentHandler: ((value: unknown) => void) | undefined
  const stop = vi.fn()
  const client = {
    ready: vi.fn().mockResolvedValue(readyEnvironment),
    on: vi.fn((_event, handler) => { environmentHandler = handler; return stop }),
  }
  const vuetify = createFengYuVuetify()
  const dispose = await bindFengYuEnvironment(vuetify, client as never)
  expect(vuetify.theme.global.name.value).toBe('fengyuCodexLight')
  environmentHandler?.({ theme: 'dark', locale: 'en' })
  expect(vuetify.theme.global.name.value).toBe('fengyuCodexDark')
  dispose()
  expect(stop).toHaveBeenCalledOnce()
})

it('merges partial environment events and forwards the normalized state', async () => {
  let environmentHandler: ((value: unknown) => void) | undefined
  const states: unknown[] = []
  const client = {
    ready: vi.fn().mockResolvedValue(readyEnvironment),
    on: vi.fn((_event, handler) => { environmentHandler = handler; return vi.fn() }),
  }
  const vuetify = createFengYuVuetify()
  await bindFengYuEnvironment(vuetify, client as never, { onEnvironment: value => states.push(value) })
  environmentHandler?.({ locale: 'en' })
  expect(vuetify.theme.global.name.value).toBe('fengyuCodexLight')
  expect(states.at(-1)).toEqual({ ...readyEnvironment, locale: 'en' })
})
