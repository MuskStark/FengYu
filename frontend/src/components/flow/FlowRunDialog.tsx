import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertCircle, CheckCircle2, Loader2, Play, Square, X } from 'lucide-react'
import type { AiPermissionMode } from '@/services/types'
import type { AgentRunStreamState } from '@/lib/agentRunStream'
import { humanizeWorkflowField } from '@/lib/flowDisplay'
import { parseJsonObject } from '@/lib/flowGraph'

/**
 * Flow run dialog (modal): renders the workflow's input schema as a friendly
 * form, then starts the run and shows the live plan-execute stream (steps,
 * results, summary) or the error state.
 */

type InputValue = string | number | boolean | Record<string, unknown> | unknown[] | undefined

export interface FlowRunStartPayload {
  inputs: Record<string, unknown>
  permissionMode: AiPermissionMode
}

interface SchemaProperty {
  type?: string
  title?: string
  description?: string
  enum?: unknown[]
  default?: unknown
}

export function FlowRunDialog(props: {
  open: boolean
  workflowTitle: string
  nodeCount: number
  inputSchemaText: string
  /** Live run stream state; null while no run has been started from this dialog. */
  run: AgentRunStreamState | null
  onClose: () => void
  onRun: (payload: FlowRunStartPayload) => void
  onCancel: () => void
  onApprove: () => void
}) {
  const { t } = useTranslation()
  const [values, setValues] = useState<Record<string, InputValue>>({})
  const [permissionMode, setPermissionMode] = useState<AiPermissionMode>('ask-for-approval')

  const fields = useMemo(() => {
    const schema = parseJsonObject(props.inputSchemaText)
    const properties = (schema?.properties ?? {}) as Record<string, SchemaProperty>
    const required = new Set(Array.isArray(schema?.required) ? (schema.required as string[]) : [])
    return Object.entries(properties).map(([name, property]) => ({
      name,
      property,
      required: required.has(name),
    }))
  }, [props.inputSchemaText])

  // Seed defaults each time the dialog opens fresh (no run in flight).
  useEffect(() => {
    if (!props.open || props.run) return
    const seeded: Record<string, InputValue> = {}
    for (const field of fields) {
      const property = field.property
      if (property.default !== undefined && property.default !== null) {
        seeded[field.name] = property.default as InputValue
      } else if (property.type === 'boolean') {
        seeded[field.name] = false
      } else {
        seeded[field.name] = ''
      }
    }
    setValues(seeded)
  }, [props.open, props.run, fields])

  const closeRef = useRef(props.onClose)
  closeRef.current = props.onClose

  useEffect(() => {
    if (!props.open) return
    const onKeydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation()
        closeRef.current()
      }
    }
    window.addEventListener('keydown', onKeydown, true)
    return () => window.removeEventListener('keydown', onKeydown, true)
  }, [props.open])

  if (!props.open) return null

  const showStream = props.run !== null
  const missing = fields
    .filter((field) => field.required && !isConfigured(values[field.name]))
    .map((field) => field.property.title || humanizeWorkflowField(field.name))

  return (
    <div className="flow-modal-backdrop" role="presentation" onClick={props.onClose}>
      <section
        className="flow-run-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t('agent.testRun')}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flow-run-dialog__icon"><Play size={16} /></div>
        <div className="flow-run-dialog__heading">
          <h2>{showStream ? t('agent.runPanel') : t('agent.testRun')}</h2>
          <p>{props.workflowTitle} · {props.nodeCount} {t('agent.nodes')}</p>
        </div>
        <button className="cx-iconbtn cx-iconbtn--sm flow-run-dialog__close" aria-label={t('flows.close')} onClick={props.onClose}>
          <X size={16} />
        </button>

        {showStream ? (
          <RunStream run={props.run!} onCancel={props.onCancel} onClose={props.onClose} onApprove={props.onApprove} />
        ) : (
          <>
            {fields.length ? (
              <div className="flow-run-form">
                {fields.map(({ name, property, required }) => (
                  <label key={name} className="flow-field">
                    <span>
                      {property.title || humanizeWorkflowField(name)}
                      {required ? <em> *</em> : null}
                    </span>
                    {property.description ? <small>{property.description}</small> : null}
                    <RunInput
                      name={name}
                      property={property}
                      value={values[name]}
                      onChange={(value) => setValues((current) => ({ ...current, [name]: value }))}
                    />
                  </label>
                ))}
              </div>
            ) : (
              <div className="cx-muted flow-config-empty">{t('agent.noRunInputs')}</div>
            )}

            <label className="flow-field">
              <span>{t('agent.permissionMode')}</span>
              <select
                className="cx-select"
                value={permissionMode}
                onChange={(event) => setPermissionMode(event.target.value as AiPermissionMode)}
              >
                <option value="ask-for-approval">{t('aichat.permissionAsk')}</option>
                <option value="approve-for-me">{t('aichat.permissionAuto')}</option>
                <option value="full-access">{t('aichat.permissionFullAccess')}</option>
              </select>
            </label>
            <small className="cx-muted">{t('agent.runInputsHint')}</small>
            {missing.length > 0 && (
              <div className="cx-alert cx-alert--error">
                <span className="cx-alert__body">{t('agent.missingRunInputs', { names: missing.join(', ') })}</span>
              </div>
            )}
            <div className="flow-run-dialog__actions">
              <button className="cx-btn cx-btn--outline" onClick={props.onClose}>{t('common.cancel')}</button>
              <button
                className="flow-run-button"
                disabled={missing.length > 0}
                onClick={() => props.onRun({
                  inputs: Object.fromEntries(Object.entries(values)
                    .filter(([, value]) => isConfigured(value))),
                  permissionMode,
                })}
              >
                <Play size={14} /> {t('agent.startRun')}
              </button>
            </div>
          </>
        )}
      </section>
    </div>
  )
}

