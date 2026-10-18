import { useEffect, useState } from 'react'
import { Navigate, useLocation, useParams } from 'react-router-dom'
import { useSearch } from '@/App'
import { manifest, type RouteMeta } from '@/content/content'
import { LOCALES, type Locale } from '@/content/navigation'
import { SearchPalette } from '@/components/search/SearchPalette'
import { Footer } from './Footer'
import { Navbar } from './Navbar'
import { Sidebar } from './Sidebar'
import { TocRail } from './TocRail'

/** The locale's entry page — the site has no marketing home (docs only). */
const ENTRY: Record<Locale, string> = { en: '/en/quickstart', zh: '/zh/quickstart' }

/**
 * The per-locale chrome. Resolves the current path against the route
 * manifest, normalizes trailing slashes, redirects the bare locale roots to
 * the entry page, and lays out the three-pane docs grid (sidebar / article /
 * TOC). Unknown paths fall into the 404 page with the locale guessed from
 * the URL.
 */
export function Shell({
  children,
  notFound = false,
}: {
  children: (props: { page: RouteMeta; notFound: boolean }) => React.ReactNode
  notFound?: boolean
}) {
  const { locale: localeParam } = useParams()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)
  const { setOpen: setSearchOpen } = useSearch()

  const locale: Locale = localeParam === 'zh' ? 'zh' : 'en'
  const valid = LOCALES.includes(locale)
  const normalized = location.pathname.length > `/${locale}`.length
    ? location.pathname.replace(/\/+$/, '')
    : location.pathname
  const page = valid ? manifest.routes[normalized] : undefined
  const meta: RouteMeta = page ?? {
    key: `${locale}/404`,
    locale,
    title: '404',
    description: '',
    lang: locale === 'zh' ? 'zh-CN' : 'en',
    changelog: false,
    toc: [],
    updatedAt: null,
  }

  // Close transient surfaces on navigation; Esc closes the drawer.
  useEffect(() => {
    setMenuOpen(false)
    setSearchOpen(false)
  }, [location.pathname, setSearchOpen])
  useEffect(() => {
    if (!menuOpen) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [menuOpen])
  // Keep <html lang> in step with the locale for AT and CJK font selection.
  useEffect(() => {
    document.documentElement.lang = meta.lang
  }, [meta.lang])

  if (!valid) return <Navigate to="/" replace />
  if (normalized !== location.pathname) return <Navigate to={normalized} replace />
  // Bare locale roots are entry redirects (the retired VitePress home lives on
  // as /{locale}/quickstart; old inbound links to /en and /zh keep working).
  if (normalized === `/${locale}`) return <Navigate to={ENTRY[locale]} replace />

  const isNotFound = notFound || !page

  return (
    <div>
      <a className="doc-skip" href="#doc-main">
        {locale === 'zh' ? '跳到正文' : 'Skip to content'}
      </a>
      <Navbar locale={locale} onMenu={() => setMenuOpen((open) => !open)} />
      {menuOpen && <div className="doc-side-scrim" onClick={() => setMenuOpen(false)} aria-hidden="true" />}
      <div className="doc-shell">
        {!isNotFound && (
          <Sidebar locale={locale} pathname={location.pathname} open={menuOpen} onNavigate={() => setMenuOpen(false)} />
        )}
        <main id="doc-main" className="doc-main">
          {children({ page: meta, notFound: isNotFound })}
        </main>
        {!isNotFound && <TocRail toc={meta.toc} locale={locale} />}
      </div>
      <Footer locale={locale} />
      <SearchPalette locale={locale} />
    </div>
  )
}
