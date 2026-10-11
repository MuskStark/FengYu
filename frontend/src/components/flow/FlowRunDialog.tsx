import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertCircle, CheckCircle2, FileSearch, FolderSearch, Loader2, Play, Square, X } from 'lucide-react'
import type { ActiveFileEntry, AgentRunFile, AiPermissionMode } from '@/services/types'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import type { AgentRunStreamState } from '@/lib/agentRunStream'
import { humanizeWorkflowField } from '@/lib/flowDisplay'
import { parseJsonObject } from '@/lib/flowGraph'
import { createRunFileGrantLedger } from '@/lib/runFileGrantLedger'

/**
 * Flow run dialog (modal): renders the workflow's input schema as a friendly
 * form — file-class inputs become pickers whose grants travel with the run
 * (`@file:<name>` placeholders in args, per-plugin refs under `files`) — then
 * starts the run and shows the live plan-execute stream or the error state.
 */

type InputValue = string | number | boolean | Record<string, unknown> | unknown[] | undefined

export interface FlowRunStartPayload {
  inputs: Record<string, unknown>
  permissionMode: AiPermissionMode
  /** File-class input grants minted by this dialog's pickers. */
  files?: AgentRunFile[]
}

interface SchemaProperty {
  type?: string
  title?: string
  description?: string
  enum?: unknown[]
  default?: unknown
  format?: string
  'x-fengyu-file-access'?: 'read' | 'read-write'
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
  /** Picked-file grants per input name (dialog-owned until the run takes them). */
  const [fileRefs, setFileRefs] = useState<Record<string, ActiveFileEntry[]>>({})
  const [fileNames, setFileNames] = useState<Record<string, string>>({})
  const [fileErrors, setFileErrors] = useState<Record<string, string | null>>({})
  /** Ledger session id — pickers capture it at pick start, accept() guards staleness. */
  const [grantSession, setGrantSession] = useState(0)
  const ledgerRef = useRef<ReturnType<typeof createRunFileGrantLedger> | null>(null)
  const ledger = useMemo(() => ledgerRef.current ?? (ledgerRef.current = createRunFileGrantLedger((entry) => {
    void services.chat.revokeAiFile(entry.pluginId, entry.ref.id).catch(() => {
      // Revocation is best-effort cleanup; the host reaps orphans eventually.
    })
  })), [])

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

