import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { i18n } from '@/i18n'
import { CalendarClock, Plus, RefreshCw } from 'lucide-react'
import '@/styles/features/pages.css'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import type { AgentScheduleSummary, AiPermissionMode, WorkflowDefinition } from '@/services/types'
import { FadeIn } from '@/components/pages/FadeIn'
import { PageEmpty, PageError, PageLoading } from '@/components/pages/StateViews'
import { ToggleSwitch } from '@/components/pages/ToggleSwitch'
import { scheduleLabel } from '@/components/pages/scheduleLabel'
import { cronValidationError } from '@/components/pages/cronExpression'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 15_000
/** 7 days minus one minute — the backend's first-fire horizon for interval schedules. */
const MAX_INTERVAL_MINUTES = 7 * 24 * 60 - 1

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(i18n.global.locale.value || undefined)
}

type ScheduleKind = 'INTERVAL' | 'CRON'

/**
 * Schedules (React twin of the Vue Schedules list view): the workflow schedule
 * list with its firing rule, status and next fire time, plus the create form
 * (fixed interval or a five-field cron expression). The backend exposes no
 * pause/enable endpoint (only create + delete), so the row switch doubles as
 * the stop control — flipping it off asks for the standard delete
 * confirmation, matching what "stopped" actually means here.
 */
