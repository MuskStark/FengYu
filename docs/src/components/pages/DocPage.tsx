import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { TocList } from '@/components/layout/TocRail'
import { MarkdownView } from '@/components/md/MarkdownView'
import { flatSidebar, loadPage, manifest, pagerFor, type PageData } from '@/content/content'
import { navigation } from '@/content/navigation'
import { t } from '@/i18n'

const REPO_EDIT = 'https://github.com/MuskStark/FengYu/edit/main/docs'

function formatDate(iso: string | null, locale: 'en' | 'zh'): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleDateString(locale === 'zh' ? 'zh-CN' : 'en-US', { year: 'numeric', month: 'short', day: 'numeric' })
}

/**
 * The standard content page: breadcrumb → title → lead → meta row → (mobile
 * outline) → rendered markdown → prev/next pager. Page JSON is loaded per
 * route (code-split), so the 250 KB changelog pages never weigh down the rest.
 */
export default function DocPage() {
  const location = useLocation()
  const meta = manifest.routes[location.pathname]
  const [page, setPage] = useState<PageData | null>(null)
  const L = t(meta?.locale ?? 'en')

  useEffect(() => {
    let cancelled = false
    setPage(null)
    const promise = loadPage(location.pathname)
    if (!promise) return
    promise.then((data) => {
      if (!cancelled) setPage(data)
    })
    return () => {
      cancelled = true
    }
  }, [location.pathname])

  useEffect(() => {
    if (meta) document.title = `${meta.title} · Infinia Docs`
  }, [meta])

  // Deep links: scroll to the anchor once the content has landed.
  useEffect(() => {
    if (!page || !location.hash) return
    const id = decodeURIComponent(location.hash.slice(1))
    const target = document.getElementById(id)
    if (target) target.scrollIntoView({ block: 'start' })
    else window.history.replaceState(null, '', location.pathname)
  }, [page, location.hash, location.pathname])

  if (!meta) return null
  const flat = flatSidebar(meta.locale, navigation[meta.locale].sidebar)
  const crumb = flat.find((entry) => entry.route === location.pathname)
  const pager = pagerFor(location.pathname, flat)
  const updated = formatDate(meta.updatedAt, meta.locale)

  return (
    <article className="doc-article">
      <nav className="doc-crumb" aria-label="Breadcrumb">
        <span>{crumb?.group ?? ''}</span>
        <ChevronRight size={12} className="doc-crumb-sep" aria-hidden="true" />
        <span aria-current="page">{meta.title}</span>
      </nav>
      <h1 className="doc-title" id={page?.titleId ?? undefined}>
        {meta.title}
      </h1>
      {meta.description && <p className="doc-lead">{meta.description}</p>}
      <div className="doc-meta">
        {updated && (
          <span>
            {L.lastUpdated}: {updated}
          </span>
        )}
        <a href={`${REPO_EDIT}/${meta.key}.md`} target="_blank" rel="noopener">
          {L.editOnGitHub}
        </a>
      </div>

      {meta.toc.length > 0 && (
        <details className="doc-toc-details">
          <summary>{L.onThisPage}</summary>
          <TocList toc={meta.toc} active={null} />
        </details>
      )}

      {page ? (
        <MarkdownView html={page.html} locale={meta.locale} />
      ) : (
        <div className="doc-prose" aria-busy="true">
          <p>…</p>
        </div>
      )}

      <nav className="doc-pager" aria-label="Pager">
        {pager.prev ? (
          <Link className="doc-pager-link" to={pager.prev.route}>
            <span className="doc-pager-label">
              <ChevronLeft size={12} aria-hidden="true" /> {L.previous}
            </span>
            <span className="doc-pager-title">{pager.prev.text}</span>
          </Link>
        ) : (
          <span />
        )}
        {pager.next && (
          <Link className="doc-pager-link doc-pager-link--next" to={pager.next.route}>
            <span className="doc-pager-label">
              {L.next} <ChevronRight size={12} aria-hidden="true" />
            </span>
            <span className="doc-pager-title">{pager.next.text}</span>
          </Link>
        )}
      </nav>
    </article>
  )
}
