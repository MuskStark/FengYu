import { pluginI18n } from './i18n'

/**
 * Reactive host environment (locale) + a `t()` translator bound to the matching message table.
 * The host pushes theme/locale via `environment` events (bound by `bindFengYuEnvironment` in
 * main.ts); this reads the latest locale and re-resolves the table on change. Mirrors the
 * offlinepython frontend env composable.
 */
export function useFengYuEnvironment() {
  return pluginI18n
}
