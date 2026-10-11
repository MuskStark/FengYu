import { useTranslation } from 'react-i18next'
import '@/styles/pages.css'
import { Tabs } from '@/aceternity/tabs'
import { TextReveal } from '@/components/aceternity/TextReveal'
import { FadeIn } from '@/components/pages/FadeIn'
import { InfiniaStorePanel } from '@/components/pages/InfiniaStorePanel'
import { InstalledPanel } from '@/components/pages/InstalledPanel'

/**
 * Infinia Store: the front tab is the official store front (the whole production
 * catalog — plugins, skills, MCP — plus the cloud-account sign-in entry, all through
 * the native store channel); the skills tab is the skills-management surface. Both
 * draw from the one official store; third-party marketplace integration was retired.
 *
 * Layout discipline (what the giant aurora banner got wrong): the store is a WORK
 * surface — search, filter, install — so the header is one compact row and the
 * official Tabs own the tab bar + panel; the bento catalog is the visual event.
 */
export default function StorePage() {
  const { t } = useTranslation()

  return (
    <div className="pg-page">
      <div className="pg-inner pg-inner--wide">
        <FadeIn>
          <header className="store-header">
            <h1 className="pg-title">{t('store.title')}</h1>
            <TextReveal words={t('store.subtitle')} className="store-header__sub" />
          </header>
        </FadeIn>
        <div className="store-tabs">
          <Tabs
            tabs={[
              { title: t('store.title'), value: 'store', content: <InfiniaStorePanel /> },
              { title: t('store.skillsTab'), value: 'skills', content: <InstalledPanel /> },
            ]}
          />
        </div>
      </div>
    </div>
  )
}
