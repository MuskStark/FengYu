import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { LazyLog } from '@melloware/react-logviewer'
import { Copy, FolderOpen, Maximize2, Minimize2, RefreshCw } from 'lucide-react'
import { services } from '@/services'
import type { LogFileSummary, LogTail, LogsOverview } from '@/services/types'
import { getPlatform } from '@/platform'
import { matchesLevelFilter, matchesPlugin, toLogLines, withLevelAnsi, type LevelFilter } from '@/lib/logView'
import { useToastStore } from '@/stores/toasts'
import '@/styles/settings.css'

/** Tail read pulled at the viewer's hard cap — the panel is a tail, not a downloader. */
const TAIL_MAX_BYTES = 256 * 1024
const FOLLOW_INTERVAL_MS = 4_000

/** The unified log surface's fixed categories; legacy 4.0 files join an archive group. */
const CATEGORIES = [
  { id: 'backend', file: 'fengyu.log', label: 'settings.logs.catBackend' },
  { id: 'plugin', file: 'plugin.log', label: 'settings.logs.catPlugin' },
  { id: 'desktop', file: 'desktop.log', label: 'settings.logs.catDesktop' },
  { id: 'update', file: 'update.log', label: 'settings.logs.catUpdate' },
  { id: 'selfUpdate', file: 'self-update.log', label: 'settings.logs.catSelfUpdate' },
] as const

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`
  return `${bytes} B`
}

function formatTime(iso: string): string {
  const parsed = Date.parse(iso)
  return Number.isFinite(parsed)
    ? new Intl.DateTimeFormat(undefined, { dateStyle: 'short', timeStyle: 'medium' }).format(parsed)
    : ''
}

/**
 * The log panel — a source-picker bar over the open-source LazyLog terminal. The first
 * dropdown picks the log CATEGORY (every plugin's output is merged into the single
 * plugin.log); picking "plugins" reveals a second dropdown narrowing to one plugin by
 * its logger column. Our layer only shapes data: level + plugin filtering happen before
 * the text is handed to the viewer, and level colors ride ANSI codes in the data itself.
 */
export default function LogsSection() {
  const { t } = useTranslation()
  const pushToast = useToastStore(state => state.push)
  const [overview, setOverview] = useState<LogsOverview | null>(null)
  const [categoryId, setCategoryId] = useState<string>('backend')
  const [pluginId, setPluginId] = useState('')
  const [tail, setTail] = useState<LogTail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loadingTail, setLoadingTail] = useState(false)
  const [levelFilter, setLevelFilter] = useState<LevelFilter>('all')
  const [follow, setFollow] = useState(false)
  const [maximized, setMaximized] = useState(false)
  /** The app content frame (main region) the fullscreen overlay docks to, in viewport px. */
  const [maxFrame, setMaxFrame] = useState<{ top: number; left: number; width: number; height: number } | null>(null)
  const sectionRef = useRef<HTMLElement>(null)
  const canOpenFolder = getPlatform().capabilities.logFolder

  const filesByName = useMemo(() => {
    const map = new Map<string, LogFileSummary>()
    for (const file of overview?.files ?? []) map.set(file.name, file)
    return map
  }, [overview])

  // Every category always renders — a missing file disables the option instead of
  // hiding it, so the dropdown never silently loses types on this machine.
  const categories = useMemo(() =>
    CATEGORIES.map(category => ({ ...category, present: filesByName.has(category.file) })),
  [filesByName])
  // Legacy 4.0 per-plugin files and anything unknown: still viewable, grouped as archives.
  const archiveFiles = useMemo(() => {
    const known = new Set<string>(CATEGORIES.map(category => category.file))
    return (overview?.files ?? []).filter(file => !known.has(file.name))
  }, [overview])

  const selectedFile = useMemo(() => {
    const category = CATEGORIES.find(item => item.id === categoryId)
    return category ? category.file : categoryId
  }, [categoryId])
  const isPluginCategory = selectedFile === 'plugin.log'

  // A category can vanish when its file rotates away or the overview reloads — fall
  // back to the first present category instead of tailing a ghost file.
  useEffect(() => {
    const present = categories.filter(item => item.present)
    if (present.length > 0 && !present.some(item => item.id === categoryId)
        && !archiveFiles.some(file => file.name === categoryId)) {
      setCategoryId(present[0].id)
      setPluginId('')
    }
  }, [categories, archiveFiles, categoryId])

  const refreshList = useCallback(async () => {
    try {
      const fresh = await services.logs.list()
      setOverview(fresh)
      setError(null)
    } catch (e) {
      setOverview({ files: [], plugins: [] })
      setError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    }
  }, [t])

  const refreshTail = useCallback(async () => {
    if (!selectedFile) return
    try {
      const content = await services.logs.tail(selectedFile, TAIL_MAX_BYTES)
      setTail(content)
      setError(null)
    } catch (e) {
      setTail(null)
      setError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    }
  }, [selectedFile, t])

  useEffect(() => {
    void refreshList()
  }, [refreshList])

  // The fullscreen overlay docks to the app's main content frame (below the titlebar)
  // and re-measures on window resize, so it reads as part of the app frame — never as
  // a detached sheet over the raw window.
  const measureFrame = useCallback(() => {
    const frame = sectionRef.current?.closest('main') ?? sectionRef.current?.parentElement
    if (!frame) return
    const rect = frame.getBoundingClientRect()
    setMaxFrame({ top: rect.top, left: rect.left, width: rect.width, height: rect.height })
  }, [])

  useEffect(() => {
    if (!maximized) return
    measureFrame()
    window.addEventListener('resize', measureFrame)
    const onKeydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMaximized(false)
    }
    window.addEventListener('keydown', onKeydown)
    // Lock the page's REAL scroll container (the shell scrolls an inner pane, not body):
    // its scrollbar would otherwise peek beside the fullscreen overlay.
    let scroller: HTMLElement | null = sectionRef.current?.parentElement ?? null
    while (scroller && scroller !== document.body) {
      const overflowY = getComputedStyle(scroller).overflowY
      if (overflowY === 'auto' || overflowY === 'scroll') break
      scroller = scroller.parentElement
    }
    scroller?.classList.add('fengyu-logs-scroll-lock')
    return () => {
      window.removeEventListener('resize', measureFrame)
      window.removeEventListener('keydown', onKeydown)
      scroller?.classList.remove('fengyu-logs-scroll-lock')
    }
  }, [maximized, measureFrame])

  // Tail fetch on source change (category or plugin reset both land here).
  useEffect(() => {
    let cancelled = false
    setLoadingTail(true)
    services.logs.tail(selectedFile, TAIL_MAX_BYTES)
      .then(content => {
        if (!cancelled) {
          setTail(content)
          setError(null)
        }
      })
      .catch(e => {
        if (!cancelled) {
          setTail(null)
          setError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
        }
      })
      .finally(() => {
        if (!cancelled) setLoadingTail(false)
      })
    return () => {
      cancelled = true
    }
  }, [selectedFile, t])

  // Follow mode: re-pull the tail on a short interval; the viewer's `follow` prop keeps
  // the view pinned to the newest row while it stays on.
  useEffect(() => {
    if (!follow) return
    const timer = window.setInterval(() => void refreshTail(), FOLLOW_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [follow, refreshTail])

  const lines = useMemo(() => (tail ? toLogLines(tail.content) : []), [tail])
  const visibleLines = useMemo(() => lines.filter(line =>
    matchesLevelFilter(line, levelFilter)
    && (!isPluginCategory || matchesPlugin(line, pluginId))),
  [lines, levelFilter, isPluginCategory, pluginId])
  // The viewer has no level/plugin concept — colors ride ANSI codes in the data itself,
  // and its own toolbar search covers text filtering with match highlighting.
  const viewerText = useMemo(() => visibleLines.map(withLevelAnsi).join('\n'), [visibleLines])

  const copyView = async () => {
    const text = viewerText.replace(/\u001B\[\d+m/g, '')
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      try {
        await getPlatform().copyText(text)
      } catch {
        return
      }
    }
    pushToast({ level: 'info', title: t('settings.logs.copied') })
  }

  const filters: Array<{ id: LevelFilter; label: string }> = [
    { id: 'all', label: t('settings.logs.filterAll') },
    { id: 'errors', label: t('settings.logs.filterErrors') },
    { id: 'warnings', label: t('settings.logs.filterWarnings') },
  ]
  const selectedMeta = filesByName.get(selectedFile)

  return (
    <section ref={sectionRef}>
      <h2 className="set-h">{t('settings.logs.title')}</h2>
      <div
        className={maximized ? 'cx-card set-logs-card--max' : 'cx-card'}
        style={maximized && maxFrame
          ? { top: maxFrame.top, left: maxFrame.left, width: maxFrame.width, height: maxFrame.height }
          : undefined}
      >
        <div className="set-logs-bar">
          <div className="set-logs-pickers">
            <label className="set-logs-picker">
              <span className="cx-muted">{t('settings.logs.sourceLabel')}</span>
              <select
                className="cx-select"
                value={categoryId}
                onChange={event => {
                  setCategoryId(event.target.value)
                  setPluginId('')
                }}
              >
                {categories.map(category => (
                  <option
                    key={category.id}
                    value={category.id}
                    disabled={!category.present}
                  >{t(category.label)}{category.present ? '' : t('settings.logs.categoryEmpty')}</option>
                ))}
                {archiveFiles.length > 0 && (
                  <optgroup label={t('settings.logs.archiveGroup')}>
                    {archiveFiles.map(file => (
                      <option key={file.name} value={file.name}>{file.name}</option>
                    ))}
                  </optgroup>
                )}
              </select>
            </label>
            {isPluginCategory && (
              <label className="set-logs-picker">
                <span className="cx-muted">{t('settings.logs.pluginLabel')}</span>
                <select
                  className="cx-select"
                  value={pluginId}
                  onChange={event => setPluginId(event.target.value)}
                >
                  <option value="">{t('settings.logs.allPlugins')}</option>
                  {(overview?.plugins ?? []).map(id => (
                    <option key={id} value={id}>{id}</option>
                  ))}
                </select>
              </label>
            )}
            <div className="cx-segment set-logs-filter">
              {filters.map(filter => (
                <button
                  key={filter.id}
                  className={levelFilter === filter.id ? 'active' : ''}
                  onClick={() => setLevelFilter(filter.id)}
                >{filter.label}</button>
              ))}
            </div>
          </div>
          <span className="set-logs-actions">
            <button
              className={follow ? 'set-logs-follow active' : 'set-logs-follow'}
              title={t('settings.logs.followHint')}
              onClick={() => setFollow(current => !current)}
            >{t('settings.logs.follow')}</button>
            {canOpenFolder && (
              <button className="cx-btn cx-btn--text cx-btn--sm" onClick={() => void getPlatform().openLogsFolder()}>
                <FolderOpen size={14} /> {t('settings.logs.openFolder')}
              </button>
            )}
            <button className="cx-btn cx-btn--text cx-btn--sm" onClick={copyView}>
              <Copy size={14} /> {t('settings.logs.copy')}
            </button>
            <button className="cx-btn cx-btn--text cx-btn--sm" onClick={() => { void refreshList(); void refreshTail(); }}>
              <RefreshCw size={14} /> {t('common.refresh')}
            </button>
            <button
              className="cx-btn cx-btn--text cx-btn--sm"
              title={maximized ? t('settings.logs.restore') : t('settings.logs.maximize')}
              onClick={() => setMaximized(current => !current)}
            >
              {maximized ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
            </button>
          </span>
        </div>
        {error && (
          <div className="cx-alert cx-alert--error" style={{ marginTop: 8 }}>{error}</div>
        )}
        {overview == null ? (
          <p className="cx-muted">{t('common.loading')}</p>
        ) : overview.files.length === 0 ? (
          <p className="cx-muted">{t('settings.logs.empty')}</p>
        ) : (
          <div className="set-logs-stage">
            {loadingTail && !tail ? (
              <p className="cx-muted">{t('common.loading')}</p>
            ) : tail && lines.length === 0 ? (
              <p className="cx-muted">{t('settings.logs.emptyFile')}</p>
            ) : tail ? (
              <div className="set-logs-lazylog">
                <LazyLog
                  text={viewerText}
                  enableSearch
                  enableSearchNavigation
                  caseInsensitive
                  selectableLines
                  follow={follow}
                  wrapLines={false}
                  rowHeight={20}
                  lineClassName="set-logs-line"
                />
              </div>
            ) : null}
            {tail && (
              <div className="cx-muted set-logs-meta">
                {tail.name} · {formatSize(tail.size)}
                {selectedMeta ? ` · ${formatTime(selectedMeta.lastModified)}` : ''}
                {` · ${t('settings.logs.lineCount', { shown: visibleLines.length, total: lines.length })}`}
                {tail.size >= TAIL_MAX_BYTES ? ` · ${t('settings.logs.tailTruncated')}` : ''}
              </div>
            )}
          </div>
        )}
      </div>
    </section>
  )
}
