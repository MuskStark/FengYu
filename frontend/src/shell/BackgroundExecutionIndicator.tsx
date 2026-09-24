import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { AlertTriangle, ChevronRight, Clock, Loader2, Wrench, X } from 'lucide-react'
import { useBackgroundTasksStore } from '@/stores/backgroundTasks'
import { usePluginBackgroundJobsStore, type PluginBackgroundJob } from '@/stores/pluginBackgroundJobs'
import { usePluginsStore } from '@/stores/plugins'
import { cn } from '@/lib/utils'
import { checkNavigationGuard } from '@/lib/navGuard'

type IndicatorStatus = 'running' | 'queued' | 'unknown'

interface IndicatorEntry {
  key: string
  kind: 'plugin' | 'workflow'
  title: string
  detail: string
  status: IndicatorStatus
  pluginJob?: PluginBackgroundJob
}

function humanizeMethod(method: string): string {
  return method.replace(/_start$/, '').replace(/_/g, ' ')
}

/**
 * Shell-level "background execution" pill — React port of the Vue BackgroundExecutionIndicator.
 * Aggregates plugin-domain jobs (the pluginBackgroundJobs ledger) and backend workflow tasks
 * (the backgroundTasks poll), floating above the status bar while anything is active. The
 * shell mounts it on every route, so it starts/stops both polls for the whole app.
 */
export default function BackgroundExecutionIndicator() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const background = useBackgroundTasksStore()
  const pluginJobs = usePluginBackgroundJobsStore()
  const plugins = usePluginsStore()
  const root = useRef<HTMLDivElement | null>(null)
  const [panelOpen, setPanelOpen] = useState(false)

  useEffect(() => {
    background.start()
    pluginJobs.start()
    if (!plugins.plugins.length) void plugins.load()
    return () => {
      background.stop()
      pluginJobs.stop()
    }
  }, [background, pluginJobs, plugins])

  useEffect(() => {
    const onPointerDown = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setPanelOpen(false)
    }
    const onKeydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setPanelOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeydown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeydown)
    }
  }, [])

  const entries = useMemo<IndicatorEntry[]>(() => [
    ...pluginJobs.jobs.map(job => ({
      key: `plugin:${job.key}`,
      kind: 'plugin' as const,
      title: plugins.plugins.find(p => p.id === job.pluginId)?.name ?? job.pluginId,
      detail: humanizeMethod(job.startMethod),
      status: job.status,
      pluginJob: job,
    })),
    ...background.tasks
      .filter(task => task.status === 'queued' || task.status === 'running')
      .map(task => ({
        key: `workflow:${task.taskId}`,
        kind: 'workflow' as const,
        title: task.description || t('backgroundIndicator.workflowTask'),
        detail: task.kind,
        status: task.status as 'running' | 'queued',
      })),
  ], [pluginJobs.jobs, background.tasks, plugins.plugins, t])

  const total = entries.length
  const runningCount = entries.filter(entry => entry.status === 'running').length
  const queuedCount = entries.filter(entry => entry.status === 'queued').length
  const unknownCount = entries.filter(entry => entry.status === 'unknown').length

  useEffect(() => {
    if (total === 0) setPanelOpen(false)
  }, [total])

  if (total === 0) return null

  const summary = unknownCount > 0
    ? t('backgroundIndicator.summaryUnknown', { count: total })
    : runningCount > 0 && queuedCount > 0
      ? t('backgroundIndicator.summaryMixed', { running: runningCount, queued: queuedCount })
      : queuedCount > 0
        ? t('backgroundIndicator.summaryQueued', { count: queuedCount })
        : t('backgroundIndicator.summaryRunning', { count: runningCount })

  const openEntry = (entry: IndicatorEntry) => {
    setPanelOpen(false)
    void (async () => {
      if (!(await checkNavigationGuard())) return
      if (entry.pluginJob) navigate(`/plugin/${entry.pluginJob.pluginId}`)
      else navigate('/flows/new')
    })
  }

  return (
    <div ref={root} className="background-execution" aria-live="polite">
      {panelOpen && (
        <section className="background-execution__panel" role="dialog" aria-label={t('backgroundIndicator.title')}>
          <div className="background-execution__panel-head">
            <span><Wrench size={17} aria-hidden="true" />{t('backgroundIndicator.title')}</span>
            <button className="cx-iconbtn cx-iconbtn--sm" aria-label={t('backgroundIndicator.close')} onClick={() => setPanelOpen(false)}>
              <X size={13} />
            </button>
          </div>
          <div className="background-execution__list">
            {entries.map(entry => (
              <div key={entry.key} className="background-execution__item">
                <button className="background-execution__item-main" onClick={() => openEntry(entry)}>
                  {entry.status === 'queued'
                    ? <Clock size={18} className="background-execution__item-icon" aria-hidden="true" />
                    : entry.status === 'unknown'
                      ? <AlertTriangle size={18} className="background-execution__item-icon" aria-hidden="true" />
                      : <Loader2 size={18} className="background-execution__item-icon cx-spin-icon" aria-hidden="true" />}
                  <span className="background-execution__item-copy">
                    <strong>{entry.title}</strong>
                    <small>{entry.detail} · {t(`backgroundIndicator.status.${entry.status}`)}</small>
                  </span>
                  <ChevronRight size={15} className="background-execution__chevron" aria-hidden="true" />
                </button>
                {entry.status === 'unknown' && entry.pluginJob && (
                  <button
                    className="cx-iconbtn cx-iconbtn--sm background-execution__dismiss"
                    title={t('backgroundIndicator.dismiss')}
                    aria-label={t('backgroundIndicator.dismiss')}
                    onClick={() => pluginJobs.remove(entry.pluginJob!.key)}
                  ><X size={13} /></button>
                )}
              </div>
            ))}
          </div>
        </section>
      )}

      <button
        type="button"
        className={cn('background-execution__trigger',
          queuedCount > 0 && 'background-execution__trigger--warn',
          unknownCount > 0 && 'background-execution__trigger--unknown')}
        aria-expanded={panelOpen}
        aria-label={summary}
        onClick={() => setPanelOpen(open => !open)}
      >
        {unknownCount
          ? <AlertTriangle size={16} aria-hidden="true" />
          : queuedCount && !runningCount
            ? <Clock size={16} aria-hidden="true" />
            : <Loader2 size={16} className="cx-spin-icon" aria-hidden="true" />}
        <span>{summary}</span>
        <ChevronRight size={14} style={{ transform: panelOpen ? 'rotate(90deg)' : 'rotate(-90deg)' }} aria-hidden="true" />
      </button>
    </div>
  )
}
