/**
 * e2e entrypoint: mount the {@link Workbench} with a deterministic fake client.
 *
 * The fake client resolves stable data so Playwright screenshots are
 * reproducible — it never calls the real host SDK. The initial theme is taken
 * from the `?theme=dark|light` query string (default `dark`) so the screenshot
 * matrix can cover both themes without editing the fixture.
 */
import { createApp } from 'vue'
import { HOST_CAPABILITIES, PROTOCOL_VERSION, type FengYuClient, type Environment, type FileRef } from '@infinia/plugin-sdk'
import { createFengYuVuetify, provideFengYuClient } from '../src'
import Workbench from './Workbench.vue'

function initialTheme(): 'dark' | 'light' {
  const raw = new URLSearchParams(window.location.search).get('theme')
  return raw === 'light' ? 'light' : 'dark'
}

/** Deterministic fake client: resolves stable data, never touches the host. */
function fakeClient(): FengYuClient {
  const pick: FileRef = {
    id: 'file-seed',
    name: 'sales-2026.xlsx',
    kind: 'file',
    access: 'read',
    size: 12_345,
  }
  return {
    ready: async () =>
      ({ protocolVersion: PROTOCOL_VERSION, theme: initialTheme(), locale: 'en', platform: 'web', capabilities: HOST_CAPABILITIES }) as Environment,
    on: () => () => {},
    notify: async () => true,
    files: {
      open: async () => pick,
      inputDirectory: async () => null,
      outputDirectory: async () => null,
      export: async () => true,
    },
    invoke: async () => undefined,
    dispose: () => {},
    request: async () => undefined,
  } as unknown as FengYuClient
}

const app = createApp(Workbench)
app.use(createFengYuVuetify({ theme: initialTheme() }))
provideFengYuClient(app, fakeClient())
app.mount('#app')
