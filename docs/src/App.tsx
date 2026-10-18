import { createContext, useContext, useEffect, useState } from 'react'
import { Suspense, lazy } from 'react'
import { BrowserRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { Shell } from './components/layout/Shell'

const DocPage = lazy(() => import('./components/pages/DocPage'))
const ChangelogPage = lazy(() => import('./components/pages/ChangelogPage'))
const NotFoundPage = lazy(() => import('./components/pages/NotFoundPage'))

interface SearchState {
  open: boolean
  setOpen: (open: boolean) => void
}

const SearchContext = createContext<SearchState>({ open: false, setOpen: () => {} })

export function useSearch(): SearchState {
  return useContext(SearchContext)
}

/** '/' → the visitor's likely locale's entry page (zh* → /zh/quickstart, else /en/quickstart). There is no marketing home: this site is documentation only — the product site lives elsewhere. */
function RootRedirect() {
  const navigate = useNavigate()
  useEffect(() => {
    const zh = (navigator.language || '').toLowerCase().startsWith('zh')
    navigate(zh ? '/zh/quickstart' : '/en/quickstart', { replace: true })
  }, [navigate])
  return null
}

function PageFallback() {
  return (
    <div className="doc-article" aria-busy="true">
      <div className="doc-title">&nbsp;</div>
      <div className="doc-lead">&nbsp;</div>
    </div>
  )
}

function Lazy({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<PageFallback />}>{children}</Suspense>
}

/** One shell per locale route; the page type comes from the route manifest (unknown paths → 404 page in-shell). */
function LocaleRoute() {
  return (
    <Shell>
      {({ page, notFound }) =>
        notFound ? (
          <Lazy>
            <NotFoundPage />
          </Lazy>
        ) : page.changelog ? (
          <Lazy>
            <ChangelogPage />
          </Lazy>
        ) : (
          <Lazy>
            <DocPage />
          </Lazy>
        )
      }
    </Shell>
  )
}

/** Paths outside any locale prefix: shell with the default locale + 404 page. */
function NotFoundRoute() {
  return (
    <Shell notFound>
      {() => (
        <Lazy>
          <NotFoundPage />
        </Lazy>
      )}
    </Shell>
  )
}

export default function App() {
  const [searchOpen, setSearchOpen] = useState(false)

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setSearchOpen((open) => !open)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  return (
    <BrowserRouter basename="/FengYu">
      <SearchContext.Provider value={{ open: searchOpen, setOpen: setSearchOpen }}>
        <Routes>
          <Route path="/" element={<RootRedirect />} />
          <Route path="/:locale" element={<LocaleRoute />} />
          <Route path="/:locale/*" element={<LocaleRoute />} />
          <Route path="*" element={<NotFoundRoute />} />
        </Routes>
      </SearchContext.Provider>
    </BrowserRouter>
  )
}
