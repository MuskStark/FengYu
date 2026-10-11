import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ExternalLink, Search, X } from 'lucide-react'
import { ossLicenses } from '@/lib/ossLicenses'
import { ossBackendLicenses } from '@/lib/ossBackendLicenses'

/**
 * The OSS disclosure modal — the full frontend + backend attribution lists
 * behind the About card's trigger button. Esc / backdrop / close button all
 * dismiss; the lists share one search.
 */
export function OssLicensesModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const [search, setSearch] = useState('')

  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  const needle = search.trim().toLowerCase()
  const frontend = useMemo(() => ossLicenses.packages.filter(pkg =>
    !needle || `${pkg.name} ${pkg.license}`.toLowerCase().includes(needle),
  ), [needle])
  const backend = useMemo(() => ossBackendLicenses.packages.filter(pkg =>
    !needle || `${pkg.name} ${pkg.group} ${pkg.license}`.toLowerCase().includes(needle),
  ), [needle])

  if (!open) return null

  return (
    <div className="app-dialog-backdrop" role="presentation" onClick={onClose}>
      <div
        className="app-dialog oss-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('about.ossTitle')}
        onClick={event => event.stopPropagation()}
      >
        <div className="oss-modal__head">
          <h2 className="app-dialog__title">{t('about.ossTitle')}</h2>
          <button className="cx-iconbtn cx-iconbtn--sm" aria-label={t('common.close')} onClick={onClose}>
            <X size={16} />
          </button>
        </div>
        <p className="cx-muted pg-oss-note">{t('about.ossNote')}</p>
        <div className="pg-search oss-modal__search">
          <Search size={15} />
          <input
            className="cx-input"
            placeholder={t('licenses.search')}
            aria-label={t('licenses.search')}
            value={search}
            onChange={event => setSearch(event.target.value)}
            autoFocus
          />
        </div>
        <div className="oss-modal__body">
          <div className="cx-section-title">{t('licenses.frontend', { count: ossLicenses.count })}</div>
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
          <div className="cx-section-title">{t('licenses.backend', { count: ossBackendLicenses.count })}</div>
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
      </div>
    </div>
  )
}
