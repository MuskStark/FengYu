import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { GraduationCap, Puzzle } from 'lucide-react'
import '@/styles/pages.css'
import { FadeIn } from '@/components/pages/FadeIn'
import { PluginMarketPanel } from '@/components/pages/PluginMarketPanel'
import { SkillMarketPanel } from '@/components/pages/SkillMarketPanel'
import { cn } from '@/lib/utils'

type StoreTab = 'plugins' | 'skills'

/**
 * Infinia Store (React twin of the Vue StoreView, scoped to the two market
 * tabs): the plugin market (unified plugin-store catalog) and the skills
 * market. Each panel owns its loading/empty/error states and lifecycle
 * actions; this page only hosts the tab switch.
 */
export default function StorePage() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<StoreTab>('plugins')

  const tabs: Array<{ id: StoreTab; label: string; icon: typeof Puzzle }> = [
    { id: 'plugins', label: t('store.title'), icon: Puzzle },
    { id: 'skills', label: t('store.skillsTab'), icon: GraduationCap },
  ]

  return (
    <div className="pg-page">
      <div className="pg-inner pg-inner--wide">
        <FadeIn>
          <header className="pg-header">
            <div className="pg-header__text">
              <h1 className="pg-title">{t('store.title')}</h1>
              <p className="cx-muted pg-subtitle">{t('store.subtitle')}</p>
            </div>
          </header>
          <div className="pg-tabs" role="tablist" aria-label={t('store.title')}>
            {tabs.map(({ id, label, icon: Icon }) => (
              <button
                key={id}
                role="tab"
                aria-selected={tab === id}
                className={cn('pg-tab', tab === id && 'pg-tab--active')}
                onClick={() => setTab(id)}
              >
                <Icon size={15} />
                {label}
              </button>
            ))}
          </div>
        </FadeIn>
        <div style={{ height: 18 }} />
        {tab === 'plugins' ? <PluginMarketPanel /> : <SkillMarketPanel />}
      </div>
    </div>
  )
}
