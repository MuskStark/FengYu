import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './en.json'

/**
 * i18next instance behind react-i18next, sharing the exact JSON catalogs the Vue shell used
 * (same keys, same {placeholder} interpolation). The exported `i18n` is a vue-i18n-shaped
 * adapter (`i18n.global.t` / `i18n.global.locale.value`) so the framework-agnostic API layer
 * (api/client.ts, api/sse.ts) imports unchanged.
 */
export const instance = i18next.createInstance()

void instance.use(initReactI18next).init({
  // Only the fallback catalog (en) loads eagerly — it keeps init synchronous, which
  // node tests depend on (t() interpolates immediately). zh is a lazy ~50KB chunk
  // fetched by ensureInitialLocale()/setLocale before first render or on switch.
  resources: {
    en: { translation: en },
  },
  lng: readInitialLanguage(),
  fallbackLng: 'en',
  initImmediate: false, // synchronous init — t() interpolates immediately (node tests)
  // Single-brace placeholders ({name}) — the catalogs came from vue-i18n.
  interpolation: { escapeValue: false, prefix: '{', suffix: '}' },
})

/** Loaded locale bundles ('en' ships in the entry chunk; zh arrives lazily). */
const loadedLocales = new Set<string>(['en'])

/** Fetches and registers a locale bundle if it is not loaded yet (idempotent). */
async function ensureLocale(language: string): Promise<void> {
  if (language !== 'zh' || loadedLocales.has('zh')) return
  const zh = await import('./zh.json')
  instance.addResourceBundle('zh', 'translation', zh.default, true, true)
  loadedLocales.add('zh')
}

/**
 * The boot hook: resolves the INITIAL language's bundle before React renders, so a
 * zh user never sees English keys flash (the entry initializes with en only).
 * Awaited by main.tsx; safe to call repeatedly.
 */
export async function ensureInitialLocale(): Promise<void> {
  await ensureLocale(readInitialLanguage())
}

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

/**
 * The desktop shell's native surfaces (the tray menu) cannot read renderer localStorage
 * — the preload bridge mirrors the language into the main process (desktop/locale.ts).
 * No-op in the browser and in node tests (no window.fengyu there).
 */
function pushLocaleToHost(language: string) {
  if (typeof window === 'undefined') return
  window.fengyu?.setLocale?.(language === 'zh' ? 'zh' : 'en')
}

applyDocumentLanguage(instance.language)
pushLocaleToHost(instance.language)

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
        void (async () => {
          await ensureLocale(language)
          await instance.changeLanguage(language)
        })()
        persistLanguage(language)
        applyDocumentLanguage(language)
        pushLocaleToHost(language)
      },
    },
  },
}