function isConfigured(value: InputValue): boolean {
  if (value === undefined || value === null) return false
  if (typeof value === 'string') return value.trim().length > 0
  if (Array.isArray(value)) return value.length > 0
  if (typeof value === 'object') return Object.keys(value as Record<string, unknown>).length > 0
  return true
}

function RunInput(props: {
  name: string
  property: SchemaProperty
  value: InputValue
  onChange: (value: InputValue) => void
}) {
  const { t } = useTranslation()
  const { property } = props
  const type = property.type
  if (type === 'boolean') {
    return (
      <label className="flow-boolean-input">
        <input
          type="checkbox"
          checked={Boolean(props.value)}
          onChange={(event) => props.onChange(event.target.checked)}
        />
        <span>{t('agent.enabled')}</span>
      </label>
    )
  }
  if (type === 'object' || type === 'array') {
    return (
      <textarea
        className="cx-textarea mono"
        rows={3}
        placeholder={type === 'array' ? t('agent.arrayInputPlaceholder') : t('agent.objectInputPlaceholder')}
        value={typeof props.value === 'string' ? props.value : JSON.stringify(props.value ?? (type === 'array' ? [] : {}), null, 2)}
        onChange={(event) => props.onChange(event.target.value)}
        onBlur={(event) => {
          try {
            props.onChange(JSON.parse(event.target.value || (type === 'array' ? '[]' : '{}')))
          } catch {
            /* keep the raw text until it parses */
          }
        }}
      />
    )
  }
  if (property.enum?.length) {
    return (
      <select
        className="cx-select"
        value={String(props.value ?? '')}
        onChange={(event) => {
          const raw = event.target.value
          let match: InputValue | undefined
          if (property.enum) {
            match = property.enum.find((option) => String(option) === raw) as InputValue | undefined
          }
          props.onChange(match !== undefined ? match : raw)
        }}
      >
        <option value="">{t('agent.notSet')}</option>
        {property.enum.map((option) => (
          <option key={String(option)} value={String(option)}>{String(option)}</option>
        ))}
      </select>
    )
  }
  return (
    <input
      className="cx-input"
      type={type === 'number' || type === 'integer' ? 'number' : 'text'}
      placeholder={property.description || t('agent.enterValue')}
      value={props.value === undefined || props.value === null || typeof props.value === 'object'
        ? ''
        : String(props.value)}
      onChange={(event) => {
        if (type === 'number' || type === 'integer') {
          props.onChange(event.target.value === '' ? undefined : Number(event.target.value))
        } else {
          props.onChange(event.target.value)
        }
      }}
    />
  )
}

function RunStream(props: {
  run: AgentRunStreamState
  onCancel: () => void
  onClose: () => void
  onApprove: () => void
}) {
  const { t } = useTranslation()
  const { run } = props
  return (
    <div className="flow-run-stream">
      <div className={`flow-run-status flow-run-status--${run.status}`}>
        {run.status === 'complete'
          ? <><CheckCircle2 size={15} /> {t('agent.completed')}</>
          : run.status === 'error' || run.status === 'cancelled'
            ? <><AlertCircle size={15} /> {run.status === 'error' ? (run.errorMsg || t('agent.failed')) : t('agent.cancel')}</>
            : <><Loader2 size={15} className="flow-spin" /> {t('agent.running')}</>}
      </div>
      {run.plan?.goal ? <p className="cx-muted flow-run-goal">{run.plan.goal}</p> : null}
      <ol className="flow-run-steps">
        {run.stepList.map((step) => (
          <li key={step.index} className={`flow-run-step flow-run-step--${step.status}`}>
            <span className="flow-run-step__head">
              <strong>{step.toolName || step.description || `#${step.index + 1}`}</strong>
              <small>{step.status}</small>
            </span>
            {step.description && step.description !== step.toolName ? (
              <span className="flow-run-step__desc">{step.description}</span>
            ) : null}
            {run.stepResults.get(step.index) ? (
              <pre className="flow-run-step__result mono">{run.stepResults.get(step.index)}</pre>
            ) : null}
          </li>
        ))}
        {!run.stepList.length && (
          <li className="cx-muted flow-run-steps__empty">
            <Loader2 size={14} className="flow-spin" /> {t('agent.running')}
          </li>
        )}
      </ol>
      {run.summary ? <div className="cx-alert cx-alert--success">
        <span className="cx-alert__body">{run.summary}</span>
      </div> : null}
      {run.status === 'error' && !run.errorMsg ? (
        <div className="cx-alert cx-alert--error"><span className="cx-alert__body">{t('agent.failed')}</span></div>
      ) : null}
      {run.awaitingApproval && (
        <div className="cx-alert cx-alert--warn">
          <span className="cx-alert__body">{t('aichat.permissionQuestion')}</span>
        </div>
      )}
      <div className="flow-run-dialog__actions">
        {run.busy && run.awaitingApproval && (
          <button className="cx-btn cx-btn--primary" onClick={props.onApprove}>
            <CheckCircle2 size={14} /> {t('flows.chatApprove')}
          </button>
        )}
        {run.busy
          ? <button className="cx-btn cx-btn--outline" onClick={props.onCancel}><Square size={13} /> {t('agent.cancel')}</button>
          : <button className="cx-btn cx-btn--outline" onClick={props.onClose}>{t('common.close')}</button>}
      </div>
    </div>
  )
}
