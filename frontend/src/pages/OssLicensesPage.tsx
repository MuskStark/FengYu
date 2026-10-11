import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft, ExternalLink, Scale, Search } from 'lucide-react'
import '@/styles/pages.css'
import { ossLicenses } from '@/lib/ossLicenses'
import { ossBackendLicenses } from '@/lib/ossBackendLicenses'
import { FadeIn } from '@/components/pages/FadeIn'

/**
 * OSS licenses — the About page's secondary view. Full disclosure lists for
 * the frontend runtime packages (generated from each upstream package.json)
 * and the backend runtime dependencies (generated from Maven's resolved graph
 * + each artifact's POM license statement), with one shared search.
 */
export default function OssLicensesPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [search, setSearch] = useState('')

  const needle = search.trim().toLowerCase()
  const frontend = useMemo(() => ossLicenses.packages.filter(pkg =>
    !needle || `${pkg.name} ${pkg.license}`.toLowerCase().includes(needle),
  ), [needle])
  const backend = useMemo(() => ossBackendLicenses.packages.filter(pkg =>
    !needle || `${pkg.name} ${pkg.group} ${pkg.license}`.toLowerCase().includes(needle),
  ), [needle])

  return (
    <div className="cx-page pg-licenses">
      <header className="pg-licenses__head">
        <div>
          <button className="cx-btn cx-btn--text cx-btn--sm pg-licenses__back" onClick={() => navigate('/about')}>
            <ArrowLeft size={14} /> {t('about.title')}
          </button>
          <h1 className="cx-page-title">{t('about.ossTitle')}</h1>
          <p className="cx-muted">{t('about.ossNote')}</p>
        </div>
      </header>

      <FadeIn>
        <div className="pg-toolbar">
          <div className="pg-toolbar__row">
            <div className="pg-search">
              <Search size={15} />
              <input
                className="cx-input"
                placeholder={t('licenses.search')}
                aria-label={t('licenses.search')}
                value={search}
                onChange={event => setSearch(event.target.value)}
              />
            </div>
            <span className="cx-chip cx-chip--gold"><Scale size={13} /> GNU GPL v3.0</span>
          </div>
        </div>
      </FadeIn>

      <FadeIn delay={0.04}>
        <div className="cx-section-title">{t('licenses.frontend', { count: ossLicenses.count })}</div>
        <div className="cx-card">
          <ul className="pg-oss-list">
            {frontend.map(pkg => (
              <li key={pkg.name} className="pg-oss-row">
                <span className="pg-oss-name" title={pkg.name}>{pkg.name}</span>
                <span className="cx-muted pg-oss-version">v{pkg.version}</span>
                <span className="cx-chip pg-oss-license">{pkg.license}</span>
                {pkg.homepage
                  ? (
                    <a
                      className="pg-oss-home"
                      href={pkg.homepage}
                      target="_blank"
                      rel="noopener noreferrer"
                      aria-label={`${pkg.name} homepage`}
                    ><ExternalLink size={13} /></a>
                  )
                  : <span className="pg-oss-home" />}
              </li>
            ))}
            {frontend.length === 0 && <li className="cx-muted pg-oss-row">{t('licenses.noMatch')}</li>}
          </ul>
        </div>
      </FadeIn>

      <FadeIn delay={0.08}>
        <div className="cx-section-title">{t('licenses.backend', { count: ossBackendLicenses.count })}</div>
        <div className="cx-card">
          <ul className="pg-oss-list">
            {backend.map(pkg => (
              <li key={`${pkg.group}:${pkg.name}`} className="pg-oss-row">
                <span className="pg-oss-name" title={`${pkg.group}:${pkg.name}`}>{pkg.name}</span>
                <span className="cx-muted pg-oss-version">{pkg.group}</span>
                <span className="cx-muted pg-oss-version">v{pkg.version}</span>
                <span className="cx-chip pg-oss-license">{pkg.license}</span>
              </li>
            ))}
            {backend.length === 0 && <li className="cx-muted pg-oss-row">{t('licenses.noMatch')}</li>}
          </ul>
        </div>
      </FadeIn>
    </div>
  )
}
