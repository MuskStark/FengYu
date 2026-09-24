import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './en.json'
import zh from './zh.json'

/**
 * i18next instance behind react-i18next, sharing the exact JSON catalogs the Vue shell used
 * (same keys, same {placeholder} interpolation). The exported `i18n` is a vue-i18n-shaped
 * adapter (`i18n.global.t` / `i18n.global.locale.value`) so the framework-agnostic API layer
 * (api/client.ts, api/sse.ts) imports unchanged.
 */
export const instance = i18next.createInstance()

void instance.use(initReactI18next).init({
  resources: {
    en: { translation: en },
    zh: { translation: zh },
  },
  lng: readInitialLanguage(),
  fallbackLng: 'en',
  initImmediate: false, // synchronous init — t() interpolates immediately (node tests)
  // Single-brace placeholders ({name}) — the catalogs came from vue-i18n.
  interpolation: { escapeValue: false, prefix: '{', suffix: '}' },
})

function readInitialLanguage(): string {
  try {
    const raw = localStorage.getItem('fengyu-language')
    if (raw === 'zh' || raw === 'en') return raw
    return navigator.language?.toLowerCase().startsWith('zh') ? 'zh' : 'en'
  } catch {
    return 'en'
  }
}

function persistLanguage(language: string) {
  try {
    localStorage.setItem('fengyu-language', language)
  } catch {
    /* private mode */
  }
}

function applyDocumentLanguage(language: string) {
  // Headless (node test) contexts have no document — the tag is cosmetic.
  if (typeof document === 'undefined') return
  document.documentElement.lang = language === 'zh' ? 'zh-CN' : 'en'
}

applyDocumentLanguage(instance.language)

export const i18n = {
  global: {
    t: ((key: string, params?: Record<string, unknown>) =>
      instance.t(key, params ?? {}) ?? key) as (key: string, params?: Record<string, unknown>) => string,
    locale: {
      get value(): string {
        return instance.language ?? 'en'
      },
      set value(language: string) {
        if (language !== 'zh' && language !== 'en') return
        void instance.changeLanguage(language)
        persistLanguage(language)
        applyDocumentLanguage(language)
      },
    },
  },
}
