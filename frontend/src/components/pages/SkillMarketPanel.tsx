import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { RefreshCw, ScrollText, Search, Trash2 } from 'lucide-react'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import type { MarketplaceSkill, SkillSummary } from '@/services/types'
import { SpotlightCard } from '@/components/aceternity/SpotlightCard'
import { toastError } from '@/stores/toasts'
import { cn } from '@/lib/utils'
import { FadeIn } from './FadeIn'
import { PageEmpty, PageError, PageLoading } from './StateViews'
import { ToggleSwitch } from './ToggleSwitch'

/** Normalized card row for either a builtin skill or a market/installable one. */
interface SkillRow {
  id: string
  name: string
  description: string
  meta: string
  official: boolean
  builtin: boolean
  installed: boolean
  enabled: boolean
  updateAvailable: boolean
}

/**
 * Skills market panel (React twin of the Vue SkillsMarketPanel): builtin +
 * catalog skills in one SpotlightCard grid with the install / update /
 * toggle / uninstall lifecycle. Builtin skills ship with the app and stay
 * read-only.
 */
export function SkillMarketPanel() {
  const { t } = useTranslation()
  const [market, setMarket] = useState<MarketplaceSkill[]>([])
  const [builtin, setBuiltin] = useState<SkillSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [search, setSearch] = useState('')

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [marketSkills, allSkills] = await Promise.all([services.skill.market(), services.skill.list()])
      setMarket(marketSkills)
      setBuiltin(allSkills.filter(skill => skill.source === 'BUILTIN'))
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => { void reload() }, [reload])

  const rows = useMemo<SkillRow[]>(() => [
    ...builtin.map((skill): SkillRow => ({
      id: skill.id,
      name: skill.name,
      description: skill.description,
      meta: t('skillsMarket.builtin'),
      official: true,
      builtin: true,
      installed: false,
      enabled: skill.enabled,
      updateAvailable: false,
    })),
    ...market.map((skill): SkillRow => ({
      id: skill.id,
      name: skill.name,
      description: skill.description,
      meta: `${skill.author || skill.id} · v${skill.installedVersion || skill.version}`,
      official: skill.official,
      builtin: false,
      installed: skill.installed,
      enabled: skill.enabled,
      updateAvailable: skill.updateAvailable,
    })),
  ], [builtin, market, t])

  const needle = search.trim().toLowerCase()
  const cards = useMemo(() => rows.filter(row =>
    !needle || `${row.name} ${row.description} ${row.id}`.toLowerCase().includes(needle),
  ), [rows, needle])

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

  async function uninstall(row: SkillRow): Promise<void> {
    if (!await getPlatform().confirm(t('skillsMarket.confirmUninstall'), { danger: true })) return
    await run(row.id, () => services.skill.uninstall(row.id))
  }

  return (
    <div>
      <div className="pg-toolbar">
        <div className="pg-toolbar__row">
          <div className="pg-search">
            <Search size={15} />
            <input
              className="cx-input"
              placeholder={t('skillsMarket.search')}
              aria-label={t('skillsMarket.search')}
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
      </div>

      {error && <PageError message={error} onRetry={() => void reload()} />}
      {loading && rows.length === 0
        ? <PageLoading label={t('skills.loading')} />
        : !error && cards.length === 0
          ? (
            <PageEmpty
              icon={<ScrollText size={30} strokeWidth={1.5} />}
              title={rows.length ? t('skillsMarket.empty') : t('skillsMarket.catalogEmpty')}
              hint={t('skillsMarket.howItWorks')}
            />
          )
          : (
            <div className="pg-grid">
              {cards.map((row, index) => (
                <FadeIn key={`${row.builtin ? 'builtin' : 'market'}:${row.id}`} delay={Math.min(index * 0.03, 0.24)}>
                  <SpotlightCard className="pg-card">
                    <div className="pg-card-head">
                      <span className="pg-icon"><ScrollText size={18} /></span>
                      <div className="pg-card-titlewrap">
                        <div className="pg-card-title">
                          <span title={row.name}>{row.name}</span>
                        </div>
                        <div className="pg-card-meta">{row.builtin ? t('skillsMarket.builtin') : row.meta}</div>
                      </div>
                      {row.installed && (
                        <span className="cx-chip cx-chip--success">{t('skillsMarket.installedLabel')}</span>
                      )}
                    </div>
                    <p className="pg-card-desc">{row.description}</p>
                    <div className="pg-card-row">
                      <span className="cx-chip">
                        {row.official ? t('skillsMarket.official') : t('skillsMarket.thirdParty')}
                      </span>
                      {row.builtin && <span className="cx-chip">{t('skillsMarket.builtin')}</span>}
                    </div>
                    <div className="pg-card-actions">
                      {row.builtin
                        ? <span className="cx-muted cx-grow" style={{ fontSize: 11.5 }}>{t('skillsMarket.builtinReadonly')}</span>
                        : (
                          <>
                            <span className="pg-version">{row.meta.split(' · ')[1] ?? ''}</span>
                            {!row.installed
                              ? (
                                <button
                                  className="cx-btn cx-btn--primary cx-btn--sm"
                                  disabled={busyId === row.id}
                                  onClick={() => void run(row.id, () => services.skill.install(row.id))}
                                >
                                  {busyId === row.id && <span className="cx-spin" />}
                                  {t('skillsMarket.install')}
                                </button>
                              )
                              : (
                                <>
                                  {row.updateAvailable && (
                                    <button
                                      className="cx-btn cx-btn--outline cx-btn--sm"
                                      disabled={busyId === row.id}
                                      onClick={() => void run(row.id, () => services.skill.update(row.id))}
                                    >{t('skillsMarket.update')}</button>
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
                                    onClick={() => void uninstall(row)}
                                  ><Trash2 size={15} /></button>
                                </>
                              )}
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
