import { useEffect, useMemo, useRef, useState } from 'react'
import {
  EmptyState,
  GhostButton,
  LoadingState,
  Page,
  PageHeader,
  StatusChip,
  useFengYuClient,
  useFengYuI18n,
} from '@infinia/plugin-ui'
import { IconRefresh, IconStethoscope } from '@tabler/icons-react'
import { createPluginRpc } from '../rpc'

type Translate = (key: string, ...args: (string | number)[]) => string

interface Props {
  toast: (msg: string) => void
}

interface Check { id?: string; value?: string | null; ok?: boolean }
interface Detection { executable?: string | null; pythonVersion?: string | null; pipVersion?: string | null; ok?: boolean }

export default function DoctorPanel({ toast }: Props) {
  const client = useFengYuClient()
  const { t } = useFengYuI18n()

  // Typed RPC client generated from manifest rpc.methods.
  const rpc = useMemo(() => createPluginRpc(client), [client])
  const abortRef = useRef<AbortController | null>(null)
  const signal = () => (abortRef.current ??= new AbortController()).signal
  const tRef = useRef<Translate>(t)
  tRef.current = t

  const [detection, setDetection] = useState<Detection | null>(null)
  const [checks, setChecks] = useState<Check[]>([])
  const [loading, setLoading] = useState(false)

  // Worker returns locale-independent short labels for status-flavored values;
  // translate them here. Data values (versions, paths) pass through unchanged.
  const VALUE_LABELS = new Set(['not_found', 'missing', 'supported', 'unsupported', 'reachable', 'unreachable'])

  function checkName(id?: string): string {
    return id ? t(`opb.doctor.check.${id}`) : ''
  }

  function checkValue(c: Check): string {
    const v = c.value ?? ''
    if (VALUE_LABELS.has(v)) return t(`opb.doctor.value.${v}`)
    if (c.id === 'disk_space') return t('opb.doctor.value.gb_available', v)
    return v
  }

  function errorText(error: unknown): string {
    return error instanceof Error && error.message ? error.message : tRef.current('opb.common.error')
  }

  async function refresh() {
    setLoading(true)
    try {
      const det = await rpc.pythonDetect({}, { signal: signal() })
      setDetection(det.success ? (det.detection ?? null) : null)
      const doc = await rpc.doctor({}, { signal: signal() })
      setChecks(doc.success ? (doc.checks ?? []) : [])
      if (!doc.success) toast(doc.summary)
    } catch (error) {
      toast(errorText(error))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void refresh() }, [])
  useEffect(() => () => abortRef.current?.abort(), [])

  return (
    <Page>
      <PageHeader
        title={t('opb.doctor.title')}
        description={t('opb.doctor.description')}
        right={
          <GhostButton disabled={loading} onClick={refresh}>
            <IconRefresh size={14} stroke={1.6} />
            {loading ? t('opb.doctor.checking') : t('opb.doctor.refresh')}
          </GhostButton>
        }
      />

      <section className="opb-col" style={{ overflow: 'visible' }}>
        <div className="opb-card">
          <div className="opb-card-head">
            <h3 className="opb-card-title">{t('opb.doctor.runtimeTitle')}</h3>
            {detection?.ok ? (
              <StatusChip tone="success">{t('opb.python.detected', detection.pythonVersion ?? '', detection.pipVersion ?? '')}</StatusChip>
            ) : detection ? (
              <StatusChip tone="danger">{t('opb.python.missing')}</StatusChip>
            ) : (
              <StatusChip tone="idle">{t('opb.doctor.notChecked')}</StatusChip>
            )}
          </div>
          <p className="opb-card-copy" style={{ marginBottom: 0 }}>{t('opb.doctor.runtimeHint')}</p>
          {detection?.executable ? (
            <code className="opb-path opb-anywhere opb-mt" style={{ flex: 'none' }}>{detection.executable}</code>
          ) : null}
        </div>

        <div className="opb-card">
          <div className="opb-card-head">
            <h3 className="opb-card-title">{t('opb.doctor.checksTitle')}</h3>
          </div>
          <p className="opb-card-copy">{t('opb.doctor.checksHint')}</p>

          {loading && !checks.length ? (
            <LoadingState label={t('opb.doctor.checking')} />
          ) : !checks.length ? (
            <EmptyState
              icon={<IconStethoscope size={20} stroke={1.6} />}
              title={t('opb.doctor.noChecks')}
              message={t('opb.doctor.emptyHint')}
            />
          ) : (
            <div className="opb-scrollx">
              <table className="opb-table">
                <thead>
                  <tr>
                    <th>{t('opb.doctor.check')}</th>
                    <th>{t('opb.doctor.value')}</th>
                    <th>{t('opb.doctor.status')}</th>
                  </tr>
                </thead>
                <tbody>
                  {checks.map((c) => (
                    <tr key={c.id}>
                      <td>{checkName(c.id)}</td>
                      <td className="opb-anywhere">{checkValue(c)}</td>
                      <td>
                        <StatusChip tone={c.ok ? 'success' : 'danger'}>
                          {c.ok ? t('opb.common.ok') : t('opb.common.fail')}
                        </StatusChip>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </section>
    </Page>
  )
}
