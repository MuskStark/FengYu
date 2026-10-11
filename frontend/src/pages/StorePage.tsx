import { useTranslation } from 'react-i18next'
import '@/styles/pages.css'
import { GlowingEffect } from '@/aceternity/glowing-effect'
import { Tabs } from '@/aceternity/tabs'
import { AuroraStage } from '@/components/aceternity/AuroraStage'
import { TextReveal } from '@/components/aceternity/TextReveal'
import { FadeIn } from '@/components/pages/FadeIn'
import { InfiniaStorePanel } from '@/components/pages/InfiniaStorePanel'
import { SkillMarketPanel } from '@/components/pages/SkillMarketPanel'

/**
 * Infinia Store: the front tab is the official store front (the whole production
 * catalog — plugins, skills, MCP — plus the cloud-account sign-in entry, all through
 * the native store channel); the skills tab is the skills-management surface. Both
 * draw from the one official store; third-party marketplace integration was retired.
 */
export default function StorePage() {
  const { t } = useTranslation()

  const tabs: Array<{ id: 'store' | 'skills'; label: string }> = [
    { id: 'store', label: t('store.title') },
    { id: 'skills', label: t('store.skillsTab') },
  ]
  void tabs

  return (
    <div className="pg-page">
      <div className="pg-inner pg-inner--wide">
        <FadeIn>
          <header className="pg-header">
            <div className="store-banner">
              <div className="store-banner__glow" aria-hidden="true">
                <GlowingEffect spread={36} disabled={false} glow />
              </div>
              <AuroraStage>
                <div className="pg-header__text">
                  <h1 className="pg-title">{t('store.title')}</h1>
                  <TextReveal words={t('store.subtitle')} className="pg-subtitle" />
                </div>
              </AuroraStage>
            </div>
          </header>
          <div className="store-tabs">
            <Tabs
              tabs={[
                { title: t('store.title'), value: 'store', content: <InfiniaStorePanel /> },
                { title: t('store.skillsTab'), value: 'skills', content: <SkillMarketPanel /> },
              ]}
            />
          </div>
        </FadeIn>
        <div style={{ height: 18 }} />
      </div>
    </div>
  )
}
