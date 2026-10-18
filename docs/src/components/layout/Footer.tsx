import type { Locale } from '@/content/navigation'
import { t } from '@/i18n'

const GITHUB_URL = 'https://github.com/MuskStark/FengYu'

export function Footer({ locale }: { locale: Locale }) {
  const L = t(locale)
  return (
    <footer className="doc-footer">
      <span>{L.footerLicense}</span>
      <span>{L.footerCopyright}</span>
      <div className="doc-footer-links">
        <a href={GITHUB_URL} target="_blank" rel="noopener">
          GitHub
        </a>
        <a href={`${import.meta.env.BASE_URL}sitemap.xml`}>Sitemap</a>
      </div>
    </footer>
  )
}
