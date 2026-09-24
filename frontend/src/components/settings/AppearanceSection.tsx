import { useTranslation } from 'react-i18next'
import { PanelLeft, Languages, Palette } from 'lucide-react'
import type { AppSettings } from '@/services/types'
import { useSettingsStore } from '@/stores/settings'

/**
 * Section 3 — appearance: theme segment (dark/light, applied optimistically by the
 * settings store), language segment (en/zh, drives i18n via the store's apply funnel),
 * and the sidebar shape flag (sidebarCollapsed — an AppSettings writable the shell's
 * collapse control normally drives; exposed here for completeness).
 */
export default function AppearanceSection({
  appSettings,
  onLanguage,
}: {
  appSettings: AppSettings | null
  /** PUT /api/settings { language } — the page funnels the response back through apply(). */
  onLanguage: (language: AppSettings['language']) => void
}) {
  const { t } = useTranslation()
  const theme = useSettingsStore(state => state.theme)
  const sidebarCollapsed = useSettingsStore(state => state.sidebarCollapsed)
  const setTheme = useSettingsStore(state => state.setTheme)
  const setSidebarCollapsed = useSettingsStore(state => state.setSidebarCollapsed)

  return (
    <section>
      <h2 className="set-h">{t('settings.general')}</h2>
      <div className="cx-card">
        <div className="cx-setting-row">
          <div className="cx-setting-row__label">
            <Palette size={16} />
            <span>{t('settings.theme')}</span>
          </div>
          <div className="cx-segment">
            <button
              type="button"
              className={theme === 'dark' ? 'active' : ''}
              onClick={() => setTheme('dark')}
            >
              {t('settings.dark')}
            </button>
            <button
              type="button"
              className={theme === 'light' ? 'active' : ''}
              onClick={() => setTheme('light')}
            >
              {t('settings.light')}
            </button>
          </div>
        </div>

        <div className="cx-setting-row">
          <div className="cx-setting-row__label">
            <Languages size={16} />
            <span>{t('settings.language')}</span>
          </div>
          <div className="cx-segment">
            <button
              type="button"
              className={appSettings?.language === 'en' ? 'active' : ''}
              onClick={() => onLanguage('en')}
            >
              {t('settings.english')}
            </button>
            <button
              type="button"
              className={appSettings?.language === 'zh' ? 'active' : ''}
              onClick={() => onLanguage('zh')}
            >
              {t('settings.chinese')}
            </button>
          </div>
        </div>

        <div className="cx-setting-row">
          <div className="cx-setting-row__label">
            <PanelLeft size={16} />
            <span>{t('sidebar.theme')}</span>
          </div>
          <div className="cx-segment">
            <button
              type="button"
              className={!sidebarCollapsed ? 'active' : ''}
              onClick={() => void setSidebarCollapsed(false)}
            >
              {t('sidebar.expand')}
            </button>
            <button
              type="button"
              className={sidebarCollapsed ? 'active' : ''}
              onClick={() => void setSidebarCollapsed(true)}
            >
              {t('sidebar.collapse')}
            </button>
          </div>
        </div>
      </div>
    </section>
  )
}
