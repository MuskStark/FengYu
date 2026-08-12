import { describe, expect, it, vi } from 'vitest'
import { bindFengYuEnvironment, createFengYuVuetify } from '../src'
import { HOST_CAPABILITIES, PROTOCOL_VERSION } from '@infinia/plugin-sdk'

const readyEnvironment = {
  protocolVersion: PROTOCOL_VERSION,
  theme: 'light' as const,
  locale: 'zh-CN',
  platform: 'web' as const,
  capabilities: HOST_CAPABILITIES,
  pluginId: 'fan.summer.test',
  pluginVersion: '1.0.0',
  permissions: [] as string[],
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

it('does not lose environment events while the ready handshake is pending', async () => {
  let environmentHandler: ((value: unknown) => void) | undefined
  let resolveReady!: (value: typeof readyEnvironment) => void
  const client = {
    ready: vi.fn().mockReturnValue(new Promise<typeof readyEnvironment>((resolve) => { resolveReady = resolve })),
    on: vi.fn((_event, handler) => { environmentHandler = handler; return vi.fn() }),
  }
  const vuetify = createFengYuVuetify()
  const binding = bindFengYuEnvironment(vuetify, client as never)

  environmentHandler?.({ theme: 'light', locale: 'zh-CN' })
  expect(vuetify.theme.global.name.value).toBe('fengyuCodexLight')
  expect(vuetify.locale.current.value).toBe('zhHans')

  resolveReady(readyEnvironment)
  await binding
})
