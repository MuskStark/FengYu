import { createContext, createElement, useCallback, useContext, useSyncExternalStore, type ReactNode } from 'react'
import type { Environment } from '@infinia/plugin-sdk'

export type FengYuMessages = Readonly<Record<string, string>>
export type FengYuMessageTables = Readonly<Record<string, FengYuMessages>>

/** Normalize host BCP-47-ish locales to the language tables supported by plugins. */
export function normalizeFengYuLocale(value?: string): string {
  return value?.trim().toLowerCase().startsWith('zh') ? 'zh' : 'en'
}

/**
 * Framework-agnostic i18n runtime for iframe plugins (same catalog shape and
 * `t()` semantics as the Vue 2.x kit). It deliberately owns no language
 * switcher: `bindFengYuEnvironment` feeds it the host locale, keeping UI and
 * worker locale aligned. React trees subscribe through {@link useFengYuI18n}.
 */
export interface FengYuI18n {
  getLocale(): string
  getMessages(): FengYuMessages
  applyEnvironment(environment: Pick<Environment, 'locale'> | { locale?: string }): void
  t(key: string, ...args: Array<string | number>): string
  subscribe(listener: () => void): () => void
}

export function createFengYuI18n(tables: FengYuMessageTables, fallback = 'en'): FengYuI18n {
  if (!tables[fallback]) throw new Error(`Missing FengYu i18n fallback table: ${fallback}`)
  let locale = fallback
  const listeners = new Set<() => void>()
  const messages = () => tables[locale] ?? tables[fallback]
  return {
    getLocale: () => locale,
    getMessages: messages,
    applyEnvironment(environment) {
      const next = normalizeFengYuLocale(environment.locale)
      if (next === locale) return
      locale = next
      listeners.forEach((listener) => listener())
    },
    t(key, ...args) {
      let value = messages()[key] ?? tables[fallback][key] ?? key
      args.forEach((argument, index) => {
        value = value.replaceAll(`{${index}}`, String(argument))
      })
      return value
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}

export const FengYuI18nContext = createContext<FengYuI18n | null>(null)
FengYuI18nContext.displayName = 'FengYuI18n'

export function FengYuI18nProvider({ i18n, children }: { i18n: FengYuI18n; children: ReactNode }) {
  return createElement(FengYuI18nContext.Provider, { value: i18n }, children)
}

/** Subscribe to a {@link createFengYuI18n} runtime; re-renders on locale change. */
export function useFengYuI18n(): { t: FengYuI18n['t']; locale: string } {
  const i18n = useContext(FengYuI18nContext)
  if (!i18n) throw new Error('useFengYuI18n() requires a FengYuI18nProvider (installed by mountFengYuApp).')
  const locale = useSyncExternalStore(
    i18n.subscribe,
    () => i18n.getLocale(),
    () => i18n.getLocale(),
  )
  // Stable identity across renders (safe as an effect dependency); rebound
  // only when the locale (and therefore the tables) change.
  const t = useCallback<FengYuI18n['t']>(
    (key, ...args) => i18n.t(key, ...args),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [i18n, locale],
  )
  return { t, locale }
}
