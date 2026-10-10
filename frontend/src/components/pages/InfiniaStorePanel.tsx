import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, GraduationCap, LogIn, Network, PackagePlus, Puzzle, RefreshCw, Search, Store, UserRound } from 'lucide-react'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import type { AccountView } from '@/services/account'
import type { PackageInspection, StoreCatalogEntry } from '@/services/types'
import { SpotlightCard } from '@/components/aceternity/SpotlightCard'
import { toastError, useToastStore } from '@/stores/toasts'
import { usePluginsStore } from '@/stores/plugins'
import { useSkillsStore } from '@/stores/skills'
import { cn } from '@/lib/utils'
import { FadeIn } from './FadeIn'
import { PageEmpty, PageError, PageLoading } from './StateViews'

type TypeFilter = 'ALL' | 'PLUGIN' | 'SKILL' | 'MCP'

const SIGN_IN_POLL_INTERVAL_MS = 1500
const SIGN_IN_TIMEOUT_MS = 5 * 60 * 1000
/** Backend marker of the "inspect and explicitly confirm" verdict (PluginPackageService). */
const ESCALATION_MARKER = 'confirm the permission escalation'
/** Search goes to the store server-side (it covers the whole catalog), debounced per keystroke. */
const SEARCH_DEBOUNCE_MS = 400

/**
 * Backend failure reasons arrive as English prose (UrlPolicy / connectivity strings).
 * Known shapes get a localized headline with the raw text demoted to the detail line,
 * matching the update-check alert's pattern; anything unrecognized shows verbatim.
 */
function localizeError(raw: string, t: (key: string) => string): { message: string; detail?: string } {
  if (/rejected by the URL policy|SSRF/i.test(raw)) {
    return { message: t('store.errorChannelBlocked'), detail: raw }
  }
  if (/connect|timeout|unreach|resolve|I\/O failure/i.test(raw)) {
    return { message: t('store.errorChannelUnreachable'), detail: raw }
  }
  return { message: raw }
}

/**
 * The official Infinia store front: the production catalog (plugins, skills, MCP
 * servers) plus the cloud-account sign-in entry, all through the native store
 * channel (`/api/store/*`). The catalog browses INCREMENTALLY — one 100-row page
 * per request, so first paint is a single round-trip; the store's cursor drives
 * "load more", and the type filter and search are server-side store queries
 * (over 1300 listings, client-side search would only see the loaded pages).
 */
