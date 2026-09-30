import { createI18n } from 'vue-i18n'
import type { Ref } from 'vue'
import en from './en.json'
import zh from './zh.json'

export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en, zh },
})

export type MessageKey = string // keys are dotted paths

/**
 * The current UI locale as a reactive {@link Ref}, for use outside a component (e.g. inside Pinia
 * stores, where {@link useI18n} cannot be called). In composition mode {@code i18n.global.locale}
 * is a ref but its type degrades to a plain string here, so the cast restores ref-typed access.
 * Components should prefer {@code const { locale } = useI18n()} instead.
 */
export const localeRef = i18n.global.locale as unknown as Ref<string>
