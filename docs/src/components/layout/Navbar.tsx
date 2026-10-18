import { Menu, Moon, Search, Sun } from 'lucide-react'
import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useSearch } from '@/App'
import { manifest } from '@/content/content'
import { navigation, type Locale } from '@/content/navigation'
import { t } from '@/i18n'
import { currentTheme, toggleTheme } from '@/lib/theme'
import { cn } from '@/lib/utils'

const GITHUB_URL = 'https://github.com/MuskStark/FengYu'

/** Swap the locale segment of the current path, falling back to the other locale's home. */
export function localizedPath(pathname: string, target: Locale): string {
  const rest = pathname.split('/').slice(2).join('/')
  const candidate = `/${target}${rest ? `/${rest}` : ''}`
  return manifest.routes[candidate] ? candidate : `/${target}`
}

function GitHubMark({ size = 17 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
    </svg>
  )
}

export function Navbar({ locale, onMenu }: { locale: Locale; onMenu: () => void }) {
  const { pathname } = useLocation()
  const { setOpen: setSearchOpen } = useSearch()
  const [theme, setTheme] = useState(currentTheme())
  const L = t(locale)
  const other: Locale = locale === 'en' ? 'zh' : 'en'

  return (
    <header className="doc-navbar">
      <button type="button" className="doc-iconbtn doc-menu-btn" onClick={onMenu} aria-label={L.openMenu}>
        <Menu size={18} />
      </button>
      <Link className="doc-brand" to={`/${locale}`}>
        <img src={`${import.meta.env.BASE_URL}logo.svg`} alt="" />
        {locale === 'zh' ? '蜂语 Infinia' : 'Infinia'}
        <span className="doc-brand-chip">Docs</span>
      </Link>
      <nav className="doc-navlinks" aria-label={L.language}>
        {navigation[locale].nav.map((item) => {
          const active = item.link === pathname || pathname.startsWith(`${item.link}/`)
          return (
            <Link key={item.link} to={item.link} className={cn('doc-navlink', active && 'active')}>
              {item.text}
            </Link>
          )
        })}
      </nav>
      <div className="doc-navbar-actions">
        <button type="button" className="doc-searchbtn" onClick={() => setSearchOpen(true)}>
          <Search size={14} />
          <span>{L.search}</span>
          <kbd className="doc-kbd">⌘K</kbd>
        </button>
        <Link className="doc-lang" to={localizedPath(pathname, other)} aria-label={L.switchLang}>
          {locale === 'en' ? '中文' : 'EN'}
        </Link>
        <button
          type="button"
          className="doc-iconbtn"
          onClick={() => setTheme(toggleTheme())}
          aria-label={L.toggleTheme}
        >
          {theme === 'dark' ? <Sun size={17} /> : <Moon size={17} />}
        </button>
        <a className="doc-iconbtn" href={GITHUB_URL} target="_blank" rel="noopener" aria-label={L.github}>
          <GitHubMark />
        </a>
      </div>
    </header>
  )
}
