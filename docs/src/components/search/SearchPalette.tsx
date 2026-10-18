import { Search } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useSearch } from '@/App'
import type { Locale } from '@/content/navigation'
import { t } from '@/i18n'

/** Loaded on first palette open — keeps MiniSearch out of the SPA shell chunk. */
type MiniSearchLike = {
  search: (query: string, options?: Record<string, unknown>) => Array<{ id: string; title: string; breadcrumb: string }>
}

/** Same tokenizer rule as the build side (scripts/build-content.mjs cjkProcessTerm): CJK → character bigrams. */
function cjkProcessTerm(term: string): string[] | string | null {
  const text = term.toLowerCase()
  if (!/[\u4e00-\u9fff]/.test(text)) return text || null
  if (text.length <= 2) return [text]
  const grams: string[] = []
  for (let i = 0; i < text.length - 1; i += 1) grams.push(text.slice(i, i + 2))
  return grams
}

const SEARCH_OPTIONS = {
  fields: ['title', 'headings', 'body'] as const,
  storeFields: ['title', 'breadcrumb'] as const,
  processTerm: cjkProcessTerm,
}

const indexModules = import.meta.glob<{ default: { index: unknown } }>('/src/.content/search-*.json')

const indexCache = new Map<Locale, MiniSearchLike>()

async function loadIndex(locale: Locale): Promise<MiniSearchLike | null> {
  const cached = indexCache.get(locale)
  if (cached) return cached
  const loader = indexModules[`/src/.content/search-${locale}.json`]
  if (!loader) return null
  const [{ default: MiniSearch }, { default: payload }] = await Promise.all([
    import('minisearch'),
    loader(),
  ])
  const index = (MiniSearch as unknown as {
    loadJSON: (json: string, options: unknown) => MiniSearchLike
  }).loadJSON(JSON.stringify(payload.index), SEARCH_OPTIONS)
  indexCache.set(locale, index)
  return index
}

interface Hit {
  id: string
  title: string
  breadcrumb: string
}

export function SearchPalette({ locale }: { locale: Locale }) {
  const { open, setOpen } = useSearch()
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<Hit[]>([])
  const [selected, setSelected] = useState(0)
  const [ready, setReady] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const navigate = useNavigate()
  const L = t(locale)

  useEffect(() => {
    if (!open) return
    setReady(false)
    setQuery('')
    setHits([])
    setSelected(0)
    let cancelled = false
    loadIndex(locale).then((index) => {
      if (!cancelled) setReady(index !== null)
    })
    return () => {
      cancelled = true
    }
  }, [open, locale])

  useEffect(() => {
    if (!open || !ready) return
    let cancelled = false
    loadIndex(locale).then((index) => {
      if (cancelled || !index) return
      const trimmed = query.trim()
      if (!trimmed) {
        setHits([])
        return
      }
      const results = index.search(trimmed, { fuzzy: 0.2, prefix: true })
      setHits(results.slice(0, 12))
      setSelected(0)
    })
    return () => {
      cancelled = true
    }
  }, [query, ready, open, locale])

  // Body scroll lock + focus while open.
  useEffect(() => {
    if (!open) return
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    requestAnimationFrame(() => inputRef.current?.focus())
    return () => {
      document.body.style.overflow = previous
    }
  }, [open])

  const go = (route: string) => {
    setOpen(false)
    navigate(route)
  }

  const onInputKey = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Escape') {
      setOpen(false)
    } else if (event.key === 'ArrowDown') {
      event.preventDefault()
      setSelected((index) => Math.min(index + 1, hits.length - 1))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setSelected((index) => Math.max(index - 1, 0))
    } else if (event.key === 'Enter') {
      const hit = hits[selected]
      if (hit) go(hit.id)
    }
  }

  const hint = useMemo(() => L.searchHint, [L])

  if (!open) return null
  return (
    <div
      className="doc-search-overlay"
      onClick={(event) => {
        if (event.target === event.currentTarget) setOpen(false)
      }}
    >
      <div className="doc-search" role="dialog" aria-modal="true" aria-label={L.search}>
        <div className="doc-search-bar">
          <Search size={16} />
          <input
            ref={inputRef}
            className="doc-search-input"
            type="text"
            placeholder={L.searchPlaceholder}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={onInputKey}
            aria-label={L.searchPlaceholder}
          />
          <kbd className="doc-kbd">Esc</kbd>
        </div>
        <div className="doc-search-list" role="listbox" aria-label={L.search}>
          {query.trim() && hits.length === 0 && (
            <p className="doc-search-empty">
              {ready ? `${L.searchNoResults} — “${query.trim()}”` : '…'}
            </p>
          )}
          {!query.trim() && (
            <p className="doc-search-empty">
              {L.searchPlaceholder}
              <br />
              <small>↑↓ {hint}</small>
            </p>
          )}
          {hits.map((hit, index) => (
            <button
              key={hit.id}
              type="button"
              role="option"
              aria-selected={index === selected}
              className="doc-search-item"
              onMouseEnter={() => setSelected(index)}
              onClick={() => go(hit.id)}
            >
              <span className="doc-search-item-title">{hit.title}</span>
              {hit.breadcrumb && <span className="doc-search-item-crumb">{hit.breadcrumb}</span>}
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}
