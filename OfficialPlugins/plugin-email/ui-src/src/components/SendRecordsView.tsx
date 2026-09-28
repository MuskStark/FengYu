import { Fragment, useEffect, useRef, useState } from 'react'
import {
  Chip,
  ErrorState,
  GhostButton,
  Select,
  StatusChip,
  type StatusTone,
  useFengYuI18n,
} from '@infinia/plugin-ui'
import { actionable, checked, rpc } from '../sdk'

interface SendTask { confirmationId: string; accountId: number; mode: string; status: string; expiresAt?: string; updatedAt?: string }
interface SentMessage { id: number; confirmationId?: string; accountEmail: string; subject?: string; status: string; errorMessage?: string; sentAt?: string; recipientsJson?: string }

const TASK_STATUSES = ['PENDING', 'SENDING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'REJECTED', 'EXPIRED']
const TASK_STATUS_KEYS: Record<string, string> = {
  PENDING: 'records.statusPending',
  SENDING: 'records.statusSending',
  COMPLETED: 'records.statusCompleted',
  PARTIAL_FAILED: 'records.partial',
  FAILED: 'records.statusFailed',
  REJECTED: 'records.statusRejected',
  EXPIRED: 'records.statusExpired',
}
const RECIPIENT_PREVIEW = 3
const LIMIT = 25

export default function SendRecordsView() {
  const { t } = useFengYuI18n()
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<string>('')
  const [offset, setOffset] = useState(0)
  const [tasks, setTasks] = useState<SendTask[]>([])
  const [messages, setMessages] = useState<SentMessage[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [expanded, setExpanded] = useState<string>()
  const [taskMessages, setTaskMessages] = useState<Record<string, SentMessage[]>>({})
  const [taskErrors, setTaskErrors] = useState<Record<string, string>>({})
  const [detailBusy, setDetailBusy] = useState<string>()
  const recipientCache = useRef(new Map<number, string[]>())

  async function load(offsetOverride?: number): Promise<void> {
    setBusy(true); setError('')
    try {
      const result = await checked(rpc.email_send_records_query({
        query, taskStatus: status || undefined, offset: offsetOverride ?? offset, limit: LIMIT,
      }))
      setTasks(result.tasks ?? []); setMessages(result.messages ?? [])
    } catch (value) { setError(actionable(value, t('records.loadAction'))) }
    finally { setBusy(false) }
  }

  // Pagination helpers pass the target offset explicitly: setState is async, so
  // loading from the closure would race the request against the re-render.
  function search(): void { setOffset(0); void load(0) }
  function previous(): void { const next = Math.max(0, offset - LIMIT); setOffset(next); void load(next) }
  function next(): void { const target = offset + LIMIT; setOffset(target); void load(target) }
  useEffect(() => { void load() }, [])

  async function toggle(task: SendTask): Promise<void> {
    if (expanded === task.confirmationId) { setExpanded(undefined); return }
    setExpanded(task.confirmationId)
    if (taskMessages[task.confirmationId] || taskErrors[task.confirmationId]) return
    setDetailBusy(task.confirmationId)
    try {
      const result = await checked(rpc.email_send_records_query({ confirmationId: task.confirmationId, limit: 100 }))
      setTaskMessages(current => ({ ...current, [task.confirmationId]: result.messages ?? [] }))
    } catch (value) {
      setTaskErrors(current => ({ ...current, [task.confirmationId]: actionable(value, t('records.detailAction')) }))
    } finally { setDetailBusy(undefined) }
  }

  function recipientsOf(message: SentMessage): string[] {
    const cached = recipientCache.current.get(message.id)
    if (cached) return cached
    let value: string[] = []
    try {
      const parsed = JSON.parse(message.recipientsJson || '{}') as { to?: string[]; cc?: string[]; bcc?: string[] }
      value = [...(parsed.to ?? []), ...(parsed.cc ?? []), ...(parsed.bcc ?? [])]
    } catch { value = [] }
    recipientCache.current.set(message.id, value)
    return value
  }

  function taskStatusLabel(value: string): string { return TASK_STATUS_KEYS[value] ? t(TASK_STATUS_KEYS[value]) : value }
  function taskStatusTone(value: string): StatusTone {
    if (value === 'COMPLETED') return 'success'
    if (value === 'SENDING' || value === 'PARTIAL_FAILED') return 'warning'
    if (value === 'FAILED') return 'danger'
    return 'idle'
  }
  function messageStatusLabel(value: string): string {
    if (value === 'SUCCESS') return t('records.statusSuccess')
    if (value === 'FAILED') return t('records.statusFailed')
    return value
  }
  function messageStatusTone(value: string): StatusTone {
    if (value === 'SUCCESS') return 'success'
    if (value === 'FAILED') return 'danger'
    return 'idle'
  }
  function modeLabel(value: string): string {
    const key = `conf.mode_${value}`
    const label = t(key)
    return label === key ? value : label
  }
  function formatTime(value?: string): string {
    return value ? value.replace('T', ' ').slice(0, 16) : '—'
  }

  return (
    <div className="records-view">
      <div className="records-head">
        <h2 className="records-title">{t('records.title')}</h2>
        <p className="records-sub">{t('records.subtitle', tasks.length, messages.length)}</p>
      </div>
      {error ? <ErrorState title={t('errors.unknown')} message={error} className="mb-4" /> : null}

      <article className="fy-card">
        <div className="records-card-head">
          <span className="records-card-title">{t('records.tasks')}</span>
          <div className="records-filters">
            <input className="email-input" data-testid="record-search" placeholder={t('records.search')}
              value={query} onChange={event => setQuery(event.target.value)}
              onKeyDown={event => { if (event.key === 'Enter') search() }} />
            <Select className="email-select" data-testid="record-status" value={status}
              onChange={setStatus}
              options={[{ value: '', label: t('records.allStatus') }, ...TASK_STATUSES.map(value => ({ value, label: taskStatusLabel(value) }))]} />
            <GhostButton data-testid="record-search-submit" disabled={busy} onClick={search}>
              {busy ? t('common.loading') : t('common.search')}
            </GhostButton>
          </div>
        </div>
        <div className="table-scroll">
          <table className="fy-table">
            <thead><tr>
              <th className="records-col-expand" aria-hidden="true"></th>
              <th>{t('records.confirmationId')}</th><th>{t('records.mode')}</th>
              <th>{t('records.status')}</th><th>{t('records.updated')}</th>
            </tr></thead>
            <tbody>
              {tasks.map(task => (
                <Fragment key={task.confirmationId}>
                  <tr className="records-task-row" onClick={() => void toggle(task)}>
                    <td className="records-col-expand">
                      <button type="button" className="records-toggle" data-testid="task-toggle"
                        aria-expanded={expanded === task.confirmationId} aria-label={t('records.toggleDetail')}
                        onClick={event => { event.stopPropagation(); void toggle(task) }}>
                        <svg className={'records-chevron' + (expanded === task.confirmationId ? ' records-chevron--open' : '')} viewBox="0 0 24 24"><path d="M9 18l6-6-6-6" /></svg>
                      </button>
                    </td>
                    <td className="records-mono">{task.confirmationId}</td>
                    <td><Chip className="mode-chip">{modeLabel(task.mode)}</Chip></td>
                    <td><StatusChip tone={taskStatusTone(task.status)}>{taskStatusLabel(task.status)}</StatusChip></td>
                    <td className="records-muted">{formatTime(task.updatedAt)}</td>
                  </tr>
                  {expanded === task.confirmationId ? (
                    <tr className="records-detail-row">
                      <td colSpan={5} className="records-detail-cell">
                        {detailBusy === task.confirmationId ? <p className="records-detail-status">{t('records.loadingDetail')}</p>
                          : taskErrors[task.confirmationId] ? <p className="records-detail-status records-detail-error">{taskErrors[task.confirmationId]}</p>
                            : (taskMessages[task.confirmationId] ?? []).length ? (
                              <div>
                                {(taskMessages[task.confirmationId] ?? []).map(message => (
                                  <div key={message.id} className="records-message">
                                    <div className="records-message-head">
                                      <span className="records-message-subject">{message.subject || t('compose.noSubject')}</span>
                                      <StatusChip tone={messageStatusTone(message.status)}>{messageStatusLabel(message.status)}</StatusChip>
                                      <span className="records-message-meta">
                                        {message.accountEmail}{message.sentAt ? ` · ${formatTime(message.sentAt)}` : ''}
                                      </span>
                                    </div>
                                    <div className="records-recipients">
                                      <span className="records-recipients-label">{t('records.recipients')}</span>
                                      {recipientsOf(message).slice(0, RECIPIENT_PREVIEW).map(recipient => (
                                        <span key={recipient} className="records-recipient">{recipient}</span>
                                      ))}
                                      {recipientsOf(message).length > RECIPIENT_PREVIEW ? (
                                        <span className="records-recipient records-recipient--more">
                                          {t('contacts.tagsMore', recipientsOf(message).length - RECIPIENT_PREVIEW)}
                                        </span>
                                      ) : null}
                                      {!recipientsOf(message).length ? <span className="records-muted">{t('common.none')}</span> : null}
                                    </div>
                                    {message.errorMessage ? <p className="records-message-error">{message.errorMessage}</p> : null}
                                  </div>
                                ))}
                              </div>
                            ) : <p className="records-detail-status">{t('records.noMessages')}</p>}
                      </td>
                    </tr>
                  ) : null}
                </Fragment>
              ))}
              {!tasks.length ? <tr><td colSpan={5} className="records-empty">{t('records.emptyTasks')}</td></tr> : null}
            </tbody>
          </table>
        </div>
      </article>

      <article className="fy-card records-card-gap">
        <div className="records-card-head"><span className="records-card-title">{t('records.messages')}</span></div>
        <div className="table-scroll">
          <table className="fy-table">
            <thead><tr>
              <th>{t('compose.subject')}</th><th>{t('records.account')}</th>
              <th>{t('records.status')}</th><th>{t('records.error')}</th><th>{t('records.sentAt')}</th>
            </tr></thead>
            <tbody>
              {messages.map(message => (
                <tr key={message.id}>
                  <td>{message.subject || t('compose.noSubject')}</td>
                  <td className="records-muted">{message.accountEmail}</td>
                  <td><StatusChip tone={messageStatusTone(message.status)}>{messageStatusLabel(message.status)}</StatusChip></td>
                  <td>{message.errorMessage ? <span className="records-message-error">{message.errorMessage}</span> : <span className="records-muted">{t('common.none')}</span>}</td>
                  <td className="records-muted">{formatTime(message.sentAt)}</td>
                </tr>
              ))}
              {!messages.length ? <tr><td colSpan={5} className="records-empty">{t('records.emptyMessages')}</td></tr> : null}
            </tbody>
          </table>
        </div>
      </article>

      <div className="pager">
        <GhostButton disabled={offset === 0} onClick={previous}>{t('common.previous')}</GhostButton>
        <span className="email-hint">{t('common.page', Math.floor(offset / LIMIT) + 1)}</span>
        <GhostButton onClick={next}>{t('common.next')}</GhostButton>
      </div>
    </div>
  )
}
