import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import { md3 } from 'vuetify/blueprints'
import { aliases, mdi } from 'vuetify/iconsets/mdi'
import { en, zhHans } from 'vuetify/locale'
import type { FengYuClient, Environment } from '@fengyu/plugin-sdk'
import { fengyuCodexDark, fengyuCodexLight } from './theme'
import { fengyuDefaults } from './defaults'

// Ship the Vuetify base stylesheet and the MDI icon font with the library.
import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import './styles/codex.css'

export type FengYuVuetifyOptions = { theme?: string; locale?: string }

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
): Promise<() => void> {
  const apply = (environment: Partial<Environment>) => {
    // theme.change(name) is the non-deprecated Vuetify 3.12 API; assigning
    // `theme.global.name.value` triggers a `[Vuetify UPGRADE]` console warning
    // on every call (and this runs on every `environment` event). `locale.current`
    // has no such deprecation, so its assignment stays.
    vuetify.theme.change(themeName(environment.theme))
    vuetify.locale.current.value = localeName(environment.locale)
  }
  apply(await client.ready())
  return client.on('environment', (value) => apply(value as Partial<Environment>))
}
