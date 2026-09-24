import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, Check, ChevronDown, ChevronRight, Copy, Play, RotateCcw, Scissors, Trash2, X } from 'lucide-react'
import { services } from '@/services'
import type {
  AgentRunDetail,
  AgentRunSummary,
  AgentScheduleSummary,
  AgentTaskCapacity,
  AgentTaskSummary,
  WorkflowDefinition,
  WorkflowWebhookDeliverySummary,
  WorkflowWebhookTriggerCreated,
  WorkflowWebhookTriggerSummary,
} from '@/services/types'
import type { useAgentRunStream, AgentRunStatus } from '@/lib/agentRunStream'

/**
 * The execution surface docked right of the canvas (port of the Vue
 * FlowExecutionPanel): the live/persisted run with per-step results and
 * rewind-from-step, run history with resume/fork, background tasks with
 * capacity, workflow schedules, and webhook triggers with delivery history.
 */

type Run = ReturnType<typeof useAgentRunStream>

const STATUS_KEYS: Partial<Record<AgentRunStatus | string, string>> = {
  planning: 'agent.planning',
  'awaiting-plan': 'agent.waitingPlanApproval',
  'awaiting-step': 'agent.waitingStepApproval',
  running: 'agent.running',
  complete: 'agent.completed',
  error: 'agent.failed',
  cancelled: 'agent.cancelled',
  'recovery-required': 'agent.recoveryRequired',
}

/** Statuses that offer "resume" (everything failed/cancelled/needs review). */
const RESUMABLE = new Set(['failed', 'cancelled', 'recovery-required'])

