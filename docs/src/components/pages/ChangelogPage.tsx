import { ChevronRight } from 'lucide-react'
import { motion, useScroll, useSpring } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { MarkdownView } from '@/components/md/MarkdownView'
import { loadPage, manifest, type PageData } from '@/content/content'
import { t } from '@/i18n'

/** How many entries ship expanded; the rest stay collapsed <details>. Only the current release opens — historical rollups (which carry the whole condensed prerelease cycle) stay shut until asked for. */
const OPEN_COUNT = 1

/**
 * The changelog as a version spine: intro prose, then one timeline node per
 * release with a gold scroll-progress fill (motion useScroll — the same
 * animation layer the Aceternity components ride). Version anchors land on
 * the <summary> whether or not it is expanded.
 */
export default function ChangelogPage() {
  const location = useLocation()
  const meta = manifest.routes[location.pathname]
  const [page, setPage] = useState<PageData | null>(null)
  const timelineRef = useRef<HTMLDivElement>(null)
  const { scrollYProgress } = useScroll({ target: timelineRef, offset: ['start 60%', 'end 100%'] })
  const fill = useSpring(scrollYProgress, { stiffness: 120, damping: 24 })
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

  if (!meta) return null
  const versions = page?.changelog?.versions ?? []
  // "Latest" marks the newest DATED release — an `[Unreleased]` section sorts
  // first but is not a release.
  const latestIndex = versions.findIndex((version) => version.date)
  const label = (version: string) => (/^v?\d/.test(version) ? `v${version}` : version)

  return (
    <article className="doc-article">
      <h1 className="doc-title">{meta.title}</h1>
      {meta.description && <p className="doc-lead">{meta.description}</p>}

      {page && <MarkdownView html={page.html} locale={meta.locale} />}

      <div className="clg-timeline" ref={timelineRef}>
        <div className="clg-progress" aria-hidden="true">
          <motion.div className="clg-progress-fill" style={{ scaleY: fill }} />
        </div>
        {versions.map((version, index) => (
          <section className={`clg-item${index === latestIndex ? ' clg-item--latest' : ''}`} key={version.version} id={version.anchor}>
            <span className="clg-dot" aria-hidden="true" />
            <details open={index < OPEN_COUNT}>
              <summary className="clg-summary" id={version.anchor}>
                <span className="clg-version">{label(version.version)}</span>
                {version.date && <span className="clg-date">{version.date}</span>}
                {index === latestIndex && <span className="doc-chip doc-chip--gradient">{L.latest}</span>}
                <ChevronRight size={15} className="clg-chevron" aria-hidden="true" />
              </summary>
              <div className="clg-body">
                {version.html && <MarkdownView html={version.html} locale={meta.locale} />}
              </div>
            </details>
          </section>
        ))}
        {!page && (
          <div className="doc-prose" aria-busy="true">
            <p>…</p>
          </div>
        )}
      </div>
    </article>
  )
}
