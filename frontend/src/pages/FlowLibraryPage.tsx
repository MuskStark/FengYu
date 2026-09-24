import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation, useNavigate } from 'react-router-dom'
import { Copy, Plus, Trash2, Workflow } from 'lucide-react'
import { services } from '@/services'
import { appConfirm } from '@/lib/appDialogs'
import type { AgentTool, WorkflowDefinition } from '@/services/types'
import {
  WORKFLOW_TEMPLATES,
  templateMissingTools,
  type WorkflowTemplate,
} from '@/components/flow/workflowTemplates'
import '@/styles/flow.css'
import FlowBuilderPage from '@/pages/FlowBuilderPage'

/**
 * FengyuFlow landing + dispatcher. Every /flows route renders this page: the
 * bare /flows path lists saved flows; /flows/new and /flows/:id delegate to the
 * builder. Because the dispatcher (not the router) owns the switch, transitions
 * between flow views pass an unsaved-changes confirmation first.
 */
export default function FlowLibraryPage() {
  const { t } = useTranslation()
  const location = useLocation()
  const navigate = useNavigate()

  /** The flow view actually rendered — lags behind the URL while a dirty guard is pending. */
  const [activeKey, setActiveKey] = useState<string>(() => resolveKey(location.pathname))
  const builderDirty = useRef(false)
  const prevPathRef = useRef(location.pathname)

  const onDirtyChange = useCallback((dirty: boolean) => {
    builderDirty.current = dirty
  }, [])

  // Resolve from the pathname, not route params: /flows/new is ALSO registered as a
  // static route, where useParams() yields no id at all.
  const routeKey = resolveKey(location.pathname)

  const confirmPending = useCallback((allow: boolean, target: string) => {
    if (allow) {
      builderDirty.current = false
      setActiveKey(target)
      return
    }
    // Deny: rewind to the flow still on screen (the URL moved ahead of the view).
    const previous = prevPathRef.current
    if (previous !== window.location.pathname) navigate(previous, { replace: true })
    else setActiveKey('list')
  }, [navigate])

  useEffect(() => {
    // Nothing to guard when the builder is clean, absent, or not switching views.
    const wasBuilder = activeKey !== 'list'
    const nextIsSame = routeKey === activeKey
    if (nextIsSame || !wasBuilder || !builderDirty.current) {
      setActiveKey(routeKey)
      prevPathRef.current = location.pathname
      return
    }
    // The URL already moved; keep rendering the previous flow until the in-app
    // discard dialog resolves (one dialog system — lib/appDialogs).
    let cancelled = false
    void appConfirm(t('agent.discardConfirm'), { danger: true }).then((allow) => {
      if (!cancelled) confirmPending(allow, routeKey)
    })
    return () => { cancelled = true }
  }, [routeKey, activeKey, location.pathname, confirmPending, t])

  if (activeKey !== 'list') {
    return (
      <FlowBuilderPage
        routeWorkflowId={activeKey === 'new' ? null : activeKey}
        onDirtyChange={onDirtyChange}
      />
    )
  }

  return <FlowLibraryList />
}

function resolveKey(pathname: string): string {
  const match = pathname.match(/^\/flows\/([^/?]+)/)
  if (!match) return 'list'
  return match[1] === 'new' ? 'new' : decodeURIComponent(match[1])
}

