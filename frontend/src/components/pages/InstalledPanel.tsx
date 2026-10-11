import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Boxes, ExternalLink, RefreshCw, ScrollText, Search, Trash2 } from 'lucide-react'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import type { MarketplaceSkill, PluginDescriptor, SkillSummary } from '@/services/types'
import { usePluginsStore } from '@/stores/plugins'
import { SpotlightCard } from '@/components/aceternity/SpotlightCard'
import { toastError } from '@/stores/toasts'
import { cn } from '@/lib/utils'
import { FadeIn } from './FadeIn'
import { BentoSkeleton, PageEmpty, PageError } from './StateViews'
import { ToggleSwitch } from './ToggleSwitch'

/**
 * The Installed tab's unified inventory: installed plugins AND skills in one
 * searchable, type-filterable grid. Plugins stay read-mostly here (open +
 * state chip — lifecycle management lives on the Tools page); skills keep
 * their full toggle/update/uninstall lifecycle, mirroring the skills market.
 */

type InstalledKind = 'all' | 'plugin' | 'skill'

interface InstalledRow {
  kind: 'plugin' | 'skill'
  id: string
  name: string
  description: string
  meta: string
  official: boolean
  builtin: boolean
  enabled: boolean
  updateAvailable: boolean
}

function categoryLabel(t: (key: string) => string, category: string): string {
  const key = `category.${category.toLowerCase()}`
  const translated = t(key)
  return translated === key ? category : translated
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

  const rows = useMemo<InstalledRow[]>(() => [
    ...plugins.map((plugin: PluginDescriptor): InstalledRow => ({
      kind: 'plugin',
      id: plugin.id,
      name: plugin.name,
      description: plugin.description,
      meta: `${categoryLabel(t, plugin.category)} · v${plugin.version}`,
      official: plugin.source === 'OFFICIAL',
      builtin: false,
      enabled: plugin.enabled !== false,
      updateAvailable: false,
    })),
    ...market.filter(skill => skill.installed).map((skill): InstalledRow => ({
      kind: 'skill',
      id: skill.id,
      name: skill.name,
      description: skill.description,
      meta: `${skill.author || skill.id} · v${skill.installedVersion || skill.version}`,
      official: skill.official,
      builtin: false,
      enabled: skill.enabled,
      updateAvailable: skill.updateAvailable,
    })),
    ...builtin.map((skill): InstalledRow => ({
      kind: 'skill',
      id: skill.id,
      name: skill.name,
      description: skill.description,
      meta: t('skillsMarket.builtin'),
      official: true,
      builtin: true,
      enabled: skill.enabled,
      updateAvailable: false,
    })),
  ], [plugins, market, builtin, t])

  const needle = search.trim().toLowerCase()
  const cards = useMemo(() => rows.filter(row =>
    (kind === 'all' || row.kind === kind)
    && (!needle || `${row.name} ${row.description} ${row.id}`.toLowerCase().includes(needle)),
  ), [rows, kind, needle])

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

  async function uninstallSkill(row: InstalledRow): Promise<void> {
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
      {loading && rows.length === 0
        ? <BentoSkeleton />
        : !error && cards.length === 0
          ? (
            <PageEmpty
              icon={<Boxes size={30} strokeWidth={1.5} />}
              title={t('store.installedEmpty')}
              hint={t('store.installedEmptyHint')}
            />
          )
          : (
            <div className="pg-grid">
              {cards.map((row, index) => (
                <FadeIn key={`${row.kind}:${row.builtin ? 'builtin' : 'id'}:${row.id}`} delay={Math.min(index * 0.03, 0.24)}>
                  <SpotlightCard className="pg-card">
                    <div className="pg-card-head">
                      <span className="pg-icon">
                        {row.kind === 'plugin' ? <Boxes size={18} /> : <ScrollText size={18} />}
                      </span>
                      <div className="pg-card-titlewrap">
                        <div className="pg-card-title">
                          <span title={row.name}>{row.name}</span>
                        </div>
                        <div className="pg-card-meta">
                          {row.kind === 'plugin' ? t('store.typePlugin') : t('store.typeSkill')}
                          {` · ${row.meta}`}
                        </div>
                      </div>
                      {row.kind === 'skill' && row.builtin && (
                        <span className="cx-chip">{t('skillsMarket.builtin')}</span>
                      )}
                    </div>
                    <p className="pg-card-desc">{row.description}</p>
                    <div className="pg-card-actions">
                      <span className={cn('cx-chip', row.official && 'cx-chip--gold')}>
                        {row.official ? t('skillsMarket.official') : t('skillsMarket.thirdParty')}
                      </span>
                      <span className="spacer" />
                      {row.kind === 'plugin' ? (
                        <button
                          className="cx-btn cx-btn--outline cx-btn--sm"
                          onClick={() => navigate(`/plugin/${encodeURIComponent(row.id)}`)}
                        >
                          <ExternalLink size={13} />
                          {t('store.open')}
                        </button>
                      ) : row.builtin ? (
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
          )}
    </div>
  )
}
