import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { LayoutGrid, Search, Star } from 'lucide-react'
import '@/styles/pages.css'
import { services } from '@/services'
import type { CategoryDescriptor, PluginDescriptor } from '@/services/types'
import { SpotlightCard } from '@/components/aceternity/SpotlightCard'
import { FadeIn } from '@/components/pages/FadeIn'
import { PageEmpty, PageError, PageLoading } from '@/components/pages/StateViews'
import { cn } from '@/lib/utils'

/** Same local-persistence slot the Vue ToolGrid used — favorites survive the rewrite. */
const FAVORITES_STORAGE_KEY = 'fengyu:tools-grid:favorite-plugins'

function loadFavorites(): Set<string> {
  try {
    const raw = localStorage.getItem(FAVORITES_STORAGE_KEY)
    return new Set(raw ? (JSON.parse(raw) as string[]) : [])
  } catch {
    return new Set()
  }
}

function persistFavorites(favorites: Set<string>): void {
  try {
    localStorage.setItem(FAVORITES_STORAGE_KEY, JSON.stringify([...favorites]))
  } catch {
    /* private mode — favorites are cosmetic */
  }
}

/**
 * Tools (React twin of the Vue ToolGrid): the installed-plugin grid with
 * search, category chips and local favorites. The Vue shell navigated to
 * /plugin/:id; that route redirects back here now, so a card click expands
 * the card's own detail section instead (local state only).
 */
