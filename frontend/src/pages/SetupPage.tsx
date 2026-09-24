import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import '@/styles/settings.css'
import type { ConnectionTestRequest, ConnectionTestResult, DbTypeMeta, WizardParams } from '@/services/types'
import { services } from '@/services'
import { instance } from '@/i18n'

/**
 * First-launch setup wizard (React port of the Vue SetupWizard.vue): choose a database
 * type, configure it (fields come from the backend's DbTypeMeta), test the connection,
 * initialize, then wait out the backend's SETUP→APP restart and reload into the shell.
 * When the type catalog cannot be loaded, the wizard degrades to read-only guidance.
 */

/** Must outlast the desktop supervisor's boot patience (health 120 s + setup probe ~20 s). */
const APP_MODE_WAIT_MS = 150_000

interface WizardState {
  types: DbTypeMeta[]
  selectedType: string
  params: WizardParams
  testResult: ConnectionTestResult | null
  testing: boolean
  initializing: boolean
  error: string
}

export default function SetupPage() {
  const { t } = useTranslation()
  const [step, setStep] = useState<1 | 2 | 3>(1)
  const [catalogFailed, setCatalogFailed] = useState(false)
  const [state, setState] = useState<WizardState>({
    types: [], selectedType: '', params: {}, testResult: null, testing: false, initializing: false, error: '',
  })
  const [restartFailed, setRestartFailed] = useState(false)

  useEffect(() => {
    let cancelled = false
    void services.system.setupTypes()
      .then(types => {
        if (cancelled) return
        setState(current => ({ ...current, types }))
        const h2 = types.find(meta => meta.type === 'h2')
        if (h2) selectType('h2', types)
      })
      .catch(() => { if (!cancelled) setCatalogFailed(true) })
    return () => { cancelled = true }
  }, [])

  const selectedMeta = state.types.find(meta => meta.type === state.selectedType) ?? null
  const canInitialize = state.testResult?.success === true

  function selectType(type: string, types: DbTypeMeta[] = state.types): void {
    const meta = types.find(item => item.type === type)
    const params: WizardParams = {}
    if (meta) {
      for (const field of meta.fields) {
        if (field.default !== undefined) {
          (params as Record<string, unknown>)[field.name] = field.default
        }
      }
    }
    setState(current => ({ ...current, selectedType: type, params, testResult: null }))
  }

  function chooseType(type: string): void {
    selectType(type)
    setStep(2)
  }

  /** Field label: i18n key first, then the server-supplied label, then the raw name. */
  function fieldLabel(name: string): string {
    if (instance.exists(`setup.fields.${name}`)) return t(`setup.fields.${name}`)
    if (name === 'filePath') return t('setup.dataFileLocation')
    return selectedMeta?.fields.find(field => field.name === name)?.label ?? name
  }

  function setParam(name: string, value: string): void {
    setState(current => ({
      ...current,
      params: { ...current.params, [name]: value } as WizardParams,
    }))
  }

  async function runTest(): Promise<void> {
    if (!state.selectedType) return
    setState(current => ({ ...current, testing: true, testResult: null, error: '' }))
    try {
      const result = await services.system.testConnection({ type: state.selectedType, params: state.params })
      setState(current => ({ ...current, testResult: result }))
    } catch (error) {
      setState(current => ({
        ...current,
        testResult: { success: false, error: error instanceof Error ? error.message : String(error) },
      }))
    } finally {
      setState(current => ({ ...current, testing: false }))
    }
  }

  async function initialize(): Promise<void> {
    if (!state.selectedType) return
    setState(current => ({ ...current, initializing: true, error: '' }))
    const request: ConnectionTestRequest = { type: state.selectedType, params: state.params }
    let ok = false
    try {
      const result = await services.system.initialize(request)
      ok = result.success
      if (!result.success) {
        setState(current => ({ ...current, error: result.error ?? 'Initialization failed' }))
      }
    } catch (error) {
      setState(current => ({
        ...current,
        error: error instanceof Error ? error.message : String(error),
      }))
    } finally {
      setState(current => ({ ...current, initializing: false }))
    }
    if (!ok) return

    // Wait out the backend restart, then reload — the boot probe re-runs against the
    // live backend and lands in the app shell (APP mode) instead of this wizard.
    setStep(3)
    const outcome = await waitForAppMode()
    if (outcome === 'app') {
      window.location.reload()
      return
    }
    setRestartFailed(true)
  }

  return (
    <div className="cx-setup-wrap">
      <div className="cx-card cx-setup-card">
        <div className="cx-setup-title">{t('setup.title', { brand: t('brand') })}</div>
        <div className="cx-muted" style={{ margin: '4px 0 20px' }}>{t('setup.subtitle')}</div>

        {catalogFailed ? (
          <div className="cx-alert cx-alert--warn">
            <span className="cx-alert__body">
              {t('common.unexpectedError')}
              <div style={{ fontSize: 12 }}>{t('setup.subtitle')}</div>
            </span>
            <button
              type="button"
              className="cx-btn cx-btn--sm"
              onClick={() => window.location.reload()}
            >
              {t('common.retry')}
            </button>
          </div>
        ) : step === 1 ? (
          <div className="cx-setup-grid">
            {state.types.map(meta => (
              <button
                key={meta.type}
                type="button"
                className={`cx-card cx-card--hover ${state.selectedType === meta.type ? 'cx-selected' : ''}`}
                style={{ padding: 16, textAlign: 'left' }}
                onClick={() => chooseType(meta.type)}
              >
                <div style={{ fontWeight: 650 }}>{meta.label}</div>
                <div
                  className="cx-muted"
                  style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.04em' }}
                >
                  {meta.embedded ? t('setup.local') : t('setup.remote')}
                </div>
              </button>
            ))}
            {state.types.length === 0 && <p className="cx-muted">{t('common.loading')}</p>}
          </div>
        ) : step === 2 ? (
          <div>
            <button type="button" className="cx-btn cx-btn--text cx-btn--sm" onClick={() => setStep(1)}>
              {t('common.back')}
            </button>
            <h2 style={{ fontSize: 17, fontWeight: 650, margin: '8px 0 18px' }}>
              {t('setup.configureTitle', { label: selectedMeta?.label ?? '' })}
            </h2>

            {(selectedMeta?.fields ?? []).map(field => (
              <div key={field.name} className="cx-field" style={{ marginBottom: 14 }}>
                <label className="cx-label" htmlFor={`cx-setup-${field.name}`}>
                  {fieldLabel(field.name)}
                </label>
                <input
                  id={`cx-setup-${field.name}`}
                  className="cx-input"
                  type={field.secret ? 'password' : 'text'}
                  placeholder={field.name}
                  value={String((state.params as Record<string, unknown>)[field.name] ?? '')}
                  onChange={event => setParam(field.name, event.target.value)}
                />
              </div>
            ))}

            {state.error && (
              <div className="cx-alert cx-alert--error" style={{ marginBottom: 12 }}>
                <span className="cx-alert__body">{state.error}</span>
              </div>
            )}

            <div className="cx-setup-actions">
              <button
                type="button"
                className="cx-btn cx-btn--tonal"
                disabled={state.testing}
                onClick={() => void runTest()}
              >
                {state.testing && <span className="cx-spin" />}
                {t('setup.testConnection')}
              </button>
              {state.testResult && (
                <div className={`cx-setup-test ${state.testResult.success ? 'cx-setup-ok' : 'cx-setup-err'}`}>
                  {state.testResult.success
                    ? t('setup.connected', { version: state.testResult.serverVersion })
                    : state.testResult.error}
                </div>
              )}
              <button
                type="button"
                className="cx-btn cx-btn--primary"
                disabled={!canInitialize || state.initializing}
                onClick={() => void initialize()}
              >
                {state.initializing && <span className="cx-spin" />}
                {t('setup.initialize')}
              </button>
            </div>
          </div>
        ) : (
          <div className="cx-setup-restarting">
            <span className="cx-spin lg" style={{ marginBottom: 16 }} />
            <p>{t('setup.restarting')}</p>
            {restartFailed && (
              <>
                <div className="cx-alert cx-alert--error" style={{ marginTop: 12 }}>
                  <span className="cx-alert__body">{t('setup.restartTimeout')}</span>
                </div>
                <button
                  type="button"
                  className="cx-btn cx-btn--outline cx-btn--sm"
                  style={{ marginTop: 12 }}
                  onClick={() => window.location.reload()}
                >
                  {t('auth.reload')}
                </button>
              </>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

/**
 * Poll until the restarted backend is provably in APP mode (the Vue shell's
 * setupRestartWait contract): a 200 from /api/setup/status is NOT success — the
 * still-exiting SETUP backend answers 200 for ~1 s after the config was persisted, and
 * APP mode does not serve /api/setup/** at all. Only the 404 proves APP mode.
 */
async function waitForAppMode(): Promise<'app' | 'timeout'> {
  const deadline = Date.now() + APP_MODE_WAIT_MS
  while (Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 500))
    try {
      const health = await services.system.health()
      if (health.status !== 'ok') continue
      await services.system.setupStatus()
    } catch (error) {
      // Structural status read (the http transport rejects the raw axios error);
      // isAxiosError-style imports stay out of view code.
      if ((error as { response?: { status?: number } } | null)?.response?.status === 404) return 'app'
      // Backend still down (or non-404 failure) — keep polling.
    }
  }
  return 'timeout'
}
