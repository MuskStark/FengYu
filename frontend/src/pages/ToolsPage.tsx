import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { LayoutGrid, Search } from 'lucide-react'
import '@/styles/pages.css'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import type { CategoryDescriptor, PluginDescriptor } from '@/services/types'
import { FadeIn } from '@/components/pages/FadeIn'
import { PageEmpty, PageError, PageLoading } from '@/components/pages/StateViews'
import { ToolsCardGrid } from '@/components/pages/ToolsCardGrid'
import { toastError } from '@/stores/toasts'
import { usePluginsStore } from '@/stores/plugins'
import { cn } from '@/lib/utils'

/** Same local-persistence slot the Vue ToolGrid used — favorites survive the rewrite. */
const FAVORITES_STORAGE_KEY = 'fengyu:tools-grid:favorite-plugins'

function loadFavorites(): Set<string> {
  try {
    const raw = localStorage.getItem(FAVORITES_STORAGE_KEY)
    return new Set(raw ? (JSON.parse(raw) as string[]) : [])
  } catch {
    return new Set<string>()
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
 * Tools: the installed-plugin grid. Cards expand in place into the detail
 * layer ({@link ToolsCardGrid}); this page owns the data lifecycle, the
 * favorites filter, and the card actions (open / uninstall / enable-toggle —
 * all local lifecycle APIs, no store channel needed).
 */
export default function ToolsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [plugins, setPlugins] = useState<PluginDescriptor[]>([])
  const [categories, setCategories] = useState<CategoryDescriptor[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [category, setCategory] = useState('all')
  const [search, setSearch] = useState('')
  const [favorites, setFavorites] = useState<Set<string>>(() => loadFavorites())
  const [busyId, setBusyId] = useState<string | null>(null)

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

  /** Shared busy/refresh tail for card lifecycle actions: run, reload, resync the
   * cached plugins mirror; failures surface as toasts. */
  async function runCardAction(plugin: PluginDescriptor, action: () => Promise<void>): Promise<void> {
    if (busyId) return
    setBusyId(plugin.id)
    try {
      await action()
      await reload()
      usePluginsStore.setState({ loaded: false })
      await usePluginsStore.getState().load()
    } catch (e) {
      toastError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setBusyId(null)
    }
  }

  async function uninstall(plugin: PluginDescriptor): Promise<void> {
    if (!await getPlatform().confirm(t('store.confirmUninstall', { name: plugin.name }), { danger: true })) return
    await runCardAction(plugin, () => services.plugin.uninstall(plugin.id))
  }

  async function toggleEnabled(plugin: PluginDescriptor): Promise<void> {
    await runCardAction(plugin, () => services.plugin.setEnabled(plugin.id, plugin.enabled === false))
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
              <ToolsCardGrid
                plugins={filtered}
                favorites={favorites}
                busyId={busyId}
                onToggleFavorite={toggleFavorite}
                onOpen={id => navigate(`/plugin/${encodeURIComponent(id)}`)}
                onUninstall={plugin => void uninstall(plugin)}
                onToggleEnabled={plugin => void toggleEnabled(plugin)}
              />
            )}
      </div>
    </div>
  )
}
