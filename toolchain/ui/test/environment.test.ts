import { describe, expect, it, vi } from 'vitest'
import { bindFengYuEnvironment, createFengYuVuetify } from '../src'

it('applies ready state, reacts to environment events, and unsubscribes', async () => {
  let environmentHandler: ((value: unknown) => void) | undefined
  const stop = vi.fn()
  const client = {
    ready: vi.fn().mockResolvedValue({ theme: 'light', locale: 'zh-CN' }),
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
    ready: vi.fn().mockResolvedValue({ theme: 'light', locale: 'zh-CN' }),
    on: vi.fn((_event, handler) => { environmentHandler = handler; return vi.fn() }),
  }
  const vuetify = createFengYuVuetify()
  await bindFengYuEnvironment(vuetify, client as never, { onEnvironment: value => states.push(value) })
  environmentHandler?.({ locale: 'en' })
  expect(vuetify.theme.global.name.value).toBe('fengyuCodexLight')
  expect(states.at(-1)).toEqual({ theme: 'light', locale: 'en' })
})
