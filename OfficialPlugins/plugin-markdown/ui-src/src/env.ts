import { useFengYuI18n, type FengYuI18n } from '@infinia/plugin-ui'
import { pluginI18n } from './i18n'

export type FengYuEnvironment = { t: FengYuI18n['t']; locale: string }

/**
 * Host-driven environment hook: `t()` bound to the message table matching the host locale.
 * mountFengYuApp installs the reactive provider; outside a mounted app (bare standalone
 * preview, unit tests) the hook throws and we fall back to the module-level singleton —
 * the provider's presence never changes within a tree, so the guarded hook order is stable.
 */
export function useFengYuEnvironment(): FengYuEnvironment {
  try {
    return useFengYuI18n()
  } catch {
    return { t: pluginI18n.t.bind(pluginI18n), locale: pluginI18n.getLocale() }
  }
}