export function InfiniaStorePanel() {
  const { t } = useTranslation()
  const [entries, setEntries] = useState<StoreCatalogEntry[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const [searchInput, setSearchInput] = useState('')
  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState<TypeFilter>('ALL')
  const [account, setAccount] = useState<AccountView | null>(null)
  const [signInBusy, setSignInBusy] = useState(false)
  const [localBusy, setLocalBusy] = useState(false)
  const localInputRef = useRef<HTMLInputElement>(null)
  const pushToast = useToastStore(state => state.push)
  const pollAborted = useRef(false)

  useEffect(() => {
    const id = window.setTimeout(() => setSearch(searchInput.trim()), SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(id)
  }, [searchInput])

  /** Monotonic query epoch: a reload bumps it, and any in-flight response from an
   *  older epoch (e.g. a loadMore that raced a filter change) is dropped instead of
   *  grafting stale rows onto the new list. */
  const queryEpoch = useRef(0)

  const reload = useCallback(async () => {
    const epoch = ++queryEpoch.current
    setLoading(true)
    setError(null)
    try {
      const params: { type?: string; query?: string } = {}
      if (typeFilter !== 'ALL') params.type = typeFilter
      if (search) params.query = search
      const [page, me] = await Promise.all([
        services.infiniaStore.catalog(params),
        services.account.me().catch(() => null),
      ])
      if (epoch !== queryEpoch.current) return
      setEntries(page.items)
      setNextCursor(page.nextCursor)
      setAccount(me && me.authenticated ? me : null)
    } catch (e) {
      if (epoch !== queryEpoch.current) return
      setError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      if (epoch === queryEpoch.current) setLoading(false)
    }
  }, [t, typeFilter, search])

  useEffect(() => {
    pollAborted.current = false
    void reload()
    return () => { pollAborted.current = true }
  }, [reload])

  /** Appends the next cursor page (one request); keeps the already-loaded rows. */
  const loadMore = useCallback(async () => {
    if (!nextCursor || loadingMore || loading) return
    const epoch = queryEpoch.current
    setLoadingMore(true)
    try {
      const params: { type?: string; query?: string; cursor: string } = { cursor: nextCursor }
      if (typeFilter !== 'ALL') params.type = typeFilter
      if (search) params.query = search
      const page = await services.infiniaStore.catalog(params)
      if (epoch !== queryEpoch.current) return
      setEntries(prev => [...prev, ...page.items])
      setNextCursor(page.nextCursor)
    } catch (e) {
      toastError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setLoadingMore(false)
    }
  }, [t, nextCursor, loadingMore, loading, typeFilter, search])

  /** Browser OAuth round-trip (same flow as the account page): open, poll, refresh. */
  async function signIn(): Promise<void> {
    if (signInBusy) return
    setSignInBusy(true)
    try {
      const started = await services.account.startSignIn()
      await getPlatform().openExternal(started.authorizationUrl)
      const deadline = Date.now() + SIGN_IN_TIMEOUT_MS
      while (!pollAborted.current) {
        if (Date.now() > deadline) throw new Error(t('common.unexpectedError'))
        const attempt = await services.account.signInStatus(started.attemptId)
        if (attempt.status === 'COMPLETED' && attempt.user) {
          setAccount(attempt.user)
          return
        }
        if (attempt.status === 'FAILED') {
          throw new Error(attempt.error || t('common.unexpectedError'))
        }
        await new Promise(resolve => window.setTimeout(resolve, SIGN_IN_POLL_INTERVAL_MS))
      }
    } catch (e) {
      toastError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setSignInBusy(false)
    }
  }

  /** Runs one store action with per-card busy state; failures surface as toasts. */
  async function run(key: string, action: () => Promise<unknown>): Promise<void> {
    if (busyKey) return
    setBusyKey(key)
    try {
      await action()
      await reload()
    } catch (e) {
      toastError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setBusyKey(null)
    }
  }

  async function install(entry: StoreCatalogEntry): Promise<void> {
    await run(entry.coordinate, async () => {
      try {
        await services.infiniaStore.install(entry.coordinate)
      } catch (e) {
        // A permission-escalation verdict is a confirmation request, not a hard failure:
        // surface the exact added permissions, then retry with the explicit ack.
        const message = e instanceof Error && e.message ? e.message : ''
        if (message.includes(ESCALATION_MARKER)
            && await getPlatform().confirm(message)) {
          await services.infiniaStore.install(entry.coordinate, true)
          return
        }
        throw e
      }
    })
  }

  async function uninstall(entry: StoreCatalogEntry): Promise<void> {
    if (!await getPlatform().confirm(t('store.confirmUninstall', { name: entry.name }), { danger: true })) return
    await run(entry.coordinate, () => services.infiniaStore.uninstall(entry.coordinate, false))
  }

  // ── Local package install (.fyp plugins / .fys skills) — the offline counterpart to
  // store installs, restored from the retired Vue StoreView. Desktop opens the OS file
  // picker (the upload runs from the native path); web falls back to a hidden file input. ──

  async function chooseLocalPackage(): Promise<void> {
    if (localBusy) return
    const platform = getPlatform()
    if (platform.kind === 'desktop') {
      const path = await platform.pickFile([{ name: 'FengYu Package', extensions: ['fyp', 'fys'] }])
      if (!path) return
      await installLocalPackage(path.split(/[\\/]/).pop() || path, undefined, path)
      return
    }
    localInputRef.current?.click()
  }

  function onLocalFilePicked(event: ChangeEvent<HTMLInputElement>): void {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (file) void installLocalPackage(file.name, file)
  }

  async function installLocalPackage(name: string, file?: File, path?: string): Promise<void> {
    const lower = name.toLowerCase()
    if (!lower.endsWith('.fyp') && !lower.endsWith('.fys')) {
      toastError(t('store.unsupportedPackage'))
      return
    }
    setLocalBusy(true)
    try {
      // Returns null when the user canceled the .fyp confirm dialog — silent, nothing installed.
      const installedAs = lower.endsWith('.fys')
        ? await installLocalSkill(name, file, path)
        : await installLocalPlugin(name, file, path)
      if (installedAs === null) return
      await refreshAfterLocalInstall()
      pushToast({ level: 'success', title: t('store.localInstalled', { name: installedAs }) })
    } catch (e) {
      toastError(e instanceof Error && e.message ? e.message : t('store.localInstallFailed'))
    } finally {
      setLocalBusy(false)
    }
  }

  async function installLocalSkill(name: string, file?: File, path?: string): Promise<string> {
    if (file) await services.skill.upload(file)
    else await services.skill.uploadNative(path!)
    return name
  }

  /** .fyp installs confirm against the inspection (name, version step, permissions) before
   * the upload — the ack flag is already true, matching the Vue-era flow. */
  async function installLocalPlugin(name: string, file?: File, path?: string): Promise<string | null> {
    const inspection = await inspectLocalPlugin(file, path)
    const displayName = inspection?.name || name
    const version = inspection?.version ? ` ${inspection.version}` : ''
    const prompt = [
      t(inspection?.installed ? 'store.confirmLocalUpdate' : 'store.confirmLocalInstall', { name: displayName, version }),
      inspection?.permissions.length
        ? t('store.localPermissions', { permissions: inspection.permissions.join(', ') })
          + (inspection.permissionsOsEnforced === false ? `\n${t('store.permissionsNotOsEnforced')}` : '')
        : '',
    ].filter(Boolean).join('\n\n')
    if (!await getPlatform().confirm(prompt)) return null
    if (file) await services.plugin.uploadPackage(file, true)
    else await services.plugin.uploadNativePackage(path!, true)
    return displayName
  }

  /** Inspection only powers the confirm dialog; a backend without the endpoint (404/405)
   * still installs — the dialog just falls back to the file name. */
  async function inspectLocalPlugin(file?: File, path?: string): Promise<PackageInspection | null> {
    try {
      return file ? await services.plugin.inspect(file) : await services.plugin.inspectNative(path!)
    } catch (e) {
      const status = (e as { response?: { status?: number } })?.response?.status
      if (status === 404 || status === 405) return null
      throw e
    }
  }

  async function refreshAfterLocalInstall(): Promise<void> {
    usePluginsStore.setState({ loaded: false })
    useSkillsStore.setState({ loaded: false })
    await Promise.all([reload(), usePluginsStore.getState().load(), useSkillsStore.getState().load()])
  }

  const filters: Array<{ id: TypeFilter; label: string }> = useMemo(() => [
    { id: 'ALL', label: t('store.typeAll') },
    { id: 'PLUGIN', label: t('store.typePlugin') },
    { id: 'SKILL', label: t('store.typeSkill') },
    { id: 'MCP', label: t('store.typeMcp') },
  ], [t])

  return (
    <div>
      <input ref={localInputRef} type="file" accept=".fyp,.fys" hidden onChange={onLocalFilePicked} />
      <div className="pg-toolbar">
        <div className="pg-toolbar__row">
          <div className="pg-search">
            <Search size={15} />
            <input
              className="cx-input"
              placeholder={t('store.searchPlaceholder')}
              aria-label={t('store.searchPlaceholder')}
              value={searchInput}
              onChange={event => setSearchInput(event.target.value)}
            />
          </div>
          {account ? (
            <span className="cx-chip cx-chip--success" title={account.email ?? account.userId}>
              <UserRound size={13} /> {account.username || account.userId}
            </span>
          ) : (
            <button
              className="cx-btn cx-btn--outline cx-btn--sm"
              disabled={signInBusy}
              onClick={() => void signIn()}
            >
              {signInBusy ? <span className="cx-spin" /> : <LogIn size={14} />}
              {t('store.signIn')}
            </button>
          )}
          <button
            className="cx-btn cx-btn--outline cx-btn--sm"
            disabled={localBusy}
            onClick={() => void chooseLocalPackage()}
          >
            {localBusy ? <span className="cx-spin" /> : <PackagePlus size={14} />}
            {t(localBusy ? 'store.installingLocal' : 'store.installLocal')}
          </button>
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
              className={cn('pg-chip-filter', typeFilter === filter.id && 'pg-chip-filter--active')}
              onClick={() => setTypeFilter(filter.id)}
            >{filter.label}</button>
          ))}
        </div>
      </div>

      {error && <PageError {...localizeError(error, t)} onRetry={() => void reload()} />}
      {loading && entries.length === 0
        ? <PageLoading label={t('store.loading')} />
        : !error && entries.length === 0
          ? (
            <PageEmpty
              icon={<Store size={30} strokeWidth={1.5} />}
              title={t('store.empty')}
              hint={t('store.subtitle')}
            />
          )
          : (
            <>
              <div className="pg-grid">
                {entries.map((entry, index) => (
                  <FadeIn key={entry.coordinate} delay={Math.min(index * 0.03, 0.24)}>
                    <SpotlightCard className="pg-card">
                      <div className="pg-card-head">
                        <span className="pg-icon">{typeIcon(entry.type)}</span>
                        <div className="pg-card-titlewrap">
                          <div className="pg-card-title">
                            <span title={entry.name}>{entry.name}</span>
                          </div>
                          <div className="pg-card-meta">
                            {typeLabel(t, entry.type)}
                            {entry.category ? ` · ${entry.category}` : ''}
                            {` · ${entry.namespace}/${entry.slug}`}
                          </div>
                        </div>
                        {entry.installed && (
                          <span className="cx-chip cx-chip--success">{t('store.sources.installed')}</span>
                        )}
                      </div>
                      <p className="pg-card-desc">{entry.summary}</p>
                      <div className="pg-card-actions">
                        <span className="pg-version">
                          {entry.installed && updateAvailable(entry)
                            ? `${entry.installedVersion ?? '—'} → ${entry.latestVersion ?? '—'}`
                            : `v${(entry.installed ? entry.installedVersion : entry.latestVersion) ?? '—'}`}
                        </span>
                        {entry.installed && (
                          <button
                            className="cx-btn cx-btn--outline cx-btn--sm"
                            disabled={busyKey === entry.coordinate}
                            onClick={() => void uninstall(entry)}
                          >{t('store.sources.uninstall')}</button>
                        )}
                        {!entry.installed
                          ? (
                            <button
                              className="cx-btn cx-btn--primary cx-btn--sm"
                              disabled={busyKey === entry.coordinate}
                              onClick={() => void install(entry)}
                            >
                              {busyKey === entry.coordinate && <span className="cx-spin" />}
                              {t('store.sources.install')}
                            </button>
                          )
                          : updateAvailable(entry)
                            ? (
                              <button
                                className="cx-btn cx-btn--primary cx-btn--sm"
                                disabled={busyKey === entry.coordinate}
                                onClick={() => void install(entry)}
                              >
                                {busyKey === entry.coordinate && <span className="cx-spin" />}
                                {t('store.sources.update')}
                              </button>
                            )
                            : null}
                      </div>
                    </SpotlightCard>
                  </FadeIn>
                ))}
              </div>
              {nextCursor && !loading && (
                <div style={{ display: 'flex', justifyContent: 'center', marginTop: 18 }}>
                  <button
                    className="cx-btn cx-btn--outline"
                    disabled={loadingMore}
                    onClick={() => void loadMore()}
                  >
                    {loadingMore ? <span className="cx-spin" /> : <ChevronDown size={15} />}
                    {t('store.loadMore')}
                  </button>
                </div>
              )}
            </>
          )}
    </div>
  )
}

function updateAvailable(entry: StoreCatalogEntry): boolean {
  return Boolean(entry.installed && entry.latestVersion
    && entry.latestVersion !== entry.installedVersion)
}

function typeIcon(type: string) {
  switch (type) {
    case 'SKILL': return <GraduationCap size={19} />
    case 'MCP': return <Network size={19} />
    default: return <Puzzle size={19} />
  }
}

function typeLabel(t: (key: string) => string, type: string): string {
  switch (type) {
    case 'SKILL': return t('store.typeSkill')
    case 'MCP': return t('store.typeMcp')
    case 'PLUGIN': return t('store.typePlugin')
    default: return type
  }
}
