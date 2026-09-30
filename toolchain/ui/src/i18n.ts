import { computed, ref, type ComputedRef, type Ref } from 'vue'
import type { Environment } from '@infinia/plugin-sdk'

export type FengYuMessages = Readonly<Record<string, string>>
export type FengYuMessageTables = Readonly<Record<string, FengYuMessages>>
export type FengYuI18n = {
  locale: Ref<string>
  messages: ComputedRef<FengYuMessages>
  applyEnvironment(environment: Pick<Environment, 'locale'> | { locale?: string }): void
  t(key: string, ...args: Array<string | number>): string
}

/** Normalize host BCP-47-ish locales to the language tables supported by official plugins. */
export function normalizeFengYuLocale(value?: string): string {
  return value?.trim().toLowerCase().startsWith('zh') ? 'zh' : 'en'
}

/**
 * Small reactive i18n runtime for iframe plugins. It deliberately owns no language switcher:
 * {@link bindFengYuEnvironment} feeds it the host locale, keeping UI and worker locale aligned.
 */
export function createFengYuI18n(tables: FengYuMessageTables, fallback = 'en'): FengYuI18n {
  if (!tables[fallback]) throw new Error(`Missing FengYu i18n fallback table: ${fallback}`)
  const locale = ref(fallback)
  const messages = computed(() => tables[locale.value] ?? tables[fallback])
  return {
    locale,
    messages,
    applyEnvironment(environment) { locale.value = normalizeFengYuLocale(environment.locale) },
    t(key, ...args) {
      let value = messages.value[key] ?? tables[fallback][key] ?? key
      args.forEach((argument, index) => { value = value.replaceAll(`{${index}}`, String(argument)) })
      return value
    },
  }
}
