import { useEffect, useMemo, useRef, useState } from 'react'
import type { FileRef } from '@infinia/plugin-sdk'
import {
  EmptyState,
  ErrorState,
  GhostButton,
  GlowingEffect,
  GoldButton,
  Meteors,
  PermissionNotice,
  Progress,
  StatusChip,
  Terminal,
  isPermissionError,
  useFengYuClient,
  useFengYuI18n,
} from '@infinia/plugin-ui'
import { IconCheck, IconFolderOpen, IconHammer, IconPlayerStop } from '@tabler/icons-react'
import { checked, createPluginRpc } from '../rpc'
import { readJobSnapshot, type PanelActivity, type UiJobStatus } from '../jobState'
import { buildWorkerConfig, configForm, DEFAULT_FORM, type ConfigForm, type WorkerConfig } from '../configState'

type Translate = (key: string, ...args: (string | number)[]) => string

interface Props {
  project: FileRef | null
  onProject: (project: FileRef | null) => void
  toast: (msg: string) => void
  onActivity: (activity: PanelActivity | null) => void
}

/** Common pinned versions offered by the segmented control (any value stays editable). */
const COMMON_PYTHON_VERSIONS = ['3.12.10', '3.11.9', '3.10.13']

/** Package name of one requirements line ("numpy==1.26.4" → "numpy"). */
function dependencyName(line: string): string {
  return line.split(/[<>=!~;#\s]/)[0] ?? line
}

/**
 * Project picker around `files.workspaceDirectory` (read-write grant). The kit's
 * DirectoryPicker wraps `files.inputDirectory` (read-only) and only surfaces the
 * directory NAME — the worker needs the granted FileRef so the host can resolve it
 * to an absolute path and so saves/builds stay inside the writable grant.
 */
function WorkspacePicker({ value, onSelect, label, disabled }: {
  value?: FileRef | null
  onSelect: (ref: FileRef | null) => void
  label: string
  disabled?: boolean
}) {
  const client = useFengYuClient()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<{ message: string; permission: boolean } | null>(null)

  const pick = async () => {
    if (loading || disabled) return
    setError(null)
    setLoading(true)
    try {
      onSelect(await client.files.workspaceDirectory())
    } catch (e) {
      const err = e instanceof Error ? e : new Error(String(e))
      setError({ message: err.message, permission: isPermissionError(err) })
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="opb-row">
      {value ? (
        <code className="opb-path" title={value.name}>{value.name}</code>
      ) : null}
      <GhostButton disabled={loading || disabled} onClick={pick} aria-label={label} title={label} className={value ? '' : 'opb-grow'}>
        <IconFolderOpen size={14} stroke={1.6} />
        {loading ? '…' : label}
      </GhostButton>
      {error && !error.permission ? (
        <ErrorState className="opb-wfull" title="picker" message={error.message} onRetry={pick} />
      ) : null}
      {error && error.permission ? <PermissionNotice message={error.message} /> : null}
    </div>
  )
}

/** Stage dot for the build status timeline (done ✓ / running pulse / pending). */
function StageDot({ state }: { state: 'done' | 'running' | 'pending' }) {
  if (state === 'done') {
    return (
      <span className="opb-dot--done">
        <IconCheck size={11} stroke={2.4} />
      </span>
    )
  }
  return state === 'running' ? <span className="opb-dot--running" /> : <span className="opb-dot--pending" />
}

export default function ProjectPanel({ project, onProject, toast, onActivity }: Props) {
  const client = useFengYuClient()
  const { t } = useFengYuI18n()

  // Typed RPC client generated from manifest rpc.methods. Path inputs (projectDir) are FileRef
  // objects the host resolves to absolute path strings before the worker receives them, so the
  // `as unknown as string` casts below encode that host-side resolution at the call boundary.
  const rpc = useMemo(() => createPluginRpc(client), [client])
  // Abort all in-flight RPC when the panel unmounts (transport-cancel), so navigating away during a
  // build/config-load does not leave a dangling worker call. Domain job cancel is separate (build.cancel).
  const abortRef = useRef<AbortController | null>(null)
  const signal = () => (abortRef.current ??= new AbortController()).signal
  // Async callbacks (poll interval) outlive renders — read the translator through a ref.
  const tRef = useRef<Translate>(t)
  tRef.current = t

  // ---- shared build/job state ------------------------------------------------
  const [logs, setLogs] = useState<string[]>([])
  const logsRef = useRef<string[]>([])
  const [status, setStatus] = useState<UiJobStatus>('idle')
  const [building, setBuilding] = useState(false)
  const jobIdRef = useRef<string | null>(null)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const [jobSeq, setJobSeq] = useState(0) // bumped per started job → Terminal re-key

  // ---- config form state ------------------------------------------------------
  const [requirements, setRequirements] = useState('')
  const [form, setForm] = useState<ConfigForm>({ ...DEFAULT_FORM })
  const [loading, setLoading] = useState(false)
  const [saved, setSaved] = useState(false) // whether the current form has been persisted to disk
  const loadVersionRef = useRef(0)

  // ---- stepper state ------------------------------------------------------------
  const [step, setStep] = useState(1) // 1=config, 2=build, 3=verify

  function errorText(error: unknown): string {
    return error instanceof Error && error.message ? error.message : tRef.current('opb.common.error')
  }

  function stopPolling() {
    if (pollRef.current) clearInterval(pollRef.current)
    pollRef.current = null
  }

  /** Reset all transient state when the project changes. */
  function resetState() {
    stopPolling()
    logsRef.current = []
    setLogs([])
    setStatus('idle')
    setBuilding(false)
    jobIdRef.current = null
    setRequirements('')
    setForm({ ...DEFAULT_FORM })
    setSaved(false)
    setStep(1)
  }

  async function selectProject(next: FileRef | null) {
    if (!next) return
    resetState()
    onProject(next)
    // Ensure the project skeleton (config.json, requirements.txt, README.md)
    // exists before we try to load it — InitService is idempotent (skips files
    // that already exist), so this is safe for re-selecting an existing project.
    try {
      await checked(await rpc.init({ projectDir: next as unknown as string }, { signal: signal() }))
    } catch (error) {
      // init is best-effort: if it fails (e.g. read-only), still attempt to load
      // whatever config is already on disk, and surface the issue.
      toast(errorText(error))
    }
    await loadConfig(next)
  }

  // ---- config step ------------------------------------------------------------
  async function loadConfig(projectOverride?: FileRef) {
    const version = ++loadVersionRef.current
    const currentProject = projectOverride ?? project
    setForm({ ...DEFAULT_FORM })
    setRequirements('')
    setSaved(true) // assume clean until edited
    if (!currentProject) return
    setLoading(true)
    try {
      const req = await checked(await rpc.requirementsGet({ projectDir: currentProject as unknown as string }, { signal: signal() }))
      if (version !== loadVersionRef.current) return
      setRequirements(String(req.text ?? ''))
      const cfg = await checked(await rpc.configGet({ projectDir: currentProject as unknown as string, session: 'ui' }, { signal: signal() }))
      if (version !== loadVersionRef.current) return
      const workerCfg = cfg.config as WorkerConfig | undefined
      setForm(configForm(workerCfg))
    } catch (error) {
      if (version === loadVersionRef.current) toast(errorText(error))
    } finally {
      if (version === loadVersionRef.current) setLoading(false)
    }
  }

  async function save() {
    const currentProject = project
    if (!currentProject) {
      // Should not happen (button is disabled), but never silently bail — the
      // original saveConfig bug was a silent `if (!project) return` here.
      toast(t('opb.project.empty'))
      return
    }
    setLoading(true)
    try {
      await checked(await rpc.requirementsSave({ projectDir: currentProject as unknown as string, text: requirements }, { signal: signal() }))
      // Send the FULL config so the worker's merge onto config.json does not reset
      // repository/pkg/bundle sections to Java defaults. depPlatforms is preserved
      // on disk by the worker merge (the typed config record omits it by design).
      const config = buildWorkerConfig(form, /* preserve depPlatforms */ undefined)
      await checked(await rpc.configSave({ projectDir: currentProject as unknown as string, session: 'ui', config }, { signal: signal() }))
      setSaved(true)
      toast(t('opb.config.saved'))
    } catch (error) {
      toast(errorText(error))
    } finally {
      setLoading(false)
    }
  }

  /** Patch the form and mark it dirty so the UI can warn about unsaved changes. */
  function editForm(patch: Partial<ConfigForm>) {
    setForm((previous) => ({ ...previous, ...patch }))
    setSaved(false)
  }

  // ---- build step ---------------------------------------------------------------
  async function pollStatus() {
    const id = jobIdRef.current
    if (!id) return
    try {
      const s = await rpc.buildStatus({ jobId: id, cursor: logsRef.current.length }, { signal: signal() })
      const snapshot = readJobSnapshot(s)
      if (snapshot.logs.length) {
        logsRef.current = logsRef.current.concat(snapshot.logs)
        setLogs(logsRef.current)
      }
      setStatus(snapshot.status)
      if (!snapshot.ok) {
        stopPolling()
        setBuilding(false)
        jobIdRef.current = null
        toast(snapshot.summary)
        return
      }
      if (snapshot.done) {
        setBuilding(false)
        stopPolling()
        jobIdRef.current = null
        toast(snapshot.error || snapshot.status === 'failed'
          ? tRef.current('opb.build.failed')
          : tRef.current('opb.build.completed', tRef.current(`opb.build.status.${snapshot.status}`)))
      }
    } catch (error) {
      stopPolling()
      setBuilding(false)
      setStatus('error')
      toast(errorText(error))
    }
  }

  async function startBuild() {
    if (!project) return
    logsRef.current = []
    setLogs([])
    setStatus('starting')
    setBuilding(true)
    setJobSeq((seq) => seq + 1)
    try {
      const res = await checked(await rpc.buildStart({ projectDir: project as unknown as string, session: 'ui' }, { signal: signal() }))
      const id = res.jobId
      if (!id) {
        setStatus('error')
        setBuilding(false)
        toast(t('opb.build.failed'))
        return
      }
      jobIdRef.current = id
      pollRef.current = setInterval(pollStatus, 800)
    } catch (error) {
      setStatus('error')
      setBuilding(false)
      toast(errorText(error))
    }
  }

  async function cancel() {
    const id = jobIdRef.current
    if (!id) return
    try {
      await checked(await rpc.buildCancel({ jobId: id }, { signal: signal() }))
      setBuilding(false)
      stopPolling()
      jobIdRef.current = null
      setStatus('cancelled')
    } catch (error) {
      toast(errorText(error))
    }
  }

  async function doPackage() {
    if (!project) return
    setBuilding(true)
    try {
      const res = await checked(await rpc.package({ projectDir: project as unknown as string, session: 'ui' }, { signal: signal() }))
      const zip = res.zipPath ?? ''
      toast(t('opb.build.packaged', zip))
    } catch (error) {
      toast(errorText(error))
    } finally {
      setBuilding(false)
    }
  }

  // ---- verify step ----------------------------------------------------------------
  async function verify() {
    if (!project) return
    setBuilding(true)
    try {
      await checked(await rpc.verify({ projectDir: project as unknown as string, session: 'ui', scope: 'ALL' }, { signal: signal() }))
      toast(t('opb.build.verifyOk'))
    } catch (error) {
      toast(errorText(error))
    } finally {
      setBuilding(false)
    }
  }

  // The build step is enabled once the config has been saved at least once for
  // the current project. This guides the user through configure → build.
  const canBuild = saved && !building

  const workflowSteps = [
    { value: 1, title: t('opb.step.config') },
    { value: 2, title: t('opb.step.build') },
    { value: 3, title: t('opb.step.verify') },
  ]

  function canVisitStep(value: number): boolean {
    return value === 1 || saved
  }

  function goToStep(value: number) {
    if (canVisitStep(value) && !building && !loading) setStep(value)
  }

  // Report job activity to the shared header/status bar (cleared on unmount).
  useEffect(() => {
    onActivity({
      running: building,
      tone: building || status === 'starting' || status === 'running'
        ? 'warning'
        : status === 'done'
          ? 'success'
          : status === 'failed' || status === 'error'
            ? 'danger'
            : 'idle',
      label: t(`opb.build.status.${status}`),
      cancel: building ? () => { void cancel() } : undefined,
    })
    return () => onActivity(null)
  }, [building, status, t, onActivity])

  useEffect(() => () => {
    stopPolling()
    abortRef.current?.abort()
  }, [])

  // ---- derived summary values (config column) --------------------------------------
  const versionChoices = form.pythonVersion && !COMMON_PYTHON_VERSIONS.includes(form.pythonVersion)
    ? [form.pythonVersion, ...COMMON_PYTHON_VERSIONS]
    : COMMON_PYTHON_VERSIONS
  const platforms = form.platformsCsv.split(',').map((s) => s.trim()).filter(Boolean)
  const dependencies = requirements.split('\n').map((s) => s.trim()).filter(Boolean)

  const stageState = (n: number): 'done' | 'running' | 'pending' => {
    if (n === 1) return 'done' // config must be saved before a build can run
    if (n === 2) return building ? 'running' : status === 'done' ? 'done' : 'pending'
    return !building && status === 'done' ? 'done' : 'pending'
  }

  // The Aceternity Terminal is decorative-interactive: it replays the latest job
  // log snapshot as typed output, re-keyed per job (and once more on completion).
  const terminalKey = `${jobSeq}-${building ? 'live' : status}`
  const terminalLines = logs.length ? logs.slice(-24) : [t('opb.build.logEmpty')]

  if (!project) {
    return (
      <div className="opb-grid" style={{ gridTemplateColumns: 'minmax(0, 1fr)', alignItems: 'center' }}>
        <div className="opb-col" style={{ maxWidth: 520, margin: '0 auto', overflowY: 'auto' }}>
          <EmptyState
            icon={<IconFolderOpen size={20} stroke={1.6} />}
            title={t('opb.project.empty')}
            message={t('opb.project.openPrompt')}
            action={<WorkspacePicker label={t('opb.project.open')} onSelect={selectProject} />}
          />
        </div>
      </div>
    )
  }

  return (
    <div className="opb-grid">
      {/* 左：构建配置列 */}
      <section className="opb-col opb-col--scroll" aria-label={t('opb.config.title')}>
        <div className="opb-card">
          <div className="opb-card-head">
            <h3 className="opb-card-title">{t('opb.nav.project')}</h3>
            {saved ? <StatusChip tone="success">{t('opb.config.saved')}</StatusChip> : <StatusChip tone="warning">{t('opb.config.unsaved')}</StatusChip>}
          </div>
          <WorkspacePicker value={project} onSelect={selectProject} label={t('opb.project.change')} disabled={building || loading} />
        </div>

        <div className="opb-card">
          <div className="opb-card-head">
            <h3 className="opb-card-title">{t('opb.config.pythonVersion')}</h3>
          </div>
          <div className="opb-segments" role="group" aria-label={t('opb.config.pythonVersion')}>
            {versionChoices.map((v) => (
              <button
                key={v}
                type="button"
                className="opb-segment"
                aria-pressed={form.pythonVersion === v}
                onClick={() => editForm({ pythonVersion: v })}
              >
                {v}
              </button>
            ))}
          </div>
          <input
            className="opb-input opb-mt"
            value={form.pythonVersion}
            placeholder={t('opb.config.pythonHint')}
            onChange={(e) => editForm({ pythonVersion: e.target.value })}
            aria-label={t('opb.config.pythonVersion')}
          />
        </div>

        <div className="opb-card">
          <div className="opb-card-head">
            <h3 className="opb-card-title">{t('opb.config.platforms')}</h3>
            <span className="opb-chip">{platforms.length}</span>
          </div>
          <div className="opb-chips">
            {platforms.map((p) => (
              <span key={p} className="opb-chip opb-chip--gold">{p}</span>
            ))}
          </div>
          <input
            className="opb-input opb-mt"
            value={form.platformsCsv}
            placeholder={t('opb.config.platformsHint')}
            onChange={(e) => editForm({ platformsCsv: e.target.value })}
            aria-label={t('opb.config.platforms')}
          />
        </div>

        <div className="opb-card opb-card--fill" style={{ overflow: 'hidden' }}>
          <div className="opb-card-head">
            <h3 className="opb-card-title">{t('opb.config.requirements')}</h3>
            <span className="opb-chip">{dependencies.length}</span>
          </div>
          <div className="opb-chips" style={{ overflow: 'hidden' }}>
            {dependencies.slice(0, 12).map((d) => (
              <span key={d} className="opb-chip">{dependencyName(d)}</span>
            ))}
            {dependencies.length > 12 ? <span className="opb-chip">…{dependencies.length - 12}</span> : null}
          </div>
        </div>

        <GoldButton className="opb-wfull" disabled={loading || building} onClick={save}>
          <IconHammer size={15} stroke={1.7} />
          {loading ? '…' : t('opb.config.save')}
        </GoldButton>
      </section>

      {/* 右：工作流 + 构建状态 + 控制台 */}
      <section className="opb-col opb-col--scroll">
        <nav className="opb-steps" aria-label={t('opb.project.workflow')}>
          {workflowSteps.map((item) => (
            <button
              key={item.value}
              type="button"
              className="opb-step"
              aria-current={step === item.value ? 'step' : undefined}
              disabled={!canVisitStep(item.value) || building || loading}
              onClick={() => goToStep(item.value)}
            >
              <span className="opb-step-num">{item.value}</span>
              <span>{item.title}</span>
            </button>
          ))}
        </nav>

        {/* Step 1: configure dependencies */}
        {step === 1 ? (
          <>
            <div className="opb-card">
              <div className="opb-card-head">
                <h3 className="opb-card-title">{t('opb.config.requirements')}</h3>
              </div>
              <p className="opb-card-copy">{t('opb.config.requirementsHint')}</p>
              <textarea
                className="opb-input opb-textarea"
                rows={8}
                value={requirements}
                placeholder={t('opb.config.requirementsPlaceholder')}
                onChange={(e) => { setRequirements(e.target.value); setSaved(false) }}
                aria-label={t('opb.config.requirements')}
              />
            </div>

            <div className="opb-card">
              <div className="opb-card-head">
                <h3 className="opb-card-title">{t('opb.config.download')}</h3>
              </div>
              <div className="opb-check-grid">
                <label className="opb-check">
                  <input type="checkbox" checked={form.onlyBinary} onChange={(e) => editForm({ onlyBinary: e.target.checked })} />
                  {t('opb.config.onlyBinary')}
                </label>
                <label className="opb-check">
                  <input type="checkbox" checked={form.recursive} onChange={(e) => editForm({ recursive: e.target.checked })} />
                  {t('opb.config.recursive')}
                </label>
              </div>

              <details className="opb-details">
                <summary>{t('opb.config.advanced')}</summary>
                <div className="opb-details-body">
                  <input
                    className="opb-input"
                    value={form.output}
                    placeholder={t('opb.config.outputHint')}
                    onChange={(e) => editForm({ output: e.target.value })}
                    aria-label={t('opb.config.output')}
                  />
                  <input
                    className="opb-input"
                    value={form.wheelDir}
                    placeholder={t('opb.config.wheelDirHint')}
                    onChange={(e) => editForm({ wheelDir: e.target.value })}
                    aria-label={t('opb.config.wheelDir')}
                  />
                  <div className="opb-check-grid">
                    <label className="opb-check">
                      <input type="checkbox" checked={form.cache} onChange={(e) => editForm({ cache: e.target.checked })} />
                      {t('opb.config.cache')}
                    </label>
                    <label className="opb-check">
                      <input type="checkbox" checked={form.upgradePip} onChange={(e) => editForm({ upgradePip: e.target.checked })} />
                      {t('opb.config.upgradePip')}
                    </label>
                    <label className="opb-check">
                      <input type="checkbox" checked={form.installer} onChange={(e) => editForm({ installer: e.target.checked })} />
                      {t('opb.config.installer')}
                    </label>
                  </div>
                  <div className="opb-card-title">{t('opb.config.pkgTitle')}</div>
                  <div className="opb-check-grid">
                    <label className="opb-check">
                      <input type="checkbox" checked={form.zip} onChange={(e) => editForm({ zip: e.target.checked })} />
                      {t('opb.config.pkgZip')}
                    </label>
                    <label className="opb-check">
                      <input type="checkbox" checked={form.pkgSha256} onChange={(e) => editForm({ pkgSha256: e.target.checked })} />
                      {t('opb.config.pkgSha256')}
                    </label>
                    <label className="opb-check">
                      <input type="checkbox" checked={form.readme} onChange={(e) => editForm({ readme: e.target.checked })} />
                      {t('opb.config.pkgReadme')}
                    </label>
                  </div>
                </div>
              </details>
            </div>

            <div className="opb-actions opb-actions--end">
              <GoldButton disabled={!saved} onClick={() => setStep(2)}>{t('opb.common.next')}</GoldButton>
            </div>
          </>
        ) : null}

        {/* Step 2: download & package */}
        {step === 2 ? (
          <>
            <div className="opb-status-card">
              {building ? (
                <>
                  <GlowingEffect spread={60} borderWidth={1.5} glow disabled={false} />
                  <Meteors number={10} />
                </>
              ) : null}
              <div className="opb-status-main">
                <div className="opb-row">
                  <span style={{ fontSize: 14, fontWeight: 600 }}>{t('opb.build.title')}</span>
                  <span className="opb-chip">python-{form.pythonVersion}</span>
                  <span className="opb-chip">{platforms.length > 0 ? platforms.join(' · ') : form.platformsCsv}</span>
                </div>
                <p className="opb-card-copy">{t('opb.build.description')}</p>
                <div className="opb-actions">
                  <GoldButton disabled={!canBuild} onClick={startBuild}>
                    <IconHammer size={15} stroke={1.7} />
                    {t('opb.build.start')}
                  </GoldButton>
                  <GhostButton disabled={building} onClick={doPackage}>{t('opb.build.package')}</GhostButton>
                  {building ? (
                    <GhostButton className="opb-danger" onClick={cancel}>
                      <IconPlayerStop size={14} stroke={1.7} />
                      {t('opb.build.cancel')}
                    </GhostButton>
                  ) : (
                    <StatusChip
                      tone={status === 'done' ? 'success' : status === 'failed' || status === 'error' ? 'danger' : 'idle'}
                    >
                      {t(`opb.build.status.${status}`)}
                    </StatusChip>
                  )}
                </div>
                {building ? <Progress className="opb-mt" status="indeterminate" label={t(`opb.build.status.${status}`)} /> : null}
              </div>
              <div className="opb-stages">
                {workflowSteps.map((s) => {
                  const state = stageState(s.value)
                  return (
                    <div key={s.value} className={`opb-stage opb-stage--${state}`}>
                      <StageDot state={state} />
                      <span>{s.title}</span>
                    </div>
                  )
                })}
              </div>
            </div>

            <div className="opb-card opb-card--fill opb-console">
              <div className="opb-card-head">
                <h3 className="opb-card-title">{t('opb.build.logTitle')}</h3>
              </div>
              <Terminal
                key={terminalKey}
                username="fengyu-builder"
                commands={[`fyp build --project ${project.name}`]}
                outputs={{ 0: terminalLines }}
                typingSpeed={40}
                delayBetweenCommands={600}
                enableSound={false}
                className="opb-terminal"
              />
            </div>

            <div className="opb-actions opb-actions--split">
              <GhostButton disabled={building} onClick={() => setStep(1)}>{t('opb.common.prev')}</GhostButton>
              <GoldButton disabled={building} onClick={() => setStep(3)}>{t('opb.common.next')}</GoldButton>
            </div>
          </>
        ) : null}

        {/* Step 3: verify */}
        {step === 3 ? (
          <>
            <div className="opb-card">
              <div className="opb-card-head">
                <h3 className="opb-card-title">{t('opb.step.verify')}</h3>
              </div>
              <p className="opb-card-copy">{t('opb.verify.hint')}</p>
              <div className="opb-actions">
                <GoldButton disabled={building} onClick={verify}>{t('opb.build.verify')}</GoldButton>
                {!building ? (
                  <StatusChip
                    tone={status === 'done' ? 'success' : status === 'failed' || status === 'error' ? 'danger' : 'idle'}
                  >
                    {t(`opb.build.status.${status}`)}
                  </StatusChip>
                ) : null}
              </div>
              {building ? <Progress className="opb-mt" status="indeterminate" label={t(`opb.build.status.${status}`)} /> : null}
            </div>
            <div className="opb-actions">
              <GhostButton disabled={building} onClick={() => setStep(2)}>{t('opb.common.prev')}</GhostButton>
            </div>
          </>
        ) : null}
      </section>
    </div>
  )
}
