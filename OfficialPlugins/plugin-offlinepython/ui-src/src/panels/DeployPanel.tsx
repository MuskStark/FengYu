import { useEffect, useMemo, useRef, useState } from 'react'
import type { FileRef } from '@infinia/plugin-sdk'
import {
  FilePicker,
  GhostButton,
  GlowingEffect,
  GoldButton,
  Meteors,
  Progress,
  StatusChip,
  Terminal,
  useFengYuClient,
  useFengYuI18n,
} from '@infinia/plugin-ui'
import { IconCheck, IconPackage, IconPlayerStop } from '@tabler/icons-react'
import { checked, createPluginRpc } from '../rpc'
import { readJobSnapshot, type PanelActivity, type UiJobStatus } from '../jobState'

type Translate = (key: string, ...args: (string | number)[]) => string

interface Props {
  toast: (msg: string) => void
  onActivity: (activity: PanelActivity | null) => void
}

export default function DeployPanel({ toast, onActivity }: Props) {
  const client = useFengYuClient()
  const { t } = useFengYuI18n()

  // Typed RPC client generated from manifest rpc.methods. zipPath is a FileRef the host resolves to
  // an absolute path string before the worker receives it; the cast encodes that boundary.
  const rpc = useMemo(() => createPluginRpc(client), [client])
  // Abort in-flight RPC on unmount (transport-cancel). Domain job cancel (deploy.cancel) is separate.
  const abortRef = useRef<AbortController | null>(null)
  const signal = () => (abortRef.current ??= new AbortController()).signal
  const tRef = useRef<Translate>(t)
  tRef.current = t

  const [bundle, setBundle] = useState<FileRef | null>(null)
  const [targetKind, setTargetKind] = useState<'global' | 'venv'>('global')
  const [venvPath, setVenvPath] = useState('')
  const [logs, setLogs] = useState<string[]>([])
  const logsRef = useRef<string[]>([])
  const [status, setStatus] = useState<UiJobStatus>('idle')
  const [installing, setInstalling] = useState(false)
  const jobIdRef = useRef<string | null>(null)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const [jobSeq, setJobSeq] = useState(0)

  // ---- deployment-machine Python (auto-detect; manual override on failure) ----
  // The build machine's configured interpreter is NOT reused here: deploy often runs
  // on a different, offline machine whose interpreter (conda/pyenv/venv) is not on PATH.
  // We auto-detect THIS machine's python; if that fails, the user types the path.
  const [detecting, setDetecting] = useState(false)
  const [detectedExe, setDetectedExe] = useState<string | null>(null) // null = not detected (yet / at all)
  const [detectedVersion, setDetectedVersion] = useState<string | null>(null)
  const [manualExe, setManualExe] = useState('') // user override; empty = rely on detection

  function errorText(error: unknown): string {
    return error instanceof Error && error.message ? error.message : tRef.current('opb.common.error')
  }

  function stopPolling() {
    if (pollRef.current) clearInterval(pollRef.current)
    pollRef.current = null
  }

  /** The interpreter path that will be sent to deployStart: manual override wins, else detected. */
  function resolvedPythonExe(): string {
    const manual = manualExe.trim()
    if (manual) return manual
    return detectedExe ?? ''
  }

  async function detectPython() {
    setDetecting(true)
    try {
      const res = await rpc.pythonDetect({ executable: manualExe.trim() || undefined }, { signal: signal() })
      const d = res.detection
      if (d?.ok && d.executable) {
        setDetectedExe(d.executable)
        setDetectedVersion(d.pythonVersion ?? null)
      } else {
        setDetectedExe(null)
        setDetectedVersion(null)
      }
    } catch {
      setDetectedExe(null)
      setDetectedVersion(null)
    } finally {
      setDetecting(false)
    }
  }

  useEffect(() => { void detectPython() }, [])

  async function startInstall() {
    if (!bundle) { toast(t('opb.deploy.bundleRequired')); return }
    if (!resolvedPythonExe()) {
      toast(t('opb.deploy.notDetected'))
      return
    }
    logsRef.current = []
    setLogs([])
    setStatus('starting')
    setInstalling(true)
    setJobSeq((seq) => seq + 1)
    try {
      const target = targetKind === 'venv'
        ? { kind: 'venv' as const, pythonExe: resolvedPythonExe(), venvPath: venvPath }
        : { kind: 'global' as const, pythonExe: resolvedPythonExe() }
      const res = await checked(await rpc.deployStart({ zipPath: bundle as unknown as string, target }, { signal: signal() }))
      const id = res.jobId
      if (!id) {
        setStatus('error')
        setInstalling(false)
        toast(t('opb.deploy.failed'))
        return
      }
      jobIdRef.current = id
      pollRef.current = setInterval(pollStatus, 800)
    } catch (error) {
      setStatus('error')
      setInstalling(false)
      toast(errorText(error))
    }
  }

  async function pollStatus() {
    const id = jobIdRef.current
    if (!id) return
    try {
      const s = await rpc.deployStatus({ jobId: id, cursor: logsRef.current.length }, { signal: signal() })
      const snapshot = readJobSnapshot(s)
      if (snapshot.logs.length) {
        logsRef.current = logsRef.current.concat(snapshot.logs)
        setLogs(logsRef.current)
      }
      setStatus(snapshot.status)
      if (!snapshot.ok) {
        stopPolling()
        setInstalling(false)
        jobIdRef.current = null
        toast(snapshot.summary)
        return
      }
      if (snapshot.done) {
        setInstalling(false)
        stopPolling()
        jobIdRef.current = null
        toast(snapshot.error || snapshot.status === 'failed'
          ? tRef.current('opb.deploy.failed')
          : tRef.current('opb.deploy.completed', tRef.current(`opb.deploy.status.${snapshot.status}`)))
      }
    } catch (error) {
      stopPolling()
      setInstalling(false)
      setStatus('error')
      toast(errorText(error))
    }
  }

  async function cancel() {
    const id = jobIdRef.current
    if (!id) return
    try {
      await checked(await rpc.deployCancel({ jobId: id }, { signal: signal() }))
      setInstalling(false)
      stopPolling()
      jobIdRef.current = null
      setStatus('cancelled')
    } catch (error) {
      toast(errorText(error))
    }
  }

  // Report job activity to the shared header/status bar (cleared on unmount).
  useEffect(() => {
    onActivity({
      running: installing,
      tone: installing || status === 'starting' || status === 'running'
        ? 'warning'
        : status === 'done'
          ? 'success'
          : status === 'failed' || status === 'error'
            ? 'danger'
            : 'idle',
      label: t(`opb.deploy.status.${status}`),
      cancel: installing ? () => { void cancel() } : undefined,
    })
    return () => onActivity(null)
  }, [installing, status, t, onActivity])

  useEffect(() => () => {
    stopPolling()
    abortRef.current?.abort()
  }, [])

  const canInstall = Boolean(bundle)
    && resolvedPythonExe().length > 0
    && (targetKind === 'global' || Boolean(venvPath.trim()))
    && !installing

  // The Aceternity Terminal replays the latest install log snapshot, re-keyed per job.
  const terminalKey = `${jobSeq}-${installing ? 'live' : status}`
  const terminalLines = logs.length ? logs.slice(-24) : [t('opb.deploy.logEmpty')]

  return (
    <div className="opb-grid">
      {/* 左：bundle / 目标 / Python 配置 */}
      <section className="opb-col opb-col--scroll" aria-label={t('opb.deploy.title')}>
        <div className="opb-card">
          <div className="opb-card-head">
            <h3 className="opb-card-title">{t('opb.deploy.bundleTitle')}</h3>
          </div>
          <p className="opb-card-copy">{t('opb.deploy.bundleHint')}</p>
          <FilePicker value={bundle} onChange={setBundle} label={t('opb.deploy.selectZip')} extensions={['zip']} />
        </div>

        <div className="opb-card">
          <div className="opb-card-head">
            <h3 className="opb-card-title">{t('opb.deploy.targetTitle')}</h3>
          </div>
          <p className="opb-card-copy">{t('opb.deploy.targetHint')}</p>
          <div className="opb-segments" role="group" aria-label={t('opb.deploy.targetTitle')}>
            <button type="button" className="opb-segment" aria-pressed={targetKind === 'global'} onClick={() => setTargetKind('global')}>
              {t('opb.deploy.targetGlobal')}
            </button>
            <button type="button" className="opb-segment" aria-pressed={targetKind === 'venv'} onClick={() => setTargetKind('venv')}>
              {t('opb.deploy.targetVenv')}
            </button>
          </div>
          {targetKind === 'venv' ? (
            <input
              className="opb-input opb-mt"
              value={venvPath}
              placeholder={t('opb.deploy.venvHint')}
              onChange={(e) => setVenvPath(e.target.value)}
              aria-label={t('opb.deploy.venvPath')}
            />
          ) : null}
        </div>

        <div className="opb-card">
          <div className="opb-card-head">
            <h3 className="opb-card-title">{t('opb.deploy.pythonTitle')}</h3>
          </div>
          <p className="opb-card-copy">{t('opb.deploy.pythonHint')}</p>
          <div className="opb-actions">
            <GhostButton disabled={detecting || installing} onClick={detectPython}>
              {t('opb.deploy.redetect')}
            </GhostButton>
            {detecting ? (
              <StatusChip tone="idle">{t('opb.deploy.detecting')}</StatusChip>
            ) : detectedExe && !manualExe.trim() ? (
              <StatusChip tone="success">{t('opb.deploy.detected', detectedExe, detectedVersion ?? '')}</StatusChip>
            ) : !detectedExe && !manualExe.trim() ? (
              <StatusChip tone="danger">{t('opb.deploy.notDetected')}</StatusChip>
            ) : null}
          </div>
          <input
            className="opb-input opb-mt"
            value={manualExe}
            placeholder={detectedExe || t('opb.deploy.pythonExe')}
            onChange={(e) => setManualExe(e.target.value)}
            aria-label={t('opb.deploy.pythonExe')}
          />
        </div>
      </section>

      {/* 右：安装状态 + 控制台 */}
      <section className="opb-col opb-col--scroll" aria-label={t('opb.deploy.logTitle')}>
        <div className="opb-status-card">
          {installing ? (
            <>
              <GlowingEffect spread={60} borderWidth={1.5} glow disabled={false} />
              <Meteors number={10} />
            </>
          ) : null}
          <div className="opb-status-main">
            <div className="opb-row">
              <span style={{ fontSize: 14, fontWeight: 600 }}>{t('opb.deploy.title')}</span>
              {bundle ? <span className="opb-chip">{bundle.name}</span> : null}
              <span className="opb-chip">{targetKind === 'venv' ? t('opb.deploy.targetVenv') : t('opb.deploy.targetGlobal')}</span>
            </div>
            <p className="opb-card-copy">{t('opb.deploy.description')}</p>
            <div className="opb-actions">
              <GoldButton disabled={!canInstall} onClick={startInstall}>
                <IconPackage size={15} stroke={1.7} />
                {t('opb.deploy.start')}
              </GoldButton>
              {installing ? (
                <GhostButton className="opb-danger" onClick={cancel}>
                  <IconPlayerStop size={14} stroke={1.7} />
                  {t('opb.deploy.cancel')}
                </GhostButton>
              ) : (
                <StatusChip
                  tone={status === 'done' ? 'success' : status === 'failed' || status === 'error' ? 'danger' : 'idle'}
                >
                  {t(`opb.deploy.status.${status}`)}
                </StatusChip>
              )}
            </div>
            {installing ? <Progress className="opb-mt" status="indeterminate" label={t(`opb.deploy.status.${status}`)} /> : null}
          </div>
          <div className="opb-stages">
            <div className="opb-stage opb-stage--done">
              <span className="opb-dot--done">
                <IconCheck size={11} stroke={2.4} />
              </span>
              <span>{t('opb.deploy.bundleTitle')}</span>
            </div>
            <div className={`opb-stage opb-stage--${installing ? 'running' : status === 'done' ? 'done' : 'pending'}`}>
              {installing ? (
                <span className="opb-dot--running" />
              ) : status === 'done' ? (
                <span className="opb-dot--done">
                  <IconCheck size={11} stroke={2.4} />
                </span>
              ) : (
                <span className="opb-dot--pending" />
              )}
              <span>{t('opb.deploy.start')}</span>
            </div>
          </div>
        </div>

        <div className="opb-card opb-card--fill opb-console">
          <div className="opb-card-head">
            <h3 className="opb-card-title">{t('opb.deploy.logTitle')}</h3>
          </div>
          <Terminal
            key={terminalKey}
            username="fengyu-deployer"
            commands={[`fyp deploy --zip ${bundle ? bundle.name : '<bundle.zip>'} --target ${targetKind}`]}
            outputs={{ 0: terminalLines }}
            typingSpeed={40}
            delayBetweenCommands={600}
            enableSound={false}
            className="opb-terminal"
          />
        </div>
      </section>
    </div>
  )
}
