import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { formatDateTime } from '@/lib/utils'
import { useLocation, useNavigate } from 'react-router-dom'
import { Copy, LayoutTemplate, Plus, Sparkles, Trash2, Upload, Workflow } from 'lucide-react'
import { services } from '@/services'
import { appConfirm } from '@/lib/appDialogs'
import type { AgentTool, WorkflowDefinition, WorkflowTemplateSummary } from '@/services/types'
import { FLOW_CHAT_SEED_KEY } from '@/lib/flowSeed'
import { parseFyfDocument, stashPendingFyfImport, fyfHasPlan, missingTemplateTools } from '@/lib/fyf'
import '@/styles/features/flow.css'
import { TextGenerateEffect } from '@/aceternity/text-generate-effect'
import { SpotlightCard } from '@/components/aceternity/SpotlightCard'
import { IntentInput } from '@/components/aceternity/IntentInput'
import { MeteorShower } from '@/components/aceternity/MeteorShower'
import FlowBuilderPage from '@/pages/FlowBuilderPage'
import FlowCopilotPage from '@/pages/FlowCopilotPage'

/**
 * iFlow landing + dispatcher. Every /flows route renders this page: the
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

  if (activeKey === 'copilot') {
    return <FlowCopilotPage />
  }
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
  if (match[1] === 'create') return 'copilot'
  return match[1] === 'new' ? 'new' : decodeURIComponent(match[1])
}

function FlowLibraryList() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [workflows, setWorkflows] = useState<WorkflowDefinition[]>([])
  const [tools, setTools] = useState<AgentTool[]>([])
  const [templates, setTemplates] = useState<WorkflowTemplateSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const importFileRef = useRef<HTMLInputElement | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        // The tool catalog gates the template cards (missing plugin → disabled card).
        const [list, catalog, templateList] = await Promise.all([
          services.workflow.list(),
          services.agent.tools(),
          services.workflow.templates().catch(() => [] as WorkflowTemplateSummary[]),
        ])
        if (!cancelled) {
          setWorkflows(list)
          setTools(catalog ?? [])
          setTemplates(templateList ?? [])
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

  const refreshList = useCallback(async () => {
    try {
      setWorkflows(await services.workflow.list())
    } catch {
      /* the list keeps its last known copy — the error banner owns the failure */
    }
  }, [])

  /**
   * Novice front door: hand the intent to the conversation-first copilot (the same
   * seed channel the main chat's @-flow hand-off uses) instead of the canvas.
   */
  const buildWithAi = useCallback((text: string) => {
    const intent = text.trim()
    if (!intent) return
    try {
      sessionStorage.setItem(FLOW_CHAT_SEED_KEY,
        JSON.stringify({ workflowId: null, text: intent, at: Date.now(), autoSend: true }))
      navigate('/flows/create')
    } catch {
      setErrorMsg(t('agent.failed'))
    }
  }, [navigate, t])

  /** .fyf import: plan documents go through the API, graph-only ones through the builder. */
  const importFyfFile = useCallback(async (file: File) => {
    setErrorMsg(null)
    setNotice(null)
    let text: string
    try {
      text = await file.text()
    } catch {
      setErrorMsg(t('flows.importUnreadable'))
      return
    }
    const parsed = parseFyfDocument(text)
    if (!parsed.ok) {
      setErrorMsg(t(parsed.error === 'not-json' ? 'flows.importNotJson'
        : parsed.error === 'bad-version' ? 'flows.importBadVersion' : 'flows.importNotFyf'))
      return
    }
    if (fyfHasPlan(parsed.doc)) {
      try {
        const result = await services.workflow.importDsl(parsed.doc)
        await refreshList()
        if (result.missingTools.length) {
          setNotice(t('flows.importMissingTools', { names: result.missingTools.join(', ') }))
          return
        }
        navigate(`/flows/${result.workflow.id}`)
      } catch (e) {
        setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
      }
      return
    }
    stashPendingFyfImport(parsed.doc)
    navigate('/flows/new')
  }, [navigate, refreshList, t])

  const sorted = useMemo(
    () => [...workflows].sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()),
    [workflows],
  )

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
      </header>

      {errorMsg && (
        <div className="cx-alert cx-alert--error">
          <span className="cx-alert__body">{errorMsg}</span>
          <button className="cx-iconbtn cx-iconbtn--sm" onClick={() => setErrorMsg(null)}>×</button>
        </div>
      )}

      {notice && (
        <div className="cx-alert cx-alert--info">
          <span className="cx-alert__body">{notice}</span>
          <button className="cx-btn cx-btn--outline" onClick={() => navigate('/store')}>
            {t('flows.importOpenStore')}
          </button>
          <button className="cx-iconbtn cx-iconbtn--sm" onClick={() => setNotice(null)}>×</button>
        </div>
      )}

      <section className="flow-ai-stage" aria-label={t('flows.homeHeadline')}>
        <MeteorShower number={12} />
        <div className="flow-ai-stage__core" aria-hidden="true">
          <span className="flow-ai-stage__ring" />
          <span className="flow-ai-stage__ring flow-ai-stage__ring--late" />
          <span className="flow-ai-stage__hex"><Sparkles size={22} /></span>
        </div>
        <p className="flow-ai-stage__eyebrow">{t('flows.homeEyebrow')}</p>
        <TextGenerateEffect
          words={t('flows.homeHeadline')}
          duration={0.7}
          className="flow-ai-stage__headline"
        />
        <p className="flow-ai-stage__sub">{t('flows.homeDescribeHint')}</p>
        <div className="flow-ai-stage__input">
          <IntentInput
            ariaLabel={t('flows.homeHeadline')}
            placeholders={[
              t('flows.homeExample1'),
              t('flows.homeExample2'),
              t('flows.homeExample3'),
              t('flows.homeExample4'),
            ]}
            onSubmit={(value) => buildWithAi(value)}
          />
        </div>
      </section>
      <div className="flow-home__links">
        <button className="flow-home__link" onClick={() => importFileRef.current?.click()}>
          <Upload size={13} /> {t('flows.homeImportFyf')}
        </button>
        <input
          ref={importFileRef}
          type="file"
          accept=".fyf,application/json"
          hidden
          onChange={(event) => {
            const file = event.target.files?.[0]
            event.target.value = ''
            if (file) void importFyfFile(file)
          }}
        />
        <button className="flow-home__link" onClick={() => navigate('/flows/new')}>
          <Plus size={13} /> {t('flows.homeAdvancedBlank')}
        </button>
      </div>

      {templates.length > 0 && (
        <div className="flow-library__section">
          <h2>{t('agent.templatesTitle')}</h2>
          {/* Same card language as "my flows" — one grid, one rhythm. Templates whose
              plugins are missing stay out entirely (they cannot run). */}
          <div className="flow-cards">
            {templates
              .map(template => ({ template, missing: missingTemplateTools(template.requiredTools, tools) }))
              .filter(({ missing }) => missing.length === 0)
              .map(({ template }) => (
                <article
                  key={template.id}
                  className="flow-tpl"
                  title={template.description ?? undefined}
                  onClick={() => navigate(`/flows/new?template=${encodeURIComponent(template.id)}`)}
                >
                  <SpotlightCard className="flow-card flow-card--template flow-tpl__card">
                    <span className="flow-card__icon"><LayoutTemplate size={17} /></span>
                    <span className="flow-card__body">
                      <strong>{template.name}</strong>
                      <small>{template.description ?? ''}</small>
                    </span>
                    <span className="cx-chip">{t('flows.templateChip')}</span>
                  </SpotlightCard>
                </article>
              ))}
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
                    {formatDateTime(definition.updatedAt, i18n.language)}
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