export default function ToolsPage() {
  const { t } = useTranslation()
  const [plugins, setPlugins] = useState<PluginDescriptor[]>([])
  const [categories, setCategories] = useState<CategoryDescriptor[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [category, setCategory] = useState('all')
  const [search, setSearch] = useState('')
  const [favorites, setFavorites] = useState<Set<string>>(() => loadFavorites())
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [descriptors, cats] = await Promise.all([
        services.plugin.list(),
        services.plugin.categories().catch(() => [] as CategoryDescriptor[]),
      ])
      setPlugins(descriptors)
      setCategories(cats)
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => { void reload() }, [reload])

  const chips = useMemo(() => [
    { key: 'all', label: t('sidebar.all') },
    ...categories.map(cat => ({ key: cat.id, label: t(cat.labelKey) })),
    { key: 'favorites', label: t('sidebar.favorites') },
  ], [categories, t])

  const filtered = useMemo(() => {
    let list = plugins
    if (category === 'favorites') list = list.filter(plugin => favorites.has(plugin.id))
    else if (category !== 'all') list = list.filter(plugin => plugin.category.toLowerCase() === category)
    const needle = search.trim().toLowerCase()
    if (needle) {
      list = list.filter(plugin =>
        `${plugin.name} ${plugin.description}`.toLowerCase().includes(needle))
    }
    return list
  }, [plugins, category, favorites, search])

  function toggleFavorite(id: string): void {
    setFavorites(current => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      persistFavorites(next)
      return next
    })
  }

  function categoryLabel(plugin: PluginDescriptor): string {
    const key = `category.${plugin.category.toLowerCase()}`
    const translated = t(key)
    return translated === key ? plugin.category : translated
  }

  return (
    <div className="pg-page">
      <div className="pg-inner pg-inner--wide">
        <FadeIn>
          <header className="pg-header">
            <div className="pg-header__text">
              <h1 className="pg-title">{t('grid.title')}</h1>
            </div>
          </header>
          <div className="pg-toolbar">
            <div className="pg-toolbar__row">
              <div className="pg-search">
                <Search size={15} />
                <input
                  className="cx-input"
                  placeholder={t('grid.search')}
                  aria-label={t('grid.search')}
                  value={search}
                  onChange={event => setSearch(event.target.value)}
                />
              </div>
            </div>
            <div className="pg-chips" role="group" aria-label={t('sidebar.categories')}>
              {chips.map(chip => (
                <button
                  key={chip.key}
                  className={cn('pg-chip-filter', category === chip.key && 'pg-chip-filter--active')}
                  onClick={() => setCategory(chip.key)}
                >{chip.label}</button>
              ))}
            </div>
          </div>
        </FadeIn>

        {error && <PageError message={error} onRetry={() => void reload()} />}
        {loading
          ? <PageLoading label={t('grid.loading')} />
          : !error && filtered.length === 0
            ? (
              <PageEmpty
                icon={<LayoutGrid size={30} strokeWidth={1.5} />}
                title={t('grid.empty')}
              />
            )
            : (
              <div className="pg-grid">
                {filtered.map((plugin, index) => {
                  const faved = favorites.has(plugin.id)
                  const expanded = expandedId === plugin.id
                  return (
                    <FadeIn key={plugin.id} delay={Math.min(index * 0.03, 0.24)}>
                      <SpotlightCard className={cn('pg-card', expanded && 'pg-card--open')}>
                        <div
                          className="pg-card-click"
                          role="button"
                          tabIndex={0}
                          aria-expanded={expanded}
                          onClick={() => setExpandedId(expanded ? null : plugin.id)}
                          onKeyDown={event => {
                            if (event.key === 'Enter' || event.key === ' ') {
                              event.preventDefault()
                              setExpandedId(expanded ? null : plugin.id)
                            }
                          }}
                        >
                          <div className="pg-card-head">
                            <span className="pg-icon pg-icon--flat">{initials(plugin.name)}</span>
                            <div className="pg-card-titlewrap">
                              <div className="pg-card-title"><span title={plugin.name}>{plugin.name}</span></div>
                              <div className="pg-card-meta">
                                {categoryLabel(plugin)} · v{plugin.version}
                              </div>
                            </div>
                            <button
                              className={cn('cx-iconbtn cx-iconbtn--sm pg-tool-fav', faved && 'pg-tool-fav--faved')}
                              title={t('grid.toggleFavorite')}
                              aria-label={t('grid.toggleFavorite')}
                              aria-pressed={faved}
                              onClick={event => {
                                event.stopPropagation()
                                toggleFavorite(plugin.id)
                              }}
                            ><Star size={16} fill={faved ? 'currentColor' : 'none'} /></button>
                          </div>
                          <p className={cn('pg-card-desc', expanded && 'pg-card-desc--open')}>
                            {plugin.description}
                          </p>
                          <div className="pg-card-row">
                            <span className={cn('cx-chip', plugin.source === 'OFFICIAL' && 'cx-chip--primary')}>
                              {plugin.source === 'OFFICIAL' ? t('source.official') : t('source.third_party')}
                            </span>
                            {plugin.supportsAi && (
                              <span className="cx-chip cx-chip--success">{t('badge.ai')}</span>
                            )}
                            {plugin.enabled === false && (
                              <span className="cx-chip cx-chip--warn">{t('store.sources.disable')}</span>
                            )}
                          </div>
                          {expanded && (
                            <FadeIn className="pg-detail">
                              <div className="pg-detail__row">
                                <span className="pg-detail__label">{t('store.publisher')}</span>
                                <span className="pg-detail__value">{plugin.author || '—'}</span>
                              </div>
                              <div className="pg-detail__row">
                                <span className="pg-detail__label">{t('skillsMarket.version')}</span>
                                <span className="pg-detail__value">v{plugin.version}</span>
                              </div>
                              {plugin.permissions && plugin.permissions.length > 0 && (
                                <div className="pg-detail__row">
                                  <span className="pg-detail__label">{t('store.permissions')}</span>
                                  <span className="pg-card-row">
                                    {plugin.permissions.map(permission => (
                                      <code key={permission} className="cx-chip">{permission}</code>
                                    ))}
                                  </span>
                                </div>
                              )}
                              <div className="pg-detail__row">
                                <span className="pg-detail__label">ID</span>
                                <span className="pg-detail__value"><code>{plugin.id}</code></span>
                              </div>
                            </FadeIn>
                          )}
                        </div>
                      </SpotlightCard>
                    </FadeIn>
                  )
                })}
              </div>
            )}
      </div>
    </div>
  )
}

/** Plugin manifests ship mdi icon names; the React shell has no mdi webfont, so use the Vue shell's initials fallback. */
function initials(name: string): string {
  return name.trim().charAt(0).toUpperCase() || '?'
}
