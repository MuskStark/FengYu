import { useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'
import type { TocItem } from '@/content/content'
import type { Locale } from '@/content/navigation'
import { t } from '@/i18n'
import { cn } from '@/lib/utils'

/** Shared anchor behavior for TOC links (rail + mobile details variant). */
function scrollToAnchor(event: React.MouseEvent<HTMLAnchorElement>, id: string) {
  event.preventDefault()
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  window.history.replaceState(null, '', `#${id}`)
}

export function TocList({ toc, active }: { toc: TocItem[]; active: string | null }) {
  return (
    <>
      {toc.map((item) => (
        <a
          key={item.id}
          href={`#${item.id}`}
          className={cn('doc-toc-link', item.depth === 3 && 'doc-toc-link--h3', active === item.id && 'active')}
          onClick={(event) => scrollToAnchor(event, item.id)}
        >
          {item.text}
        </a>
      ))}
    </>
  )
}

/**
 * Right-rail "On this page". The active heading is tracked with an
 * IntersectionObserver band under the sticky navbar — the first heading
 * inside the band is the current section.
 */
export function TocRail({ toc, locale }: { toc: TocItem[]; locale: Locale }) {
  const [active, setActive] = useState<string | null>(toc[0]?.id ?? null)
  const location = useLocation()
  const L = t(locale)

  useEffect(() => {
    setActive(toc[0]?.id ?? null)
    if (toc.length === 0) return
    const elements = toc.map((item) => document.getElementById(item.id)).filter((el): el is HTMLElement => el !== null)
    if (elements.length === 0) return
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries.filter((entry) => entry.isIntersecting)
        if (visible.length > 0) setActive(visible[0].target.id)
      },
      { rootMargin: '-80px 0px -70% 0px', threshold: 0 },
    )
    for (const element of elements) observer.observe(element)
    return () => observer.disconnect()
  }, [toc, location.pathname])

  if (toc.length === 0) return null
  return (
    <nav className="doc-toc" aria-label={L.onThisPage}>
      <p className="doc-toc-title">{L.onThisPage}</p>
      <TocList toc={toc} active={active} />
    </nav>
  )
}
