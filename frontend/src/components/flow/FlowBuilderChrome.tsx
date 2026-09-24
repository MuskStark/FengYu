import { useTranslation } from 'react-i18next'
import {
  ArrowLeft,
  Check,
  History,
  Maximize,
  MessageSquare,
  Play,
  Plus,
  Redo2,
  RotateCcw,
  Save,
  Settings2,
  Square,
  StickyNote,
  Trash2,
  Undo2,
  Wrench,
  X,
} from 'lucide-react'
import type { WorkflowRevisionSummary } from '@/services/types'
import type { WorkflowTemplate } from '@/components/flow/workflowTemplates'

/**
 * Builder chrome: the top toolbar (brand/back, chat + settings toggles, save,
 * run/cancel) plus the workflow settings side panel (name/description/goal,
 * publish-to-AI, version history, delete) and the small canvas overlay strips
 * (empty state, canvas actions, alerts).
 */

export function FlowToolbar(props: {
  title: string
  nodeCount: number
  published: boolean
  hasUnpublishedChanges: boolean
  dirty: boolean
  busy: boolean
  chatOpen: boolean
  settingsOpen: boolean
  execOpen: boolean
  canSave: boolean
  runDisabled: boolean
  onBack: () => void
  onToggleChat: () => void
  onToggleSettings: () => void
  onToggleExec: () => void
  onSave: () => void
  onRunClick: () => void
}) {
  const { t } = useTranslation()
  const publishLabel = props.published
    ? (props.hasUnpublishedChanges ? t('agent.publishedWithDraft') : t('agent.published'))
    : t('agent.draft')
  return (
    <header className="flow-toolbar">
      <button className="flow-brand" title={t('flows.backToLibrary')} onClick={props.onBack}>
        <span className="flow-brand__mark"><Wrench size={16} /></span>
        <span>
          <strong>{props.title}</strong>
          <small>
            {props.nodeCount} {t('agent.nodes')} · {publishLabel}
            {props.dirty ? <em className="flow-unsaved" title={t('agent.unsavedChanges')}>●</em> : null}
          </small>
        </span>
        <ArrowLeft size={15} />
      </button>
      <div className="flow-toolbar-spacer" />
      <button
        className={`flow-toolbar-button${props.chatOpen ? ' active' : ''}`}
        onClick={props.onToggleChat}
      >
        <MessageSquare size={14} /> {t('flows.chatTitle')}
      </button>
      <button
        className={`flow-toolbar-button${props.execOpen ? ' active' : ''}`}
        onClick={props.onToggleExec}
      >
        <Play size={14} /> {t('agent.runPanel')}
      </button>
      <button
        className={`flow-toolbar-button${props.settingsOpen ? ' active' : ''}`}
        onClick={props.onToggleSettings}
      >
        <Settings2 size={14} /> {t('agent.workflowSettings')}
      </button>
      <button className="flow-toolbar-button" disabled={!props.canSave} onClick={props.onSave}>
        <Save size={14} /> {t('agent.saveWorkflow')}
      </button>
      <button
        className="flow-run-button"
        disabled={props.runDisabled}
        onClick={props.onRunClick}
      >
        {props.busy ? <Square size={14} /> : <Play size={14} />}
        {props.busy ? t('agent.cancel') : t('agent.testRun')}
      </button>
    </header>
  )
}

