import { createI18n } from 'vue-i18n'
import en from './en'
import zhCN from './zh-CN'

export function localeFor(value: string): 'en' | 'zh-CN' {
  return value.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en'
}

export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en, 'zh-CN': zhCN },
})

export function syncLocale(value: string): void {
  i18n.global.locale.value = localeFor(value)
}
