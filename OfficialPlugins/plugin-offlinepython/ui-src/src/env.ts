import { pluginI18n } from './i18n'

/**
 * Reactive host environment (locale) + a `t()` translator bound to the matching message table.
 * The host pushes theme/locale via `environment` events (already bound in main.ts); this just
 * reads the latest locale and re-resolves the table on change.
 */
export function useFengYuEnvironment() {
  return pluginI18n
}
