import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Puzzle, RefreshCw, Search, Store, Trash2 } from 'lucide-react'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import type { StoreSourceType, UnifiedCatalogEntry } from '@/services/types'
import { SpotlightCard } from '@/components/aceternity/SpotlightCard'
import { toastError } from '@/stores/toasts'
import { cn } from '@/lib/utils'
import { FadeIn } from './FadeIn'
import { PageEmpty, PageError, PageLoading } from './StateViews'
import { ToggleSwitch } from './ToggleSwitch'

const SOURCE_TYPES: StoreSourceType[] = ['FENGYU', 'CLAUDE', 'CODEX', 'GROK']

/**
 * Plugin market panel (React twin of the Vue UnifiedSourcesPanel catalog):
 * the unified plugin-store catalog aggregated across the subscribed sources,
 * with the full install / update / uninstall / enable-disable lifecycle. This
 * is the one store surface whose API covers every action the page promises.
 */
export function PluginMarketPanel() {
  const { t } = useTranslation()
  const [entries, setEntries] = useState<UnifiedCatalogEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busyUid, setBusyUid] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [sourceFilter, setSourceFilter] = useState<StoreSourceType | 'ALL'>('ALL')

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const catalog = await services.store.catalog()
      setEntries(catalog)
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => { void reload() }, [reload])

  const needle = search.trim().toLowerCase()
  const filtered = useMemo(() => entries.filter((entry) => {
    if (sourceFilter !== 'ALL' && entry.sourceType !== sourceFilter) return false
    if (!needle) return true
    return `${entry.displayName} ${entry.name} ${entry.description} ${entry.category ?? ''}`
      .toLowerCase()
      .includes(needle)
  }), [entries, needle, sourceFilter])

  /** Runs one store action with per-card busy state; failures surface as toasts. */
  async function run(uid: string, action: () => Promise<unknown>): Promise<void> {
    if (busyUid) return
    setBusyUid(uid)
    try {
      await action()
      await reload()
    } catch (e) {
      toastError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setBusyUid(null)
    }
  }

  async function install(entry: UnifiedCatalogEntry): Promise<void> {
    // Platforms without OS-level permission enforcement ask for an explicit ack.
    if (entry.permissionsOsEnforced === false
      && !await getPlatform().confirm(t('store.sources.confirmInstallNotEnforced', { name: entry.displayName }))) {
      return
    }
    await run(entry.uid, () => services.store.install(entry.uid))
  }

  async function update(entry: UnifiedCatalogEntry): Promise<void> {
    const prompt = entry.permissionsOsEnforced === false
      ? `${t('store.sources.confirmUpdatePermissions')}\n\n${t('store.permissionsNotOsEnforced')}`
      : t('store.sources.confirmUpdatePermissions')
    if (!await getPlatform().confirm(prompt)) return
    await run(entry.uid, () => services.store.update(entry.uid, true))
  }

  async function uninstall(entry: UnifiedCatalogEntry): Promise<void> {
    if (!await getPlatform().confirm(t('store.confirmUninstall', { name: entry.displayName }), { danger: true })) return
    await run(entry.uid, () => services.store.uninstall(entry.uid, false))
  }

  const setEnabled = (entry: UnifiedCatalogEntry, enabled: boolean) =>
    void run(entry.uid, () => services.store.setEnabled(entry.uid, enabled))

  return (
    <div>
      <div className="pg-toolbar">
        <div className="pg-toolbar__row">
          <div className="pg-search">
            <Search size={15} />
            <input
              className="cx-input"
              placeholder={t('store.sources.search')}
              aria-label={t('store.sources.search')}
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
        <div className="pg-chips" role="group" aria-label={t('store.sources.sourceTypeAll')}>
          <button
            className={cn('pg-chip-filter', sourceFilter === 'ALL' && 'pg-chip-filter--active')}
            onClick={() => setSourceFilter('ALL')}
          >{t('store.sources.sourceTypeAll')}</button>
          {SOURCE_TYPES.map(type => (
            <button
              key={type}
              className={cn('pg-chip-filter', sourceFilter === type && 'pg-chip-filter--active')}
              onClick={() => setSourceFilter(type)}
            >{t(`source.${type.toLowerCase()}`)}</button>
          ))}
        </div>
      </div>

      {error && <PageError message={error} onRetry={() => void reload()} />}
      {loading && entries.length === 0
        ? <PageLoading label={t('store.loading')} />
        : !error && filtered.length === 0
          ? (
            <PageEmpty
              icon={<Store size={30} strokeWidth={1.5} />}
              title={t('store.sources.noSources')}
              hint={t('store.subtitle')}
            />
          )
          : (
            <div className="pg-grid">
              {filtered.map((entry, index) => (
                <FadeIn key={entry.uid} delay={Math.min(index * 0.03, 0.24)}>
                  <SpotlightCard className="pg-card">
                    <div className="pg-card-head">
                      <span className="pg-icon"><Puzzle size={19} /></span>
                      <div className="pg-card-titlewrap">
                        <div className="pg-card-title">
                          <span title={entry.displayName}>{entry.displayName}</span>
                        </div>
                        <div className="pg-card-meta">
                          {t(`source.${entry.sourceType.toLowerCase()}`)}
                          {entry.category ? ` · ${entry.category}` : ''}
                          {entry.author?.name ? ` · ${entry.author.name}` : ''}
                        </div>
                      </div>
                      {entry.installed && (
                        <span className="cx-chip cx-chip--success">{t('store.sources.installed')}</span>
                      )}
                    </div>
                    <p className="pg-card-desc">{entry.description}</p>
                    <div className="pg-card-actions">
                      <span className="pg-version">
                        {entry.installed && entry.updateAvailable
                          ? `${entry.installedVersion ?? '—'} → ${entry.availableVersion ?? '—'}`
                          : `v${(entry.installed ? entry.installedVersion : entry.availableVersion) ?? '—'}`}
                      </span>
                      {entry.installed && (
                        <button
                          className="cx-iconbtn cx-iconbtn--sm"
                          title={t('store.sources.uninstall')}
                          aria-label={t('store.sources.uninstall')}
                          disabled={busyUid === entry.uid}
                          onClick={() => void uninstall(entry)}
                        ><Trash2 size={15} /></button>
                      )}
                      {!entry.installed
                        ? (
                          <button
                            className="cx-btn cx-btn--primary cx-btn--sm"
                            disabled={busyUid === entry.uid}
                            onClick={() => void install(entry)}
                          >
                            {busyUid === entry.uid && <span className="cx-spin" />}
                            {busyUid === entry.uid ? t('store.sources.cloneInProgress') : t('store.sources.install')}
                          </button>
                        )
                        : entry.updateAvailable
                          ? (
                            <button
                              className="cx-btn cx-btn--outline cx-btn--sm"
                              disabled={busyUid === entry.uid}
                              onClick={() => void update(entry)}
                            >{t('store.sources.update')}</button>
                          )
                          : (
                            <ToggleSwitch
                              checked={entry.enabled}
                              disabled={busyUid === entry.uid}
                              label={entry.enabled ? t('store.sources.disable') : t('store.sources.enable')}
                              onChange={next => setEnabled(entry, next)}
                            />
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
