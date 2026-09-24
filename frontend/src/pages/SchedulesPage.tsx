import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CalendarClock, RefreshCw } from 'lucide-react'
import '@/styles/pages.css'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import type { AgentScheduleSummary, WorkflowDefinition } from '@/services/types'
import { FadeIn } from '@/components/pages/FadeIn'
import { PageEmpty, PageError, PageLoading } from '@/components/pages/StateViews'
import { ToggleSwitch } from '@/components/pages/ToggleSwitch'
import { scheduleLabel } from '@/components/pages/scheduleLabel'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 15_000

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

/**
 * Schedules (React twin of the Vue Schedules list view): the workflow schedule
 * list with its firing rule, status and next fire time. The backend exposes no
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
            </div>
          </header>
          <p className="cx-muted pg-account-hint" style={{ marginTop: 0 }}>{t('schedules.runtime')}</p>
        </FadeIn>
        <div style={{ height: 14 }} />

        {error && <PageError message={error} onRetry={() => void reload()} />}
        {loading && schedules.length === 0
          ? <PageLoading label={t('schedules.loading')} />
          : !error && schedules.length === 0
            ? (
              <PageEmpty
                icon={<CalendarClock size={30} strokeWidth={1.5} />}
                title={t('schedules.empty')}
                hint={t('schedules.emptyWorkflows')}
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