export function FlowExecutionPanel(props: {
  run: Run
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [history, setHistory] = useState<AgentRunSummary[]>([])
  const [historyQuery, setHistoryQuery] = useState('')
  const [persisted, setPersisted] = useState<AgentRunDetail | null>(null)
  const [tasks, setTasks] = useState<AgentTaskSummary[]>([])
  const [capacity, setCapacity] = useState<AgentTaskCapacity | null>(null)
  const [schedules, setSchedules] = useState<AgentScheduleSummary[]>([])
  const [workflows, setWorkflows] = useState<WorkflowDefinition[]>([])
  const [webhooks, setWebhooks] = useState<WorkflowWebhookTriggerSummary[]>([])
  const [deliveries, setDeliveries] = useState<Record<string, WorkflowWebhookDeliverySummary[]>>({})
  const [openWebhook, setOpenWebhook] = useState<string | null>(null)
  const [secretOnce, setSecretOnce] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [expandedStep, setExpandedStep] = useState<number | null>(null)

  const loadSurroundings = useCallback(async () => {
    try {
      const [runs, taskList, taskCapacity, scheduleList, workflowList, webhookList] = await Promise.all([
        services.agent.runsQuery('', 50),
        services.agent.tasks(),
        services.agent.taskCapacity(),
        services.agent.schedules(),
        services.workflow.list(),
        services.workflow.webhookTriggers(),
      ])
      setHistory(runs ?? [])
      setTasks(taskList ?? [])
      setCapacity(taskCapacity ?? null)
      setSchedules(scheduleList ?? [])
      setWorkflows(workflowList ?? [])
      setWebhooks(webhookList ?? [])
    } catch (e) {
      setErrorMsg(e instanceof Error && e.message ? e.message : t('agent.failed'))
    }
  }, [t])

  useEffect(() => {
    void loadSurroundings()
    // Runs/tasks tick while the panel is open (Vue polled the same surfaces).
    const timer = window.setInterval(() => void loadSurroundings(), 15_000)
    return () => window.clearInterval(timer)
  }, [loadSurroundings])

  const searchHistory = async () => {
    try {
      setHistory(await services.agent.runsQuery(historyQuery.trim(), 50))
    } catch {
      /* keep the previous list */
    }
  }

  const openPersisted = async (runId: string) => {
    try {
      setPersisted(await services.agent.runDetail(runId))
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }

  const resumeRun = async (runId: string) => {
    try {
      const response = await services.agent.resume(runId)
      props.run.attachRun(response.runId, null)
      await openPersisted(response.runId)
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }

  const forkRun = async (runId: string) => {
    try {
      const response = await services.agent.forkRun(runId)
      props.run.attachRun(response.runId, null)
      await openPersisted(response.runId)
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }

  /** Rewind-from-step: resume with only the steps before this one kept. */
  const rewindToStep = async (keepSteps: number) => {
    const runId = props.run.runId
    if (!runId) return
    try {
      const response = await services.agent.rewindRun(runId, keepSteps)
      props.run.attachRun(response.runId, props.run.plan)
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }

  const workflowName = (workflowId: string): string =>
    workflows.find((workflow) => workflow.id === workflowId)?.name ?? workflowId

  const toggleDeliveries = async (triggerId: string) => {
    if (openWebhook === triggerId) {
      setOpenWebhook(null)
      return
    }
    setOpenWebhook(triggerId)
    try {
      const list = await services.workflow.webhookDeliveries(triggerId)
      setDeliveries((current) => ({ ...current, [triggerId]: list }))
    } catch {
      setDeliveries((current) => ({ ...current, [triggerId]: [] }))
    }
  }

  const rotateSecret = async (triggerId: string) => {
    try {
      const created: WorkflowWebhookTriggerCreated = await services.workflow.rotateWebhookSecret(triggerId)
      setSecretOnce(created.secret)
      await loadSurroundings()
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }

  const deleteWebhook = async (triggerId: string) => {
    try {
      await services.workflow.deleteWebhookTrigger(triggerId)
      setWebhooks((current) => current.filter((trigger) => trigger.triggerId !== triggerId))
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }

  const deleteSchedule = async (scheduleId: string) => {
    try {
      await services.agent.deleteSchedule(scheduleId)
      setSchedules((current) => current.filter((schedule) => schedule.scheduleId !== scheduleId))
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }

  const killTask = async (taskId: string) => {
    try {
      await services.agent.killTask(taskId)
      await loadSurroundings()
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }

  const idle = !props.run.busy
  const statusKey = STATUS_KEYS[props.run.status] ?? 'agent.running'

  return (
    <aside className="flow-panel flow-panel--right flow-exec">
      <div className="flow-inspector__head">
        <span className="flow-inspector__title">{t('agent.runPanel')}</span>
        <button className="cx-iconbtn cx-iconbtn--sm" aria-label={t('flows.close')} onClick={props.onClose}>
          <X size={16} />
        </button>
      </div>

      {errorMsg && (
        <div className="cx-alert cx-alert--error">
          <span className="cx-alert__body">{errorMsg}</span>
          <button className="cx-iconbtn cx-iconbtn--sm" onClick={() => setErrorMsg(null)}>×</button>
        </div>
      )}

      {/* ── live run ── */}
      {props.run.status !== 'idle' && (
        <div className="flow-exec__current">
          <span className={`cx-chip flow-exec__status flow-exec__status--${props.run.status}`}>
            {t(statusKey)}
          </span>
          {props.run.errorMsg && (
            <div className="cx-alert cx-alert--error">
              <AlertTriangle size={14} />
              <span className="cx-alert__body">{props.run.errorMsg}</span>
            </div>
          )}
          {props.run.summary && <div className="cx-alert cx-alert--info"><span className="cx-alert__body">{props.run.summary}</span></div>}
          {props.run.awaitingApproval && (
            <button className="cx-btn cx-btn--primary" onClick={() => void props.run.approve()}>
              <Check size={14} /> {props.run.awaitingApproval === 'plan'
                ? t('agent.waitingPlanApproval') : t('agent.waitingStepApproval')}
            </button>
          )}
          {props.run.busy && (
            <button className="cx-btn cx-btn--outline" onClick={() => void props.run.cancel()}>
              {t('agent.cancel')}
            </button>
          )}
          <div className="flow-exec__steps">
            {props.run.stepList.map((step) => (
              <div key={step.index} className="flow-exec__step">
                <div
                  className="flow-exec__step-head"
                  onClick={() => setExpandedStep(expandedStep === step.index ? null : step.index)}
                  role="button"
                >
                  <span className={`flow-exec__step-dot flow-exec__step-dot--${step.status}`} />
                  <span className="flow-exec__step-name">{step.toolName}</span>
                  <span className="cx-muted flow-exec__step-desc">{step.description}</span>
                </div>
                {step.status === 'retrying' && (
                  <span className="cx-muted flow-exec__step-retry">{t('agent.retry')}</span>
                )}
                {expandedStep === step.index && (
                  <pre className="flow-exec__step-result mono">
                    {props.run.stepResults.get(step.index) || '—'}
                  </pre>
                )}
                {idle && !persisted && (
                  <button
                    className="cx-iconbtn cx-iconbtn--sm"
                    title={t('agent.rewindToStep')}
                    onClick={() => void rewindToStep(step.index)}
                  >
                    <RotateCcw size={13} />
                  </button>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── persisted run detail (history drill-down) ── */}
      {persisted && (
        <div className="flow-exec__current">
          <div className="flow-exec__detail-head">
            <span className={`cx-chip flow-exec__status flow-exec__status--${persisted.status}`}>
              {STATUS_KEYS[persisted.status] ? t(STATUS_KEYS[persisted.status]!) : persisted.status}
            </span>
            <button className="cx-iconbtn cx-iconbtn--sm" aria-label={t('flows.close')} onClick={() => setPersisted(null)}>
              <X size={14} />
            </button>
          </div>
          <p className="flow-exec__detail-goal">{persisted.goal}</p>
          {RESUMABLE.has(persisted.status) && (
            <button className="cx-btn cx-btn--outline" onClick={() => void resumeRun(persisted.id)}>
              {t('agent.resume')}
            </button>
          )}
          <div className="flow-exec__steps">
            {(persisted.plan?.steps ?? []).map((step) => (
              <div key={step.index} className="flow-exec__step">
                <div
                  className="flow-exec__step-head"
                  onClick={() => setExpandedStep(expandedStep === step.index ? null : step.index)}
                  role="button"
                >
                  <span className={`flow-exec__step-dot flow-exec__step-dot--${step.status}`} />
                  <span className="flow-exec__step-name">{step.toolName}</span>
                  <span className="cx-muted flow-exec__step-desc">{step.description}</span>
                </div>
                {expandedStep === step.index && (
                  <pre className="flow-exec__step-result mono">
                    {persisted.executions.find((execution) => execution.index === step.index)?.result ?? '—'}
                  </pre>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── run history ── */}
      <details className="flow-exec__section" open>
        <summary>{t('agent.history')}</summary>
        <div className="flow-exec__search">
          <input
            className="cx-input"
            placeholder={t('agent.historySearchPlaceholder')}
            value={historyQuery}
            onChange={(event) => setHistoryQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void searchHistory()
            }}
          />
        </div>
        {!history.length ? (
          <p className="cx-muted flow-exec__empty">{t('agent.historyEmpty')}</p>
        ) : (
          history.map((entry) => (
            <div key={entry.id} className="flow-exec__row">
              <div className="flow-exec__row-main">
                <strong title={entry.goal}>{entry.goal || entry.id}</strong>
                <small>
                  {STATUS_KEYS[entry.status] ? t(STATUS_KEYS[entry.status]!) : entry.status}
                  {' · '}
                  {new Date(entry.updatedAt).toLocaleString()}
                </small>
              </div>
              <div className="flow-exec__row-actions">
                <button className="cx-iconbtn cx-iconbtn--sm" title={t('agent.historySearch') === '' ? '' : undefined} onClick={() => void openPersisted(entry.id)}>
                  <ChevronRight size={14} />
                </button>
                {RESUMABLE.has(entry.status) && (
                  <button className="cx-iconbtn cx-iconbtn--sm" title={t('agent.resume')} onClick={() => void resumeRun(entry.id)}>
                    <Play size={13} />
                  </button>
                )}
                <button className="cx-iconbtn cx-iconbtn--sm" title={t('agent.forkRun')} onClick={() => void forkRun(entry.id)}>
                  <Scissors size={13} />
                </button>
              </div>
            </div>
          ))
        )}
      </details>

      {/* ── background tasks ── */}
      <details className="flow-exec__section">
        <summary>{t('agent.backgroundTasks')}</summary>
        {capacity && (
          <p className="cx-muted flow-exec__capacity">
            {t('agent.backgroundTaskCapacity', {
              running: capacity.running,
              limit: capacity.runningLimit,
              queued: capacity.queued,
            })}
          </p>
        )}
        {!tasks.length ? (
          <p className="cx-muted flow-exec__empty">{t('agent.historyEmpty')}</p>
        ) : (
          tasks.map((task) => (
            <div key={task.taskId} className="flow-exec__row">
              <div className="flow-exec__row-main">
                <strong title={task.description}>{task.description || task.kind}</strong>
                <small>
                  {task.status}
                  {task.queueWaitMs !== undefined && task.queueWaitMs > 0
                    ? ` · ${t('agent.backgroundTaskQueueWait', { seconds: Math.round(task.queueWaitMs / 1000) })}` : ''}
                </small>
              </div>
              {(task.status === 'queued' || task.status === 'running') && (
                <div className="flow-exec__row-actions">
                  <button className="cx-iconbtn cx-iconbtn--sm" title={t('agent.killTask')} onClick={() => void killTask(task.taskId)}>
                    <X size={13} />
                  </button>
                </div>
              )}
            </div>
          ))
        )}
      </details>

      {/* ── schedules ── */}
      <details className="flow-exec__section">
        <summary>{t('agent.schedules')}</summary>
        {!schedules.length ? (
          <p className="cx-muted flow-exec__empty">{t('agent.historyEmpty')}</p>
        ) : (
          schedules.map((schedule) => (
            <div key={schedule.scheduleId} className="flow-exec__row">
              <div className="flow-exec__row-main">
                <strong>{workflowName(schedule.workflowId)}</strong>
                <small>
                  {t('agent.scheduleNext')} {new Date(schedule.nextFireAt).toLocaleString()}
                  {' · '}
                  {t('agent.scheduleFires', { count: schedule.fires })}
                  {schedule.missedFires > 0 ? ` · ${t('agent.scheduleMissed', { count: schedule.missedFires })}` : ''}
                  {schedule.lastError ? ` · ${schedule.lastError}` : ''}
                </small>
              </div>
              <div className="flow-exec__row-actions">
                <button className="cx-iconbtn cx-iconbtn--sm" title={t('agent.deleteSchedule')} onClick={() => void deleteSchedule(schedule.scheduleId)}>
                  <Trash2 size={13} />
                </button>
              </div>
            </div>
          ))
        )}
      </details>

      {/* ── webhook triggers ── */}
      <details className="flow-exec__section">
        <summary>{t('agent.webhookTriggers')}</summary>
        {!webhooks.length ? (
          <p className="cx-muted flow-exec__empty">{t('agent.historyEmpty')}</p>
        ) : (
          webhooks.map((trigger) => (
            <div key={trigger.triggerId} className="flow-exec__row">
              <div className="flow-exec__row-main">
                <strong>{trigger.name}</strong>
                <small title={trigger.endpoint}>
                  {trigger.endpoint}
                  {' · '}
                  {t('agent.webhookFires', { count: trigger.fires })}
                  {trigger.lastError ? ` · ${trigger.lastError}` : ''}
                </small>
              </div>
              <div className="flow-exec__row-actions">
                <button
                  className="cx-iconbtn cx-iconbtn--sm"
                  title={t('agent.webhookDeliveryHistory')}
                  onClick={() => void toggleDeliveries(trigger.triggerId)}
                >
                  {openWebhook === trigger.triggerId ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                </button>
                <button className="cx-iconbtn cx-iconbtn--sm" title={t('agent.rotateWebhookSecret')} onClick={() => void rotateSecret(trigger.triggerId)}>
                  <RotateCcw size={13} />
                </button>
                <button className="cx-iconbtn cx-iconbtn--sm" title={t('agent.deleteWebhook')} onClick={() => void deleteWebhook(trigger.triggerId)}>
                  <Trash2 size={13} />
                </button>
              </div>
              {openWebhook === trigger.triggerId && (
                <div className="flow-exec__deliveries">
                  {secretOnce && (
                    <div className="flow-exec__secret-once">
                      {t('agent.webhookSecretOnce')}
                      <code>{secretOnce}</code>
                      <button
                        className="cx-iconbtn cx-iconbtn--sm"
                        title={t('common.copied')}
                        onClick={() => void navigator.clipboard?.writeText(secretOnce)}
                      >
                        <Copy size={12} />
                      </button>
                    </div>
                  )}
                  {(deliveries[trigger.triggerId] ?? []).length === 0 ? (
                    <p className="cx-muted flow-exec__empty">{t('agent.webhookNoDeliveries')}</p>
                  ) : (
                    deliveries[trigger.triggerId].map((delivery) => (
                      <div key={delivery.taskId ?? delivery.acceptedAt} className="flow-exec__delivery">
                        <span className="cx-chip flow-exec__status--delivery">
                          {delivery.status}
                        </span>
                        <small>
                          {new Date(delivery.acceptedAt).toLocaleString()}
                          {delivery.idempotencyKeyPresent ? ` · ${t('agent.webhookEventKeyed')}` : ''}
                          {delivery.error ? ` · ${delivery.error}` : ''}
                        </small>
                      </div>
                    ))
                  )}
                </div>
              )}
            </div>
          ))
        )}
      </details>
    </aside>
  )
}
