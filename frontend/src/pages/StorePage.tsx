import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { GraduationCap, Puzzle } from 'lucide-react'
import '@/styles/pages.css'
import { FadeIn } from '@/components/pages/FadeIn'
import { InfiniaStorePanel } from '@/components/pages/InfiniaStorePanel'
import { SkillMarketPanel } from '@/components/pages/SkillMarketPanel'
import { cn } from '@/lib/utils'

type StoreTab = 'store' | 'skills'

/**
 * Infinia Store: the front tab is the official store front (the whole production
 * catalog — plugins, skills, MCP — plus the cloud-account sign-in entry, all through
 * the native store channel); the skills tab is the skills-management surface. Both
 * draw from the one official store; third-party marketplace integration was retired.
 */
export default function StorePage() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<StoreTab>('store')

  const tabs: Array<{ id: StoreTab; label: string; icon: typeof Puzzle }> = [
    { id: 'store', label: t('store.title'), icon: Puzzle },
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
        {tab === 'store' ? <InfiniaStorePanel /> : <SkillMarketPanel />}
      </div>
    </div>
  )
}
