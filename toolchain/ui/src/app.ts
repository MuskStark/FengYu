import { createApp, type Component, type Plugin } from 'vue'
import type { Environment, FengYuClient } from '@infinia/plugin-sdk'
import { provideFengYuClient } from './client'
import { bindFengYuEnvironment, createFengYuVuetify } from './createFengYuVuetify'

export type MountFengYuAppOptions = {
  root: Component
  client: FengYuClient
  target?: string | Element
  plugins?: Plugin[]
  onEnvironment?: (environment: Environment) => void
  onReadyError?: (error: unknown) => void
}

/** Bootstrap and own the complete iframe UI lifecycle. Returns an idempotent disposer. */
export async function mountFengYuApp(options: MountFengYuAppOptions): Promise<() => void> {
  const vuetify = createFengYuVuetify()
  const disposeEnvironment = await bindFengYuEnvironment(vuetify, options.client, {
    onEnvironment: options.onEnvironment,
    onReadyError: options.onReadyError,
  })
  const app = createApp(options.root)
  try {
    provideFengYuClient(app, options.client)
    for (const plugin of options.plugins ?? []) app.use(plugin)
    app.use(vuetify)
    app.mount(options.target ?? '#app')
  } catch (error) {
    disposeEnvironment()
    options.client.dispose()
    throw error
  }

  let disposed = false
  const dispose = () => {
    if (disposed) return
    disposed = true
    window.removeEventListener('pagehide', dispose)
    try { app.unmount() }
    finally {
      disposeEnvironment()
      options.client.dispose()
    }
  }
  window.addEventListener('pagehide', dispose, { once: true })
  return dispose
}