export function FlowSettingsPanel(props: {
  name: string
  description: string
  goal: string
  canSave: boolean
  hasId: boolean
  published: boolean
  hasUnpublishedChanges: boolean
  publishedRevision: number | null
  revisions: WorkflowRevisionSummary[]
  onName: (value: string) => void
  onDescription: (value: string) => void
  onGoal: (value: string) => void
  onSave: () => void
  onTogglePublish: () => void
  onRestoreRevision: (revision: number) => void
  onDelete: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  // Vue labels: 未发布 → 发布给 AI；已发布 + 草稿修改 → 发布修改；已发布 → 取消 AI 发布。
  const publishLabel = !props.published
    ? t('agent.publishForAi')
    : props.hasUnpublishedChanges ? t('agent.publishChanges') : t('agent.unpublish')
  return (
    <aside className="flow-panel flow-panel--right flow-settings">
      <div className="flow-inspector__head">
        <span className="flow-inspector__title">{t('agent.workflowSettings')}</span>
        <button className="cx-iconbtn cx-iconbtn--sm" aria-label={t('flows.close')} onClick={props.onClose}>
          <X size={16} />
        </button>
      </div>
      <label className="flow-field">
        <span>{t('agent.workflowName')}</span>
        <input
          className="cx-input"
          value={props.name}
          placeholder={t('agent.untitledWorkflow')}
          onChange={(event) => props.onName(event.target.value)}
        />
      </label>
      <label className="flow-field">
        <span>{t('agent.description')}</span>
        <input
          className="cx-input"
          value={props.description}
          placeholder={t('agent.workflowDescription')}
          onChange={(event) => props.onDescription(event.target.value)}
        />
      </label>
      <label className="flow-field">
        <span>{t('agent.canvasGoalPlaceholder')}</span>
        <textarea
          className="cx-textarea"
          rows={3}
          value={props.goal}
          placeholder={t('agent.canvasGoalPlaceholder')}
          onChange={(event) => props.onGoal(event.target.value)}
        />
      </label>
      <button className="cx-btn cx-btn--primary" disabled={!props.canSave} onClick={props.onSave}>
        <Check size={14} /> {t('agent.saveWorkflow')}
      </button>

      {props.hasId && (
        <>
          {props.published && props.hasUnpublishedChanges && props.publishedRevision != null && (
            <div className="cx-alert cx-alert--info flow-settings__notice">
              {t('agent.unpublishedChanges', { revision: props.publishedRevision })}
            </div>
          )}
          <button
            className={props.published ? 'cx-btn cx-btn--outline' : 'cx-btn cx-btn--primary'}
            onClick={props.onTogglePublish}
          >
            {publishLabel}
          </button>

          <div className="flow-settings__versions">
            <span className="flow-settings__versions-title">
              <History size={13} /> {t('agent.versionHistory')}
            </span>
            {!props.revisions.length ? (
              <span className="cx-muted flow-settings__versions-empty">{t('agent.noVersionHistory')}</span>
            ) : (
              props.revisions.map((revision) => (
                <div key={revision.revision} className="flow-settings__revision">
                  <span className="flow-settings__revision-meta">
                    <strong>v{revision.revision}</strong>
                    <small>{new Date(revision.publishedAt).toLocaleString()}</small>
                  </span>
                  {revision.active ? (
                    <span className="cx-chip cx-chip--success">{t('agent.activeVersion')}</span>
                  ) : (
                    <button
                      className="cx-iconbtn cx-iconbtn--sm"
                      title={t('agent.restoreVersion')}
                      onClick={() => props.onRestoreRevision(revision.revision)}
                    >
                      <RotateCcw size={14} />
                    </button>
                  )}
                </div>
              ))
            )}
          </div>

          <button className="cx-btn cx-btn--outline flow-settings__delete" onClick={props.onDelete}>
            <Trash2 size={14} /> {t('agent.deleteWorkflow')}
          </button>
        </>
      )}
    </aside>
  )
}

const GUIDE_STEPS = [
  { titleKey: 'flows.guideStep1Title', descKey: 'flows.guideStep1Desc' },
  { titleKey: 'flows.guideStep2Title', descKey: 'flows.guideStep2Desc' },
  { titleKey: 'flows.guideStep3Title', descKey: 'flows.guideStep3Desc' },
] as const

/**
 * First-run surface on an empty canvas: a three-step guide (the numbers are the
 * real creation order) plus two immediate actions — a one-click template or a
 * blank start node.
 */
export function FlowEmptyState(props: {
  onAddStart: () => void
  templates: Array<{ template: WorkflowTemplate; missing: string[] }>
  onApplyTemplate: (template: WorkflowTemplate) => void
}) {
  const { t } = useTranslation()
  const availableTemplates = props.templates.filter((entry) => !entry.missing.length)
  return (
    <div className="flow-stage-empty flow-guide">
      <strong className="flow-guide__title">{t('flows.guideTitle')}</strong>
      <span className="flow-guide__subtitle">{t('flows.guideSubtitle')}</span>
      <ol className="flow-guide__steps">
        {GUIDE_STEPS.map((step, index) => (
          <li key={step.titleKey} className="flow-guide__step">
            <span className="flow-guide__num">{index + 1}</span>
            <span className="flow-guide__step-title">{t(step.titleKey)}</span>
            <span className="flow-guide__step-desc">{t(step.descKey)}</span>
          </li>
        ))}
      </ol>
      <div className="flow-guide__actions">
        {availableTemplates.length > 0 && (
          <div className="flow-guide__templates">
            <span className="flow-guide__templates-label">{t('flows.startFromTemplate')}</span>
            {availableTemplates.map(({ template }) => (
              <button
                key={template.id}
                className="flow-guide__template"
                title={t(template.descriptionKey)}
                onClick={() => props.onApplyTemplate(template)}
              >
                {t(template.titleKey)}
              </button>
            ))}
          </div>
        )}
        <button className="flow-run-button" onClick={props.onAddStart}>
          <Play size={14} /> {t('flows.startBlank')}
        </button>
      </div>
    </div>
  )
}

export function FlowCanvasActions(props: {
  paletteOpen: boolean
  canUndo: boolean
  canRedo: boolean
  incompleteCount: number
  onTogglePalette: () => void
  onUndo: () => void
  onRedo: () => void
  onAddNote: () => void
  onFitView: () => void
  onFocusIncomplete: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="flow-canvas-actions">
      <button
        className={props.paletteOpen ? 'active' : ''}
        title={t('agent.paletteShortcutHint')}
        onClick={props.onTogglePalette}
      >
        <Plus size={15} /> {t('agent.addNode')}
      </button>
      <button title={t('flows.undo')} disabled={!props.canUndo} onClick={props.onUndo}>
        <Undo2 size={15} />
      </button>
      <button title={t('flows.redo')} disabled={!props.canRedo} onClick={props.onRedo}>
        <Redo2 size={15} />
      </button>
      <button title={t('flows.addNote')} onClick={props.onAddNote}>
        <StickyNote size={15} />
      </button>
      <button title={t('agent.canvasFitView')} onClick={props.onFitView}>
        <Maximize size={15} />
      </button>
      {props.incompleteCount > 0 && (
        <button
          className="flow-canvas-warning flow-canvas-warning--action"
          title={t('flows.incompleteChipHint')}
          onClick={props.onFocusIncomplete}
        >
          {t('agent.incompleteNodes', { count: props.incompleteCount })}
        </button>
      )}
    </div>
  )
}

export function FlowStageAlert(props: {
  errorMsg: string | null
  recoveryMsg: string | null
  onClearError: () => void
  onClearRecovery: () => void
}) {
  const { t } = useTranslation()
  if (!props.errorMsg && !props.recoveryMsg) return null
  return (
    <div className="flow-stage-alert">
      <div className={`cx-alert ${props.errorMsg ? 'cx-alert--error' : 'cx-alert--info'}`}>
        <span className="cx-alert__body">{props.errorMsg ?? props.recoveryMsg}</span>
        <button
          className="cx-iconbtn cx-iconbtn--sm"
          aria-label={t('flows.close')}
          onClick={props.errorMsg ? props.onClearError : props.onClearRecovery}
        >
          <X size={15} />
        </button>
      </div>
    </div>
  )
}

/* guide hot-reload marker */
