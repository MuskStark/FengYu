import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Boxes, RefreshCw, ScrollText, Search, Trash2 } from 'lucide-react'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import type { MarketplaceSkill, PluginDescriptor, SkillSummary } from '@/services/types'
import { usePluginsStore } from '@/stores/plugins'
import { SpotlightCard } from '@/components/aceternity/SpotlightCard'
import { ToolsCardGrid } from '@/components/pages/ToolsCardGrid'
import { toastError } from '@/stores/toasts'
import { loadFavorites, persistFavorites } from '@/lib/toolFavorites'
import { cn } from '@/lib/utils'
import { FadeIn } from './FadeIn'
import { BentoSkeleton, PageEmpty, PageError } from './StateViews'
import { ToggleSwitch } from './ToggleSwitch'

/**
 * The Installed tab's unified inventory: installed plugins AND skills in one
 * searchable, type-filterable view. Plugins render through the Tools page's
 * own card grid (favorites, enable-toggle, uninstall, expandable detail — the
 * full lifecycle, with favorites shared through lib/toolFavorites so both
 * surfaces stay in sync); skills keep the toggle/update/uninstall lifecycle,
 * builtin skills marked as such.
 */

type InstalledKind = 'all' | 'plugin' | 'skill'

interface SkillRow {
  id: string
  name: string
  description: string
  meta: string
  official: boolean
  builtin: boolean
  enabled: boolean
  updateAvailable: boolean
}

