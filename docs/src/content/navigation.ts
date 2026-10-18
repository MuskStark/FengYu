import { en, zh, type LocaleNavigation, type NavGroup, type NavLink } from '../../scripts/navigation.mjs'

export type { LocaleNavigation, NavGroup, NavLink }

export type Locale = 'en' | 'zh'

export const LOCALES: Locale[] = ['en', 'zh']

/** The typed view of scripts/navigation.mjs — the single nav/sidebar source shared with the content pipeline. */
export const navigation: Record<Locale, LocaleNavigation> = { en, zh }