export default function SchedulesPage() {
  const { t } = useTranslation()
  const [schedules, setSchedules] = useState<AgentScheduleSummary[]>([])
  const [workflows, setWorkflows] = useState<WorkflowDefinition[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const busyRef = useRef(false)

  // ── create form ────────────────────────────────────────────────────
  const [showForm, setShowForm] = useState(false)
  const [workflowId, setWorkflowId] = useState('')
  const [kind, setKind] = useState<ScheduleKind>('INTERVAL')
  const [minutes, setMinutes] = useState('60')
  const [cronExpr, setCronExpr] = useState('')
  const [recurring, setRecurring] = useState(true)
  const [fireImmediately, setFireImmediately] = useState(false)
  const [permissionMode, setPermissionMode] = useState<AiPermissionMode>('ask-for-approval')
  const [creating, setCreating] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    if (busyRef.current) return
    setLoading(true)
    setError(null)
    try {
      const [active, definitions] = await Promise.all([services.agent.schedules(), services.workflow.list()])
      setSchedules(active)
      setWorkflows(definitions)
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => {
    void reload()
    // Polite periodic refresh like the Vue view (skipped while a delete is in flight).
    const timer = window.setInterval(() => {
      if (!document.hidden && !busyRef.current) void reload()
    }, REFRESH_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [reload])

  async function stop(schedule: AgentScheduleSummary): Promise<void> {
    if (busyRef.current) return
    if (!await getPlatform().confirm(t('schedules.deleteConfirm'), { danger: true })) return
    busyRef.current = true
    setBusyId(schedule.scheduleId)
    try {
      await services.agent.deleteSchedule(schedule.scheduleId)
      setSchedules(current => current.filter(item => item.scheduleId !== schedule.scheduleId))
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      busyRef.current = false
      setBusyId(null)
    }
  }

  // The empty-state hint must reflect reality: only PUBLISHED workflows can be scheduled,
  // so a draft-only library still counts as "nothing schedulable yet".
  const publishedWorkflows = workflows.filter(workflow => workflow.published)
  const intervalMinutes = Number.parseInt(minutes, 10)
  const intervalValid = Number.isInteger(intervalMinutes)
    && intervalMinutes >= 1 && intervalMinutes <= MAX_INTERVAL_MINUTES
  // Live cron check mirrors the backend grammar so the hint names the field while typing;
  // the backend's own 400 (same message shape) is the authority on submit.
  const cronError = cronExpr.trim() ? cronValidationError(cronExpr, t) : null
  const formValid = workflowId !== '' && !creating && (kind === 'CRON'
    ? cronExpr.trim() !== '' && cronError === null
    : intervalValid)

  function resetForm(): void {
    setWorkflowId('')
    setKind('INTERVAL')
    setMinutes('60')
    setCronExpr('')
    setRecurring(true)
    setFireImmediately(false)
    setPermissionMode('ask-for-approval')
    setFormError(null)
  }

  async function create(): Promise<void> {
    setCreating(true)
    setFormError(null)
    try {
      await services.agent.createSchedule(kind === 'CRON'
        ? {
            workflowId,
            inputs: {},
            intervalSeconds: 3600,
            recurring: true,
            fireImmediately,
            cron: { expression: cronExpr.trim() },
            permissionMode,
          }
        : {
            workflowId,
            inputs: {},
            intervalSeconds: intervalMinutes * 60,
            recurring,
            fireImmediately,
            permissionMode,
          })
      setShowForm(false)
      resetForm()
      await reload()
    } catch (e) {
      setFormError(e instanceof Error && e.message ? e.message : t('common.unexpectedError'))
    } finally {
      setCreating(false)
    }
  }

  const workflowName = (id: string): string =>
    workflows.find(workflow => workflow.id === id)?.name ?? id

  return (
    <div className="pg-page">
      <div className="pg-inner">
        <FadeIn>
          <header className="pg-header">
            <div className="pg-header__text">
              <h1 className="pg-title">{t('schedules.title')}</h1>
              <p className="cx-muted pg-subtitle">{t('schedules.hint')}</p>
            </div>
            <div className="pg-header__actions">
              <button
                className="cx-iconbtn"
                title={t('schedules.refresh')}
                aria-label={t('schedules.refresh')}
                disabled={loading}
                onClick={() => void reload()}
              ><RefreshCw size={17} className={cn(loading && 'pg-spin')} /></button>
              <button
                className="cx-btn cx-btn--primary"
                disabled={publishedWorkflows.length === 0}
                title={publishedWorkflows.length === 0 ? t('schedules.emptyWorkflows') : undefined}
                onClick={() => { setShowForm(current => !current); setFormError(null) }}
              ><Plus size={15} />{t('schedules.newSchedule')}</button>
            </div>
          </header>
          <p className="cx-muted pg-account-hint mt-0">{t('schedules.runtime')}</p>
        </FadeIn>
        <div className="h-3.5" />

        {showForm && (
          <FadeIn className="cx-card">
            <div className="flex flex-col gap-3.5">
              <div className="grid gap-3.5 md:grid-cols-2">
                <label className="cx-field">
                  <span className="cx-label">{t('schedules.workflow')}</span>
                  <select
                    className="cx-select"
                    value={workflowId}
                    onChange={event => setWorkflowId(event.target.value)}
                  >
                    <option value="">{t('schedules.choose')}</option>
                    {publishedWorkflows.map(workflow => (
                      <option key={workflow.id} value={workflow.id}>{workflow.name}</option>
                    ))}
                  </select>
                </label>
                <label className="cx-field">
                  <span className="cx-label">{t('schedules.type')}</span>
                  <select
                    className="cx-select"
                    value={kind}
                    onChange={event => setKind(event.target.value as ScheduleKind)}
                  >
                    <option value="INTERVAL">{t('schedules.modeINTERVAL')}</option>
                    <option value="CRON">{t('schedules.modeCRON')}</option>
                  </select>
                </label>
              </div>
              {kind === 'CRON'
                ? (
                  <label className="cx-field">
                    <span className="cx-label">{t('schedules.cronExpression')}</span>
                    <input
                      className="cx-input"
                      placeholder="*/15 9-18 * * 1-5"
                      spellCheck={false}
                      value={cronExpr}
                      onChange={event => setCronExpr(event.target.value)}
                    />
                    <span className="cx-muted text-xs">{t('schedules.cronHint')}</span>
                  </label>
                )
                : (
                  <label className="cx-field">
                    <span className="cx-label">{t('schedules.interval')}</span>
                    <input
                      className="cx-input cx-input--narrow"
                      type="number"
                      min={1}
                      max={MAX_INTERVAL_MINUTES}
                      value={minutes}
                      onChange={event => setMinutes(event.target.value)}
                    />
                    <span className="cx-muted text-xs">
                      {t('schedules.minutes')} · {t('schedules.legacyExpiry')}
                    </span>
                  </label>
                )}
              <label className="cx-field">
                <span className="cx-label">{t('schedules.permissionMode')}</span>
                <select
                  className="cx-select"
                  value={permissionMode}
                  onChange={event => setPermissionMode(event.target.value as AiPermissionMode)}
                >
                  <option value="ask-for-approval">{t('aichat.permissionAsk')}</option>
                  <option value="approve-for-me">{t('aichat.permissionAuto')}</option>
                  <option value="full-access">{t('aichat.permissionFullAccess')}</option>
                </select>
              </label>
              <div className="flex flex-wrap items-center gap-5">
                {kind === 'INTERVAL' && (
                  <ToggleSwitch
                    checked={recurring}
                    label={t('schedules.recurring')}
                    onChange={setRecurring}
                  />
                )}
                <ToggleSwitch
                  checked={fireImmediately}
                  label={t('schedules.immediate')}
                  onChange={setFireImmediately}
                />
              </div>
              {kind === 'CRON' && cronError && (
                <div className="cx-alert cx-alert--error">
                  <span className="cx-alert__body">{cronError}</span>
                </div>
              )}
              {kind === 'INTERVAL' && minutes !== '' && !intervalValid && (
                <div className="cx-alert cx-alert--error">
                  <span className="cx-alert__body">{t('schedules.invalidInterval')}</span>
                </div>
              )}
              {formError && (
                <div className="cx-alert cx-alert--error">
                  <span className="cx-alert__body">{formError}</span>
                </div>
              )}
              <div className="flex justify-end gap-2.5">
                <button
                  className="cx-btn cx-btn--outline"
                  disabled={creating}
                  onClick={() => { setShowForm(false); resetForm() }}
                >{t('common.cancel')}</button>
                <button
                  className="cx-btn cx-btn--primary"
                  disabled={!formValid}
                  onClick={() => void create()}
                >{t('schedules.create')}</button>
              </div>
            </div>
          </FadeIn>
        )}
        {showForm && <div className="h-3.5" />}

        {error && <PageError message={error} onRetry={() => void reload()} />}
        {loading && schedules.length === 0
          ? <PageLoading label={t('schedules.loading')} />
          : !error && schedules.length === 0
            ? (
              <PageEmpty
                icon={<CalendarClock size={30} strokeWidth={1.5} />}
                title={t('schedules.empty')}
                hint={publishedWorkflows.length > 0 ? t('schedules.emptyWithWorkflows') : t('schedules.emptyWorkflows')}
              />
            )
            : (
              <FadeIn className="pg-sched-list" role="list">
                {schedules.map(schedule => (
                  <article key={schedule.scheduleId} className="pg-sched-row" role="listitem">
                    <div className="pg-sched-row__body">
                      <h2 className="pg-sched-row__name">{workflowName(schedule.workflowId)}</h2>
                      <p className="pg-sched-row__line">{scheduleLabel(schedule, t)}</p>
                      <p className="pg-sched-row__line">
                        {t('agent.scheduleNext', { time: formatTime(schedule.nextFireAt) })}
                        {' · '}
                        {t('agent.scheduleFires', { n: schedule.fires })}
                      </p>
                      <p className="pg-sched-row__line">
                        {schedule.expiresAt
                          ? t('schedules.expires', { time: formatTime(schedule.expiresAt) })
                          : t('schedules.noExpiry')}
                      </p>
                      {schedule.missedFires > 0 && (
                        <p className="pg-sched-row__line">
                          {t('agent.scheduleMissed', { n: schedule.missedFires })}
                        </p>
                      )}
                      {schedule.lastError && (
                        <p className="pg-sched-row__line pg-sched-row__line--error">{schedule.lastError}</p>
                      )}
                    </div>
                    <div className="pg-sched-row__side">
                      {schedule.lastError
                        ? <span className="cx-chip cx-chip--error">{t('common.off')}</span>
                        : <span className="cx-chip cx-chip--success">{t('common.on')}</span>}
                      <ToggleSwitch
                        checked
                        disabled={busyId === schedule.scheduleId}
                        title={t('agent.deleteSchedule')}
                        onChange={() => void stop(schedule)}
                      />
                    </div>
                  </article>
                ))}
              </FadeIn>
            )}
      </div>
    </div>
  )
}