  // Seed defaults each time the dialog opens fresh (no run in flight); a fresh
  // open also resets grant ownership — earlier unsubmitted picks are revoked.
  useEffect(() => {
    if (!props.open || props.run) return
    setGrantSession(ledger.beginSession())
    setFileRefs({})
    setFileNames({})
    setFileErrors({})
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
    // The ledger is a ref-mounted singleton; re-seeding is keyed by open/run/fields.
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  // Close/unmount without submitting: still-owned grants go back (the captured
  // props.run reflects the submit state at mount — ownership tracking is the
  // ledger's job, not this effect's deps).
  useEffect(() => () => {
    if (!props.run) ledger.releaseRemaining()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (!props.open) return null

  const showStream = props.run !== null
  const missing = fields
    .filter((field) => field.required && !isConfigured(values[field.name]))
    .map((field) => field.property.title || humanizeWorkflowField(field.name))

  const setFileValue = (name: string, refs: ActiveFileEntry[] | null, displayName: string | undefined,
    sessionAtStart: number) => {
    if (refs === null) {
      ledger.clear(name)
      setFileRefs((current) => { const next = { ...current }; delete next[name]; return next })
      setFileNames((current) => { const next = { ...current }; delete next[name]; return next })
      setValues((current) => ({ ...current, [name]: '' }))
      return
    }
    // A pick resolving after the dialog reopened (or after submit) hands its
    // grants straight back — the form state stays untouched.
    if (!ledger.accept(sessionAtStart, name, refs)) return
    setFileRefs((current) => ({ ...current, [name]: refs }))
    if (displayName) setFileNames((current) => ({ ...current, [name]: displayName }))
    setValues((current) => ({ ...current, [name]: `@file:${name}` }))
  }

  const startRun = () => {
    // File-class inputs: picked-file grants travel with the run. Ownership
    // leaves the dialog with this submit — the created run's terminal cleanup
    // now owns every revoke.
    const files: AgentRunFile[] = Object.entries(fileRefs).map(([name, refs]) => ({ name, refs }))
    ledger.markTransferred()
    props.onRun({
      inputs: Object.fromEntries(Object.entries(values)
        .filter(([, value]) => isConfigured(value))),
      permissionMode,
      files,
    })
  }

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
                      pickedName={fileNames[name]}
                      error={fileErrors[name] ?? null}
                      grantSession={grantSession}
                      onChange={(value) => setValues((current) => ({ ...current, [name]: value }))}
                      onPick={(refs, displayName, sessionAtStart) =>
                        setFileValue(name, refs, displayName, sessionAtStart)}
                      onClear={() => setFileValue(name, null, undefined, ledger.session())}
                      onError={(message) => setFileErrors((current) => ({ ...current, [name]: message }))}
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
                onClick={startRun}
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
  /** Display name of the picked file/directory (grant-backed inputs). */
  pickedName?: string
  error: string | null
  /** Ledger session id at render time — captured by pickers at pick start. */
  grantSession: number
  onChange: (value: InputValue) => void
  onPick: (refs: ActiveFileEntry[] | null, displayName: string | undefined, sessionAtStart: number) => void
  onClear: () => void
  onError: (message: string | null) => void
}) {
  const { t } = useTranslation()
  const { property } = props
  const type = property.type
  if (property.format === 'fengyu-file' || property.format === 'fengyu-directory') {
    return (
      <FileRunInput
        name={props.name}
        directory={property.format === 'fengyu-directory'}
        writable={property['x-fengyu-file-access'] === 'read-write'}
        picked={typeof props.value === 'string' && props.value.startsWith('@file:')}
        pickedName={props.pickedName}
        error={props.error}
        grantSession={props.grantSession}
        onPick={props.onPick}
        onClear={props.onClear}
        onError={props.onError}
      />
    )
  }
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

/**
 * File-class run input: a picker that mints grants. Desktop dialogs return
 * native paths granted via /api/ai/files/native; the web falls back to uploads
 * (webkitdirectory for directories). Grants are dialog-owned until submit.
 */
function FileRunInput(props: {
  name: string
  directory: boolean
  writable: boolean
  picked: boolean
  pickedName?: string
  error: string | null
  grantSession: number
  onPick: (refs: ActiveFileEntry[] | null, displayName: string | undefined, sessionAtStart: number) => void
  onClear: () => void
  onError: (message: string | null) => void
}) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const storeDirectory = async (refsPromise: Promise<ActiveFileEntry[]>, displayName: string) => {
    const sessionAtStart = props.grantSession
    setBusy(true)
    props.onError(null)
    try {
      const refs = await refsPromise
      if (!refs.length) throw new Error(t('aichat.fileNeedsPlugin'))
      props.onPick(refs, displayName, sessionAtStart)
    } catch (e) {
      props.onError(e instanceof Error ? e.message : t('aichat.attachDirectoryFailed'))
    } finally {
      setBusy(false)
    }
  }

  const storeFile = async (refsPromise: Promise<ActiveFileEntry[]>, displayName: string) => {
    const sessionAtStart = props.grantSession
    setBusy(true)
    props.onError(null)
    try {
      const refs = await refsPromise
      if (!refs.length) throw new Error(t('aichat.fileNeedsPlugin'))
      props.onPick(refs, displayName, sessionAtStart)
    } catch (e) {
      props.onError(e instanceof Error ? e.message : t('agent.failed'))
    } finally {
      setBusy(false)
    }
  }

  const pick = async () => {
    if (busy) return
    const platform = getPlatform()
    if (props.directory) {
      if (platform.kind === 'desktop') {
        const path = await platform.pickDirectory()
        if (!path) return
        const displayName = path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || path
        await storeDirectory(services.chat.grantAiNativePath(path, 'directory', props.writable), displayName)
        return
      }
      const input = document.createElement('input')
      input.type = 'file'
      input.setAttribute('webkitdirectory', '')
      input.multiple = true
      input.onchange = () => {
        const files = input.files ? Array.from(input.files) : []
        if (!files.length) return
        const displayName = (files[0] as File & { webkitRelativePath?: string }).webkitRelativePath?.split('/')[0]
          || files[0]!.name
        void storeDirectory(services.chat.uploadAiDirectory(files, props.writable), displayName)
      }
      input.click()
      return
    }
    if (platform.kind === 'desktop') {
      const path = await platform.pickFile()
      if (!path) return
      const displayName = path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || path
      await storeFile(services.chat.grantAiNativePath(path, 'file', false), displayName)
      return
    }
    fileInputRef.current?.click()
  }

  return (
    <div className="flow-run-file">
      <input
        ref={fileInputRef}
        type="file"
        className="flow-run-file__native"
        tabIndex={-1}
        aria-hidden="true"
        onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (!file) return
          void storeFile(services.chat.uploadAiFile(file), file.name)
        }}
      />
      <button
        type="button"
        className={`flow-run-file__pick${props.picked ? ' flow-run-file__pick--done' : ''}`}
        onClick={() => void pick()}
        disabled={busy}
      >
        {busy
          ? <Loader2 size={14} className="flow-spin" />
          : props.directory ? <FolderSearch size={14} /> : <FileSearch size={14} />}
        {props.picked && props.pickedName
          ? props.pickedName
          : props.directory ? t('flows.pickDirectory') : t('flows.pickFile')}
      </button>
      {props.picked && (
        <button type="button" className="cx-iconbtn cx-iconbtn--sm" title={t('common.clear')} onClick={props.onClear}>
          <X size={13} />
        </button>
      )}
      {props.error && <small className="flow-run-file__error">{props.error}</small>}
    </div>
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
        {run.stepList.map((step) => {
          const timing = run.stepTimings.get(step.index)
          const durationMs = timing
            ? (timing.endedAt ?? Date.now()) - timing.startedAt
            : undefined
          return (
            <li key={step.index} className={`flow-run-step flow-run-step--${step.status}`}>
              <span className="flow-run-step__head">
                <strong>{step.toolName || step.description || `#${step.index + 1}`}</strong>
                <small>
                  {step.status}
                  {durationMs !== undefined && step.status !== 'pending'
                    ? ` · ${(durationMs / 1000).toFixed(1)}s`
                    : ''}
                </small>
              </span>
              {step.description && step.description !== step.toolName ? (
                <span className="flow-run-step__desc">{step.description}</span>
              ) : null}
              {run.stepResults.get(step.index) ? (
                <pre className="flow-run-step__result mono">{run.stepResults.get(step.index)}</pre>
              ) : null}
            </li>
          )
        })}
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
