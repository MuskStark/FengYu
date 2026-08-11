import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import { md3 } from 'vuetify/blueprints'
import { aliases, mdi } from 'vuetify/iconsets/mdi'
import { en, zhHans } from 'vuetify/locale'
import { HOST_CAPABILITIES, PROTOCOL_VERSION, type FengYuClient, type Environment } from '@infinia/plugin-sdk'
import { fengyuCodexDark, fengyuCodexLight } from './theme'
import { fengyuDefaults } from './defaults'

// Ship Vuetify and FengYu styles; the build appends the external MDI font CSS
// import to dist/style.css so consuming plugin apps emit same-origin fonts.
import 'vuetify/styles'
import './styles/codex.css'

export type FengYuVuetifyOptions = { theme?: string; locale?: string }
export type FengYuEnvironmentBindingOptions = {
  onEnvironment?: (environment: Environment) => void
  onReadyError?: (error: unknown) => void
}

/** Map an Environment.theme value (or option) to the registered Vuetify theme name. */
export function themeName(value?: string): string {
  return value === 'light' ? 'fengyuCodexLight' : 'fengyuCodexDark'
}

/** Map an Environment.locale (BCP-47-ish) to a registered Vuetify locale id. */
export function localeName(value?: string): string {
  return value?.toLowerCase().startsWith('zh') ? 'zhHans' : 'en'
}

export function createFengYuVuetify(options: FengYuVuetifyOptions = {}): ReturnType<typeof createVuetify> {
  return createVuetify({
    blueprint: md3,
    components,
    directives,
    defaults: fengyuDefaults,
    icons: { defaultSet: 'mdi', aliases, sets: { mdi } },
    locale: { locale: localeName(options.locale), fallback: 'en', messages: { en, zhHans } },
    theme: {
      defaultTheme: themeName(options.theme),
      themes: { fengyuCodexDark, fengyuCodexLight },
    },
  })
}

/**
 * Connect a {@link FengYuClient} to a Vuetify instance: apply the current host
 * environment once, then react to `environment` events. Returns the client's
 * unsubscribe function so the caller can dispose the binding on teardown.
 */
export async function bindFengYuEnvironment(
  vuetify: ReturnType<typeof createFengYuVuetify>,
  client: FengYuClient,
  options: FengYuEnvironmentBindingOptions = {},
): Promise<() => void> {
  let current: Environment = {
    protocolVersion: PROTOCOL_VERSION,
    theme: 'dark',
    locale: 'en',
    platform: 'web',
    capabilities: HOST_CAPABILITIES,
  }
  const apply = (environment: Partial<Environment>) => {
    current = { ...current, ...environment }
    // theme.change(name) is the non-deprecated Vuetify 3.12 API; assigning
    // `theme.global.name.value` triggers a `[Vuetify UPGRADE]` console warning
    // on every call (and this runs on every `environment` event). `locale.current`
    // has no such deprecation, so its assignment stays.
    vuetify.theme.change(themeName(current.theme))
    vuetify.locale.current.value = localeName(current.locale)
    options.onEnvironment?.({ ...current })
  }
  // Await the host's ready handshake so theme/locale apply before first paint.
  // When there is no host (e.g. `vite` started standalone, outside any simulator),
  // ready() never gets a response and would block mount for the full 30s timeout,
  // leaving a blank page. Fall back to defaults after a short timeout so the UI at
  // least renders — invoke calls will still surface a clear error per-call.
  try {
    apply(await client.ready({ timeoutMs: 3_000 }))
  } catch (error) {
    apply({})
    options.onReadyError?.(error)
  }
  return client.on('environment', (value) => apply(value as Partial<Environment>))
}