function FlowLibraryList() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [workflows, setWorkflows] = useState<WorkflowDefinition[]>([])
  const [tools, setTools] = useState<AgentTool[]>([])
  const [loading, setLoading] = useState(true)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        // The tool catalog gates the template cards (missing plugin → disabled card).
        const [list, catalog] = await Promise.all([services.workflow.list(), services.agent.tools()])
        if (!cancelled) {
          setWorkflows(list)
          setTools(catalog ?? [])
        }
      } catch (e) {
        if (!cancelled) setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => { cancelled = true }
  }, [t])

  const sorted = useMemo(
    () => [...workflows].sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()),
    [workflows],
  )

  const applyTemplate = useCallback((template: WorkflowTemplate) => {
    navigate({ pathname: '/flows/new', search: `?template=${encodeURIComponent(template.id)}` })
  }, [navigate])

  /** Saves the definition as a copy, then prepends it to the list (Vue duplicateFlow). */
  const duplicateFlow = useCallback(async (definition: WorkflowDefinition) => {
    try {
      // Strip an existing "(copy)"-style suffix so duplicating a duplicate stays readable.
      const baseName = definition.name.replace(/\s*[（(][^)）]*[)）]\s*$/, '').trim()
      const copyName = `${baseName} (${t('agent.workflowCopySuffix')})`.slice(0, 160)
      const saved = await services.workflow.create({
        name: copyName,
        description: definition.description,
        inputSchema: definition.inputSchema,
        plan: definition.plan,
        layout: definition.layout ?? undefined,
        graph: definition.graph ?? undefined,
      })
      setWorkflows((current) => [saved, ...current.filter((item) => item.id !== saved.id)])
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }, [t])

  const deleteFlow = useCallback(async (definition: WorkflowDefinition) => {
    if (!await appConfirm(t('agent.deleteWorkflowConfirm'), { danger: true })) return
    try {
      await services.workflow.delete(definition.id)
      setWorkflows((current) => current.filter((item) => item.id !== definition.id))
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }, [t])

  return (
    <div className="cx-page flow-library">
      <header className="flow-library__head">
        <div>
          <h1 className="cx-page-title">{t('flows.title')}</h1>
          <p className="cx-muted">{t('flows.subtitle')}</p>
        </div>
        <button className="cx-btn cx-btn--primary" onClick={() => navigate('/flows/new')}>
          <Plus size={15} /> {t('flows.newFlow')}
        </button>
      </header>

      {errorMsg && (
        <div className="cx-alert cx-alert--error">
          <span className="cx-alert__body">{errorMsg}</span>
          <button className="cx-iconbtn cx-iconbtn--sm" onClick={() => setErrorMsg(null)}>×</button>
        </div>
      )}

      {WORKFLOW_TEMPLATES.length > 0 && (
        <div className="flow-library__section">
          <h2>{t('agent.templatesTitle')}</h2>
          <div className="flow-cards">
            {WORKFLOW_TEMPLATES.map((template) => {
              const missing = templateMissingTools(template, tools)
              return (
                <button
                  key={template.id}
                  className="flow-card flow-card--template"
                  disabled={missing.length > 0}
                  title={missing.length ? t('agent.templateMissingTools', { names: missing.join(', ') }) : undefined}
                  onClick={() => applyTemplate(template)}
                >
                  <span className="flow-card__icon"><Workflow size={17} /></span>
                  <span className="flow-card__body">
                    <strong>{t(template.titleKey)}</strong>
                    <small>
                      {missing.length
                        ? t('agent.templateNeedsPlugins', { names: missing.join(', ') })
                        : t(template.descriptionKey)}
                    </small>
                  </span>
                  <span className="flow-card__go">→</span>
                </button>
              )
            })}
          </div>
        </div>
      )}

      <div className="flow-library__section">
        <h2>{t('flows.savedFlows')}</h2>
        {loading ? (
          <div className="cx-muted">{t('common.loading')}</div>
        ) : !sorted.length ? (
          <div className="flow-library__empty">
            <Workflow size={26} />
            <strong>{t('flows.emptyTitle')}</strong>
            <span>{t('agent.newWorkflowHint')}</span>
            <button className="cx-btn cx-btn--outline" onClick={() => navigate('/flows/new')}>
              <Plus size={15} /> {t('flows.newFlow')}
            </button>
          </div>
        ) : (
          <div className="flow-cards">
            {sorted.map((definition) => (
              <article
                key={definition.id}
                className="cx-card cx-card--hover flow-card"
                onClick={() => navigate(`/flows/${definition.id}`)}
              >
                <span className="flow-card__icon"><Workflow size={17} /></span>
                <span className="flow-card__body">
                  <strong>{definition.name}</strong>
                  <small>{definition.description || t('agent.noDescription')}</small>
                  <small className="flow-card__meta">
                    {definition.plan.steps.length} {t('agent.nodes')}
                    {' · '}
                    {new Date(definition.updatedAt).toLocaleString()}
                  </small>
                </span>
                <span className={`cx-chip${definition.published ? ' cx-chip--success' : ''}`}>
                  {definition.published ? t('agent.published') : t('agent.draft')}
                </span>
                <span className="flow-card__actions" onClick={(event) => event.stopPropagation()}>
                  <button
                    className="cx-iconbtn cx-iconbtn--sm"
                    title={t('agent.duplicateWorkflow')}
                    onClick={() => void duplicateFlow(definition)}
                  >
                    <Copy size={15} />
                  </button>
                  <button
                    className="cx-iconbtn cx-iconbtn--sm"
                    title={t('agent.deleteWorkflow')}
                    onClick={() => void deleteFlow(definition)}
                  >
                    <Trash2 size={15} />
                  </button>
                </span>
              </article>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
