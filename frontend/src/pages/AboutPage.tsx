import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { BookOpen, ExternalLink, Github, RefreshCw, Scale, Tag, User } from 'lucide-react'
import '@/styles/pages.css'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import type { UpdateCheckResult } from '@/services/types'
import { OssLicensesModal } from '@/components/pages/OssLicensesModal'
import { TextReveal } from '@/components/aceternity/TextReveal'
import { FadeIn } from '@/components/pages/FadeIn'

const REPO = 'https://github.com/MuskStark/FengYu'
const DOCS = 'https://muskstark.github.io/FengYu/'
const AUTHOR_URL = 'https://github.com/MuskStark'
const LICENSE_URL = 'https://github.com/MuskStark/FengYu/blob/main/LICENSE'

/**
 * About — the app's identity page. One slim aurora band carries the brand
 * (the ceremonial moment, contained so it can't hollow the page the way the
 * lamp experiment did), the info card keeps the version/update/links table,
 * and the OSS disclosure section lists every runtime open-source package with
 * its license (generated from each upstream package.json by
 * scripts/oss-licenses.mjs — regenerate after dependency changes).
 */
export default function AboutPage() {
  const { t } = useTranslation()
  const [ossOpen, setOssOpen] = useState(false)
  const [result, setResult] = useState<UpdateCheckResult | null>(null)
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const checkingRef = useRef(false)

  const check = useCallback(async (force: boolean) => {
    if (checkingRef.current) return
    checkingRef.current = true
    setChecking(true)
    setError(null)
    try {
      setResult(await services.appUpdate.check(force))
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : t('update.error'))
    } finally {
      checkingRef.current = false
      setChecking(false)
    }
  }, [t])

  // Startup probe: cached (force=false) so it stays polite to the release API.
  useEffect(() => { void check(false) }, [check])

  const openRelease = () => {
    void getPlatform().openExternal(result?.releaseUrl || `${REPO}/releases`)
      .catch(() => setError(t('update.error')))
  }

  const appVersion = result?.currentVersion ?? '…'

  return (
    <div className="pg-page">
      <div className="pg-inner">
        <FadeIn>
          {/* Identity hero: one smooth radial gold wash behind the brand — the
              vendored aurora's repeating stripes read as stains on the light
              canvas, so the ceremony here is a clean halo, not an effect. */}
          <div className="pg-about-hero">
            <img className="pg-about-logo" src="/infinia-logo.svg" alt="" />
            <div className="pg-about-name">
              <h1 className="pg-about-brand">{t('brand')}</h1>
              <span className="cx-chip cx-chip--solid">v{appVersion}</span>
            </div>
            <p className="pg-about-slogan">{t('about.slogan')}</p>
            <TextReveal words={t('about.subtitle')} className="cx-muted pg-about-subtitle" />
          </div>
        </FadeIn>

        <FadeIn delay={0.05}>
          <div className="cx-section-title">{t('about.infoTitle')}</div>
          <div className="cx-card">
            <div className="pg-about-row">
              <div className="pg-about-row__label"><Tag size={17} /><span>{t('about.version')}</span></div>
              <span className="pg-about-value">v{appVersion}</span>
            </div>

            {/* Update check (browser/portable/desktop all probe via the loopback API) */}
            <div className="pg-about-row">
              <div className="pg-about-row__label"><RefreshCw size={17} /><span>{t('update.title')}</span></div>
              <div className="pg-about-update">
                {checking && (
                  <span className="cx-muted pg-about-value">
                    <RefreshCw size={13} className="pg-spin" />
                    {t('update.checking')}
                  </span>
                )}
                {!checking && error && (
                  <>
                    <span className="pg-about-value pg-about-error">{t('update.error')}</span>
                    <button className="cx-btn cx-btn--tonal cx-btn--sm" onClick={() => void check(true)}>
                      {t('update.retry')}
                    </button>
                  </>
                )}
                {!checking && !error && result?.updateAvailable && (
                  <>
                    <span className="pg-about-value">{t('update.available', { version: result.latestVersion })}</span>
                    <button className="cx-btn cx-btn--tonal cx-btn--sm" onClick={openRelease}>
                      <ExternalLink size={13} />
                      {t('update.openPage')}
                    </button>
                  </>
                )}
                {!checking && !error && result && !result.updateAvailable && (
                  <>
                    <span className="cx-muted pg-about-value">{t('update.latest')}</span>
                    <button className="cx-btn cx-btn--tonal cx-btn--sm" onClick={() => void check(true)}>
                      {t('update.recheck')}
                    </button>
                  </>
                )}
                {!checking && !error && !result && (
                  <button className="cx-btn cx-btn--tonal cx-btn--sm" onClick={() => void check(true)}>
                    {t('update.check')}
                  </button>
                )}
                {error && (
                  <span className="pg-about-value pg-about-error" style={{ flexBasis: '100%', fontSize: 12 }}>
                    {error}
                  </span>
                )}
              </div>
            </div>

            <div className="pg-about-row">
              <div className="pg-about-row__label"><User size={17} /><span>{t('about.author')}</span></div>
              <a className="pg-about-link" href={AUTHOR_URL} target="_blank" rel="noopener noreferrer">MuskStark</a>
            </div>
            <div className="pg-about-row">
              <div className="pg-about-row__label"><Scale size={17} /><span>{t('about.license')}</span></div>
              <a className="pg-about-link" href={LICENSE_URL} target="_blank" rel="noopener noreferrer">GNU GPL v3.0</a>
            </div>
            <div className="pg-about-row">
              <div className="pg-about-row__label"><Github size={17} /><span>{t('about.repository')}</span></div>
              <a className="pg-about-link" href={REPO} target="_blank" rel="noopener noreferrer">github.com/MuskStark/FengYu</a>
            </div>
            <div className="pg-about-row">
              <div className="pg-about-row__label"><BookOpen size={17} /><span>{t('about.docs')}</span></div>
              <a className="pg-about-link" href={DOCS} target="_blank" rel="noopener noreferrer">muskstark.github.io/FengYu</a>
            </div>
          </div>
        </FadeIn>

        {/* OSS disclosure — required notices for the runtime open-source packages
            (frontend + backend); the full lists live in the modal. */}
        <FadeIn delay={0.1}>
          <div className="pg-oss-entry">
            <button
              className="cx-btn cx-btn--outline cx-btn--sm"
              onClick={() => setOssOpen(true)}
            >
              {t('about.ossViewAll')}
            </button>
          </div>
        </FadeIn>
      </div>
      <OssLicensesModal open={ossOpen} onClose={() => setOssOpen(false)} />
    </div>
  )
}
