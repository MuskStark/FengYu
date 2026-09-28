import { useEffect, useMemo, useRef, useState } from 'react'
import type { FileRef } from '@infinia/plugin-sdk'
import {
  CardSpotlight,
  CardStack,
  ErrorState,
  GhostButton,
  GoldButton,
  PlaceholdersAndVanishInput,
  Progress,
  Select,
  useFengYuI18n,
} from '@infinia/plugin-ui'
import { IconArchive, IconPlayerPause } from '@tabler/icons-react'
import { useAccountsStore } from '../stores/accounts'
import { useArchiveStore } from '../stores/archive'
import { actionable, checked, files, rpc } from '../sdk'

export default function CollectTab() {
  const { t } = useFengYuI18n()
  const accounts = useAccountsStore(state => state.accounts)
  const selectedId = useAccountsStore(state => state.selectedId)
  const offset = useArchiveStore(state => state.offset)
  const limit = useArchiveStore(state => state.limit)
  const messages = useArchiveStore(state => state.messages)
  const progress = useArchiveStore(state => state.progress)
  const update = useArchiveStore(state => state.update)
  const nextPage = useArchiveStore(state => state.nextPage)
  const previousPage = useArchiveStore(state => state.previousPage)
  const updateProgress = useArchiveStore(state => state.updateProgress)

  const [folder, setFolder] = useState('INBOX')
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const [output, setOutput] = useState<FileRef | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [summary, setSummary] = useState('')
  const [detail, setDetail] = useState<Record<string, unknown>>()
  const [folders, setFolders] = useState<string[]>(['INBOX'])
  const [foldersLoading, setFoldersLoading] = useState(false)
  const [jobId, setJobId] = useState('')
  const [search, setSearch] = useState('')
  const [activeId, setActiveId] = useState<unknown>()
  const jobIdRef = useRef('')
  const cursorRef = useRef(0)
  const pollTimer = useRef<number | undefined>(undefined)

  async function choose() {
    try { setOutput(await files.outputDirectory()) } catch (value) { setError(actionable(value, t('archive.selectOutput'))) }
  }

  // Date inputs yield yyyy-mm-dd. Expand to full-day bounds so filtering by a date includes every
  // message that arrived that day: start = 00:00:00.000, end = 23:59:59.999 (local, then toISOString).
  const dayStart = (value: string) => value ? new Date(value + 'T00:00:00').toISOString() : undefined
  const dayEnd = (value: string) => value ? new Date(value + 'T23:59:59.999').toISOString() : undefined

  async function loadFolders() {
    const accountId = useAccountsStore.getState().selectedId
    if (!accountId) { setFolders(['INBOX']); return }
    setFoldersLoading(true)
    try {
      const result = await checked(rpc.email_imap_folders({ accountId: accountId! }))
      const next = result.folders?.length ? result.folders : ['INBOX']
      setFolders(next)
      setFolder(current => (next.includes(current) ? current : 'INBOX'))
    } catch (value) {
      setFolders(['INBOX'])
      setError(actionable(value, t('archive.loadFolders')))
    } finally { setFoldersLoading(false) }
  }

  // Parse the latest progress log line ("42/1974 new=10 skipped=2 failed=0") emitted by the backend's
  // ProgressSink into the live counters the progress grid displays.
  function applyProgressLine(line: string) {
    const match = line.match(/(\d+)\/(\d+)\s+new=(\d+)\s+skipped=(\d+)\s+failed=(\d+)/)
    if (!match) return
    updateProgress({
      processed: Number(match[1]), newArchived: Number(match[3]),
      duplicates: Number(match[4]), failed: Number(match[5]), successful: Number(match[3]),
    })
  }

  function stopPolling() {
    if (pollTimer.current) { window.clearInterval(pollTimer.current); pollTimer.current = undefined }
  }

  // Archive runs as a background job (email_archive_fetch_start → poll email_archive_fetch_status)
  // so a folder with thousands of messages does not exceed the host's per-RPC timeout. Each status
  // poll drains new progress lines and, on completion, carries the final CollectResult.
  async function collect() {
    setBusy(true); setError(''); setSummary('')
    updateProgress({ processed: 0, newArchived: 0, duplicates: 0, failed: 0, successful: 0 })
    try {
      const started = await checked(rpc.email_archive_fetch_start(
        { accountId: useAccountsStore.getState().selectedId!, folder, start: dayStart(start), end: dayEnd(end), outputDirectory: output as unknown as string }))
      jobIdRef.current = started.jobId ?? ''
      setJobId(jobIdRef.current)
      cursorRef.current = 0
      pollTimer.current = window.setInterval(async () => {
        try {
          const snap = await checked(
            rpc.email_archive_fetch_status({ jobId: jobIdRef.current, cursor: cursorRef.current }))
          for (const line of snap.logs ?? []) applyProgressLine(line)
          cursorRef.current = snap.cursor ?? 0
          if (snap.done) {
            stopPolling(); jobIdRef.current = ''; setJobId(''); setBusy(false)
            if (snap.status === 'FAILED') { setError(snap.error ?? t('archive.collectAction')) }
            else if (snap.status === 'CANCELLED') { setSummary(t('archive.cancelled')) }
            else if (snap.result) {
              setSummary(t('archive.collectSummary', snap.result.newArchived, snap.result.skippedDuplicates, snap.result.failures))
              updateProgress({ successful: snap.result.newArchived })
            }
            await loadResults()
          }
        } catch (value) { stopPolling(); jobIdRef.current = ''; setJobId(''); setBusy(false); setError(actionable(value, t('archive.collectAction'))) }
      }, 1000)
    } catch (value) { setBusy(false); setError(actionable(value, t('archive.collectAction'))) }
  }

  async function cancelCollect() {
    if (!jobIdRef.current) return
    try { await checked(rpc.email_archive_fetch_cancel({ jobId: jobIdRef.current })) }
    catch (value) { setError(actionable(value, t('archive.collectAction'))) }
  }

  async function loadResults() {
    try {
      const result = await checked(rpc.email_archive_query({ accountId: useAccountsStore.getState().selectedId, folder, offset: useArchiveStore.getState().offset, limit: useArchiveStore.getState().limit }))
      update({ messages: result.messages ?? [] })
    } catch (value) { setError(actionable(value, t('archive.loadAction'))) }
  }

  async function openDetail(id: unknown) {
    setActiveId(id)
    try { const result = await checked(rpc.email_archive_detail({ id: id as number })); setDetail(result.message) }
    catch (value) { setError(actionable(value, t('archive.detailAction'))) }
  }

  function previous() { previousPage(); void loadResults() }
  function next() { nextPage(); void loadResults() }

  // App loads accounts once on mount; if that failed, the account dropdown here would stay empty
  // with no retry. Re-fetch on mount when the list is empty so the picker is always populated.
  useEffect(() => {
    if (!useAccountsStore.getState().accounts.length) {
      useAccountsStore.getState().load().catch(value => { setError(actionable(value, t('accounts.loading'))) })
    }
  }, [])  // mount-only: `t` is a fresh bound fn per render and must not be a dep
  useEffect(() => { void loadFolders() }, [])
  useEffect(() => { void loadFolders() }, [selectedId])
  useEffect(() => { void loadResults() }, [])
  // Stop the status poll loop if the user navigates away mid-archive.
  useEffect(() => () => stopPolling(), [])

  const query = search.trim().toLowerCase()
  const visibleMessages = useMemo(() => messages.filter(message =>
    !query
    || String(message.subject ?? '').toLowerCase().includes(query)
    || String(message.fromAddress ?? '').toLowerCase().includes(query)), [messages, query])

  return (
    <section className="archive-workspace">
      <div className="archive-controls">
        {error ? <ErrorState title={t('errors.unknown')} message={error} className="mb-2" /> : null}
        {summary ? <p className="email-alert email-alert--success">{summary}</p> : null}
        <div className="form-grid">
          <label className="email-field">
            <span>{t('archive.account')}</span>
            <Select className="email-select" value={selectedId === null ? '' : String(selectedId)} data-testid="archive-account"
              onChange={value => useAccountsStore.getState().select(Number(value))}
              options={accounts.map(account => ({ value: String(account.id), label: account.email }))} />
          </label>
          <label className="email-field">
            <span>{t('archive.folder')}</span>
            <Select className="email-select" value={folder} disabled={foldersLoading}
              onChange={setFolder}
              options={folders.map(item => ({ value: item, label: item }))} />
          </label>
          <label className="email-field">
            <span>{t('archive.from')}</span>
            <input className="email-input" type="date" value={start} onChange={event => setStart(event.target.value)} />
          </label>
          <label className="email-field">
            <span>{t('archive.to')}</span>
            <input className="email-input" type="date" value={end} onChange={event => setEnd(event.target.value)} />
          </label>
        </div>
        <div className="archive-actions">
          <GhostButton onClick={() => void choose()}>{output?.name || t('archive.output')}</GhostButton>
          <GoldButton data-testid="archive-collect" disabled={!output || !selectedId || Boolean(jobId) || busy} onClick={() => void collect()}>
            {busy ? t('common.loading') : t('archive.collect')}
          </GoldButton>
          {jobId ? (
            <GhostButton data-testid="archive-cancel" onClick={() => void cancelCollect()}>
              <IconPlayerPause size={14} stroke={1.6} />
              {t('archive.cancel')}
            </GhostButton>
          ) : null}
        </div>
        {jobId ? <Progress status="indeterminate" label={t('archive.collectAction')} className="mt-2" /> : null}
      </div>

      <div className="archive-columns" data-testid="archive-results">
        <aside className="archive-list">
          <div className="archive-list-head">
            <PlaceholdersAndVanishInput
              placeholders={[t('archive.sender') + '…', t('compose.subject') + '…']}
              onChange={event => setSearch(event.target.value)}
              onSubmit={() => {}}
            />
          </div>
          <div className="archive-list-scroll">
            {visibleMessages.map(message => {
              const id = message.id
              const sender = String(message.fromAddress ?? '')
              return (
                <button key={String(id)} type="button" data-testid="archive-row"
                  className={'archive-message' + (activeId === id ? ' infinia-active-pill' : '')}
                  onClick={() => void openDetail(id)}>
                  <span className="archive-message-head">
                    <span className="infinia-hex email-hex-muted"><span className="email-hex-initial">{sender.charAt(0) || '?'}</span></span>
                    <span className="archive-message-from">{sender || t('archive.sender')}</span>
                    <span className="archive-message-time">{String(message.archivedAt ?? '').replace('T', ' ').slice(0, 16)}</span>
                  </span>
                  <span className="archive-message-subject">{String(message.subject ?? t('compose.noSubject'))}</span>
                  <span className="archive-message-snippet">{String(message.bodyPreview ?? '')}</span>
                </button>
              )
            })}
            {!visibleMessages.length ? <p className="email-hint archive-empty">{t('records.emptyMessages')}</p> : null}
          </div>
          <div className="pager">
            <GhostButton disabled={offset === 0} onClick={previous}>{t('common.previous')}</GhostButton>
            <span className="email-hint">{t('common.page', Math.floor(offset / limit) + 1)}</span>
            <GhostButton data-testid="archive-next-page" onClick={next}>{t('common.next')}</GhostButton>
          </div>
        </aside>

        <CardSpotlight radius={320} color="rgba(234,176,75,0.15)" className="archive-reading">
          {detail ? (
            <>
              <div className="archive-reading-head">
                <h2>{String(detail.subject ?? t('compose.noSubject'))}</h2>
                <div className="archive-reading-meta">
                  <span>{String(detail.fromAddress ?? '')}</span>
                  <span className="font-mono">{String(detail.receivedAt ?? detail.archivedAt ?? '').replace('T', ' ').slice(0, 16)}</span>
                </div>
              </div>
              <div className="archive-reading-body">
                <p>{String(detail.bodyPreview ?? '')}</p>
                <dl className="detail archive-reading-fields">
                  {['folder', 'hasAttachment', 'emlPath'].map(key => (
                    <div key={key} className="confirm-row">
                      <dt>{key}</dt>
                      <dd>{String(detail[key] ?? t('common.none'))}</dd>
                    </div>
                  ))}
                </dl>
              </div>
              <div className="archive-reading-actions">
                <GhostButton onClick={() => void loadResults()}>
                  <IconArchive size={14} stroke={1.6} />
                  {t('archive.results')}
                </GhostButton>
              </div>
            </>
          ) : (
            <div className="archive-reading-body archive-reading-empty">
              <p className="email-hint">{t('archive.results')}</p>
            </div>
          )}
        </CardSpotlight>

        <aside className="email-rail">
          <div className="fy-card email-rail-card">
            <div className="email-rail-head">
              <span className="email-rail-title">{t('archive.queue')}</span>
              <span className="email-rail-count">{jobId ? '…' : '—'}</span>
            </div>
            <div className="email-cardstack">
              <CardStack
                offset={9}
                scaleFactor={0.05}
                items={[
                  {
                    id: 1,
                    name: t('archive.processed'),
                    designation: String(progress.processed),
                    content: <span className="cardstack-stat">{progress.processed}</span>,
                  },
                  {
                    id: 2,
                    name: t('archive.new'),
                    designation: `${t('archive.duplicates')} ${progress.duplicates}`,
                    content: <span className="cardstack-stat">{progress.newArchived}</span>,
                  },
                  {
                    id: 3,
                    name: t('archive.failed'),
                    designation: `${t('archive.statArchived')} ${progress.successful}`,
                    content: <span className="cardstack-stat">{progress.failed}</span>,
                  },
                ]}
              />
            </div>
          </div>
          <div className="fy-card email-stat-card" data-testid="archive-progress">
            <div className="email-stat-label">{t('archive.processed')}</div>
            <div className="email-stat-value">{progress.processed}</div>
            <div className="email-stat-grid">
              <span>{t('archive.new')} {progress.newArchived}</span>
              <span>{t('archive.duplicates')} {progress.duplicates}</span>
              <span>{t('archive.failed')} {progress.failed}</span>
            </div>
          </div>
        </aside>
      </div>
    </section>
  )
}