export function InstalledPanel() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const plugins = usePluginsStore(state => state.plugins)
  const loadPlugins = usePluginsStore(state => state.load)
  const [market, setMarket] = useState<MarketplaceSkill[]>([])
  const [builtin, setBuiltin] = useState<SkillSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [kind, setKind] = useState<InstalledKind>('all')
  const [favorites, setFavorites] = useState<Set<string>>(() => loadFavorites())

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [marketSkills, allSkills] = await Promise.all([services.skill.market(), services.skill.list()])
      setMarket(marketSkills)
      setBuiltin(allSkills.filter(skill => skill.source === 'BUILTIN'))
      await loadPlugins()
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setLoading(false)
    }
  }, [t, loadPlugins])

  useEffect(() => { void reload() }, [reload])

  const skillRows = useMemo<SkillRow[]>(() => [
    ...market.filter(skill => skill.installed).map((skill): SkillRow => ({
      id: skill.id,
      name: skill.name,
      description: skill.description,
      meta: `${skill.author || skill.id} · v${skill.installedVersion || skill.version}`,
      official: skill.official,
      builtin: false,
      enabled: skill.enabled,
      updateAvailable: skill.updateAvailable,
    })),
    ...builtin.map((skill): SkillRow => ({
      id: skill.id,
      name: skill.name,
      description: skill.description,
      meta: t('skillsMarket.builtin'),
      official: true,
      builtin: true,
      enabled: skill.enabled,
      updateAvailable: false,
    })),
  ], [market, builtin, t])

  const needle = search.trim().toLowerCase()
  const filteredPlugins = useMemo(() => plugins.filter(plugin =>
    !needle || `${plugin.name} ${plugin.description}`.toLowerCase().includes(needle),
  ), [plugins, needle])
  const filteredSkills = useMemo(() => skillRows.filter(row =>
    !needle || `${row.name} ${row.description} ${row.id}`.toLowerCase().includes(needle),
  ), [skillRows, needle])

  const showPlugins = kind !== 'skill'
  const showSkills = kind !== 'plugin'
  const empty = (showPlugins && filteredPlugins.length === 0) && (showSkills && filteredSkills.length === 0)

  function toggleFavorite(id: string): void {
    setFavorites(current => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      persistFavorites(next)
      return next
    })
  }

  /** Shared busy/refresh tail for plugin card actions (ToolsPage parity): run,
   * resync the cached plugins mirror (the store subscription re-renders us),
   * then reload the skill inventory. */
  async function runPluginAction(plugin: PluginDescriptor, action: () => Promise<void>): Promise<void> {
    if (busyId) return
    setBusyId(plugin.id)
    try {
      await action()
      usePluginsStore.setState({ loaded: false })
      await loadPlugins()
    } catch (e) {
      toastError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setBusyId(null)
    }
  }

  async function uninstallPlugin(plugin: PluginDescriptor): Promise<void> {
    if (!await getPlatform().confirm(t('store.confirmUninstall', { name: plugin.name }), { danger: true })) return
    await runPluginAction(plugin, () => services.plugin.uninstall(plugin.id))
  }

  async function toggleEnabled(plugin: PluginDescriptor): Promise<void> {
    await runPluginAction(plugin, () => services.plugin.setEnabled(plugin.id, plugin.enabled === false))
  }

  async function run(id: string, action: () => Promise<unknown>): Promise<void> {
    if (busyId) return
    setBusyId(id)
    try {
      await action()
      await reload()
    } catch (e) {
      toastError(`${t('skillsMarket.operationFailed')}: ${e instanceof Error && e.message ? e.message : ''}`)
    } finally {
      setBusyId(null)
    }
  }

  async function uninstallSkill(row: SkillRow): Promise<void> {
    if (!await getPlatform().confirm(t('skillsMarket.confirmUninstall'), { danger: true })) return
    await run(row.id, () => services.skill.uninstall(row.id))
  }

  const filters: Array<{ id: InstalledKind; label: string }> = [
    { id: 'all', label: t('store.typeAll') },
    { id: 'plugin', label: t('store.typePlugin') },
    { id: 'skill', label: t('store.typeSkill') },
  ]

  return (
    <div>
      <div className="pg-toolbar">
        <div className="pg-toolbar__row">
          <div className="pg-search">
            <Search size={15} />
            <input
              className="cx-input"
              placeholder={t('store.installedSearch')}
              aria-label={t('store.installedSearch')}
              value={search}
              onChange={event => setSearch(event.target.value)}
            />
          </div>
          <button
            className="cx-iconbtn"
            title={t('store.refresh')}
            aria-label={t('store.refresh')}
            disabled={loading}
            onClick={() => void reload()}
          ><RefreshCw size={17} className={cn(loading && 'pg-spin')} /></button>
        </div>
        <div className="pg-chips" role="group" aria-label={t('store.typeAll')}>
          {filters.map(filter => (
            <button
              key={filter.id}
              className={cn('pg-chip-filter', kind === filter.id && 'pg-chip-filter--active')}
              aria-pressed={kind === filter.id}
              onClick={() => setKind(filter.id)}
            >{filter.label}</button>
          ))}
        </div>
      </div>

      {error && <PageError message={error} onRetry={() => void reload()} />}
      {loading && skillRows.length === 0
        ? <BentoSkeleton />
        : !error && empty
          ? (
            <PageEmpty
              icon={<Boxes size={30} strokeWidth={1.5} />}
              title={t('store.installedEmpty')}
              hint={t('store.installedEmptyHint')}
            />
          )
          : (
            <>
              {showPlugins && filteredPlugins.length > 0 && (
                <>
                  <div className="cx-section-title">{t('store.typePlugin')}</div>
                  <ToolsCardGrid
                    plugins={filteredPlugins}
                    favorites={favorites}
                    busyId={busyId}
                    onToggleFavorite={toggleFavorite}
                    onOpen={id => navigate(`/plugin/${encodeURIComponent(id)}`)}
                    onUninstall={plugin => void uninstallPlugin(plugin)}
                    onToggleEnabled={plugin => void toggleEnabled(plugin)}
                  />
                </>
              )}
              {showSkills && filteredSkills.length > 0 && (
                <>
                  <div className="cx-section-title">{t('store.typeSkill')}</div>
                  <div className="pg-grid">
                    {filteredSkills.map((row, index) => (
                      <FadeIn key={`${row.builtin ? 'builtin' : 'id'}:${row.id}`} delay={Math.min(index * 0.03, 0.24)}>
                        <SpotlightCard className="pg-card">
                          <div className="pg-card-head">
                            <span className="pg-icon"><ScrollText size={18} /></span>
                            <div className="pg-card-titlewrap">
                              <div className="pg-card-title">
                                <span title={row.name}>{row.name}</span>
                              </div>
                              <div className="pg-card-meta">
                                {t('store.typeSkill')}
                                {` · ${row.meta}`}
                              </div>
                            </div>
                            {row.builtin && <span className="cx-chip">{t('skillsMarket.builtin')}</span>}
                          </div>
                          <p className="pg-card-desc">{row.description}</p>
                          <div className="pg-card-actions">
                            <span className={cn('cx-chip', row.official && 'cx-chip--gold')}>
                              {row.official ? t('skillsMarket.official') : t('skillsMarket.thirdParty')}
                            </span>
                            <span className="spacer" />
                            {row.builtin ? (
                              <ToggleSwitch
                                checked={row.enabled}
                                disabled={busyId === row.id}
                                label={row.enabled ? t('skillsMarket.disable') : t('skillsMarket.enable')}
                                onChange={next => void run(row.id, () => services.skill.setEnabled(row.id, next))}
                              />
                            ) : (
                              <>
                                {row.updateAvailable && (
                                  <button
                                    className="cx-btn cx-btn--outline cx-btn--sm"
                                    disabled={busyId === row.id}
                                    onClick={() => void run(row.id, () => services.skill.update(row.id))}
                                  >{t('store.update')}</button>
                                )}
                                <ToggleSwitch
                                  checked={row.enabled}
                                  disabled={busyId === row.id}
                                  label={row.enabled ? t('skillsMarket.disable') : t('skillsMarket.enable')}
                                  onChange={next => void run(row.id, () => services.skill.setEnabled(row.id, next))}
                                />
                                <button
                                  className="cx-iconbtn cx-iconbtn--sm"
                                  title={t('skillsMarket.uninstall')}
                                  aria-label={t('skillsMarket.uninstall')}
                                  disabled={busyId === row.id}
                                  onClick={() => void uninstallSkill(row)}
                                ><Trash2 size={15} /></button>
                              </>
                            )}
                          </div>
                        </SpotlightCard>
                      </FadeIn>
                    ))}
                  </div>
                </>
              )}
            </>
          )}
    </div>
  )
}
