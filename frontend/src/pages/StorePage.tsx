import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Boxes, Puzzle } from 'lucide-react'
import '@/styles/pages.css'
import { FadeIn } from '@/components/pages/FadeIn'
import { InfiniaStorePanel } from '@/components/pages/InfiniaStorePanel'
import { InstalledPanel } from '@/components/pages/InstalledPanel'
import { cn } from '@/lib/utils'

type StoreTab = 'store' | 'installed'

/**
 * Infinia Store: the front tab is the official store front (the whole production
 * catalog — plugins, skills, MCP — plus the cloud-account sign-in entry, all through
 * the native store channel); the Installed tab is the unified installed inventory.
 * The tab bar keeps the accessible pg-tabs pattern (role=tablist + conditional
 * panel mounting): the vendored Tabs stack both panels absolutely, which drops
 * the ARIA roles and leaves the inactive panel's controls keyboard-reachable.
 */
export default function StorePage() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<StoreTab>('store')

  const tabs: Array<{ id: StoreTab; label: string; icon: typeof Puzzle }> = [
    { id: 'store', label: t('store.title'), icon: Puzzle },
    { id: 'installed', label: t('store.skillsTab'), icon: Boxes },
  ]

  return (
    <div className="pg-page">
      <div className="pg-inner pg-inner--wide">
        <FadeIn>
          <header className="store-header">
            <h1 className="pg-title">{t('store.title')}</h1>
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
        {tab === 'store' ? <InfiniaStorePanel /> : <InstalledPanel />}
      </div>
    </div>
  )
}
