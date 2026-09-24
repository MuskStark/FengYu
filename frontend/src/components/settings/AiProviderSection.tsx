import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Eye, EyeOff, Plug } from 'lucide-react'
import type { AiConfigTestResult, AiSettings } from '@/services/types'
import { services } from '@/services'
import {
  AI_MODES,
  aiTestRequest,
  type AiFormState,
  type CloudProviderId,
  type ProviderForm,
} from './aiForm'

/** Typed patch builder — a computed union key alone would widen to a string index. */
function providerPatch(mode: CloudProviderId, next: ProviderForm): Partial<AiFormState> {
  if (mode === 'openai') return { openai: next }
  if (mode === 'anthropic') return { anthropic: next }
  return { deepseek: next }
}

/**
 * Section 1 — AI providers: mode segment (local/openai/anthropic/deepseek) plus the
 * selected source's endpoint / model / API key fields, live connection test, and the
 * shared save (PUT /api/ai/config persists every provider at once, like the Vue shell).
 */
export default function AiProviderSection({
  form,
  ai,
  saving,
  saveError,
  onPatch,
  onSave,
}: {
  form: AiFormState
  ai: AiSettings | null
  saving: boolean
  saveError: string | null
  onPatch: (patch: Partial<AiFormState>) => void
  onSave: () => void
}) {
  const { t } = useTranslation()
  const [showKey, setShowKey] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<AiConfigTestResult | null>(null)

  const isLocal = form.mode === 'local'
  // Capture the narrowed provider id + its form once — closure narrowing only survives
  // for const locals, and the JSX handlers below capture both.
  const providerMode: CloudProviderId | null = form.mode === 'local' ? null : form.mode
  const provider = providerMode ? form[providerMode] : null
  // OpenAI-compatible sources expect /v1 in the base URL; Anthropic does not.
  const needsV1 = form.mode === 'openai' || form.mode === 'deepseek'
  const keySet = ai && providerMode ? ai[providerMode].apiKeySet : false

  async function runTest(): Promise<void> {
    setTesting(true)
    setTestResult(null)
    try {
      setTestResult(await services.aiConfig.testConfig(aiTestRequest(form)))
    } catch (error) {
      setTestResult({ success: false, error: error instanceof Error ? error.message : String(error) })
    } finally {
      setTesting(false)
    }
  }

  return (
    <section>
      <h2 className="set-h">{t('aiSettings.providers')}</h2>
      <div className="cx-card">
        <div className="cx-setting-row">
          <div className="cx-setting-row__label"><span>{t('aiSettings.mode')}</span></div>
          <div className="cx-segment">
            {AI_MODES.map(mode => (
              <button
                key={mode}
                type="button"
                className={form.mode === mode ? 'active' : ''}
                onClick={() => onPatch({ mode })}
              >
                {t(`aiSettings.mode${mode.charAt(0).toUpperCase()}${mode.slice(1)}`)}
              </button>
            ))}
          </div>
        </div>

        {isLocal ? (
          <>
            <div className="cx-setting-row">
              <div className="cx-setting-row__label"><span>{t('aiSettings.ollamaUrl')}</span></div>
              <input
                className="cx-input"
                style={{ flex: '1 1 auto', maxWidth: 320 }}
                placeholder="http://127.0.0.1:11434"
                value={form.ollama.baseUrl}
                onChange={event => onPatch({ ollama: { ...form.ollama, baseUrl: event.target.value } })}
              />
            </div>
            <div className="cx-setting-row">
              <div className="cx-setting-row__label"><span>{t('aiSettings.model')}</span></div>
              <input
                className="cx-input"
                style={{ flex: '1 1 auto', maxWidth: 320 }}
                placeholder="llama3"
                value={form.ollama.model}
                onChange={event => onPatch({ ollama: { ...form.ollama, model: event.target.value } })}
              />
            </div>
          </>
        ) : provider && providerMode ? (
          <>
            <div className="cx-setting-row">
              <div className="cx-setting-row__label"><span>{t('aiSettings.apiKey')}</span></div>
              <div className="prov-key-row" style={{ flex: '1 1 auto', maxWidth: 320 }}>
                <input
                  className="cx-input"
                  type={showKey ? 'text' : 'password'}
                  placeholder={keySet ? t('aiSettings.apiKeyHint') : ''}
                  value={provider.apiKey}
                  onChange={event =>
                    onPatch(providerPatch(providerMode, { ...provider, apiKey: event.target.value }))}
                  style={{ borderRadius: 'var(--cx-radius) 0 0 var(--cx-radius)' }}
                />
                <button
                  type="button"
                  className="prov-key-mini"
                  title={showKey ? t('aiSettings.hideKey') : t('aiSettings.showKey')}
                  onClick={() => setShowKey(visible => !visible)}
                >
                  {showKey ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </div>
            <div className="cx-setting-row">
              <div className="cx-setting-row__label">
                <span className="set-row-text">
                  {t('aiSettings.endpoint')}
                  <small>{needsV1 ? t('aiSettings.endpointHintOpenai') : t('aiSettings.endpointHintAnthropic')}</small>
                </span>
              </div>
              <input
                className="cx-input"
                style={{ flex: '1 1 auto', maxWidth: 320 }}
                placeholder={needsV1 ? 'https://api.openai.com/v1' : 'https://api.anthropic.com'}
                value={provider.endpoint}
                onChange={event =>
                  onPatch(providerPatch(providerMode, { ...provider, endpoint: event.target.value }))}
              />
            </div>
            <div className="cx-setting-row">
              <div className="cx-setting-row__label"><span>{t('aiSettings.model')}</span></div>
              <input
                className="cx-input"
                style={{ flex: '1 1 auto', maxWidth: 320 }}
                placeholder={t('aiSettings.modelNamePh')}
                value={provider.model}
                onChange={event =>
                  onPatch(providerPatch(providerMode, { ...provider, model: event.target.value }))}
              />
            </div>
          </>
        ) : null}

        {testResult && (
          <div className={`cx-alert ${testResult.success ? 'cx-alert--success' : 'cx-alert--error'}`}>
            <span className="cx-alert__body">
              {testResult.success ? t('aiSettings.testSuccess') : t('aiSettings.testFailed')}
              {testResult.error ? <div style={{ fontSize: 12 }}>{testResult.error}</div> : null}
              {testResult.warning ? <div style={{ fontSize: 12 }}>{testResult.warning}</div> : null}
            </span>
          </div>
        )}
      </div>

      <div className="set-save-row">
        <button
          type="button"
          className="cx-btn"
          disabled={testing}
          onClick={() => void runTest()}
        >
          {testing ? <span className="cx-spin" /> : <Plug size={16} />}
          {testing ? t('aiSettings.testing') : t('aiSettings.test')}
        </button>
        <button type="button" className="cx-btn cx-btn--primary" disabled={saving} onClick={onSave}>
          {saving ? <span className="cx-spin" /> : null}
          {t('aiSettings.save')}
        </button>
        {saveError ? <span className="cx-chip cx-chip--error">{saveError}</span> : null}
      </div>
    </section>
  )
}
