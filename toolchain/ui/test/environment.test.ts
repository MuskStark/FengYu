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
